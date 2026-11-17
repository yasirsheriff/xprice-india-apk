"""
Backfills past prices from archived copies of xbox.com/en-IN product pages on the
Internet Archive (web.archive.org). Each archived page embeds Microsoft's own price data,
e.g. "listPrice":3999,"msrp":3999,...,"currencyCode":"INR", so these are genuine prices
for that date -- just sparse (popular games: dozens of snapshots since ~2022; niche games: few).

Runs a little each day inside a time budget, politely (one request at a time, ~1/sec),
and remembers progress in archive/progress.json so it resumes where it stopped.
Archived points go into prices/<ID>.json under "a": [["YYYY-MM-DD", price, mrp], ...].
"""
import gzip
import json
import os
import re
import time
import urllib.parse
import urllib.request

USER_AGENT = "LootwisePriceCollector/1.0 (archive backfill)"
CDX = "https://web.archive.org/cdx/search/cdx"
REQUEST_GAP_SEC = 1.0
MAX_SNAPSHOTS_PER_GAME = 40
RECHECK_DAYS = 60  # look for new snapshots of finished games every ~2 months

_NUM = r"([0-9]+(?:\.[0-9]+)?)"

_last_request = 0.0


def _get(url, timeout=60, retries=3):
    """Polite GET: at most ~1 request/second, backs off on errors and 429s."""
    global _last_request
    for attempt in range(retries):
        wait = REQUEST_GAP_SEC - (time.monotonic() - _last_request)
        if wait > 0:
            time.sleep(wait)
        _last_request = time.monotonic()
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "gzip"})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = resp.read()
            if data[:2] == b"\x1f\x8b":
                data = gzip.decompress(data)
            return data.decode("utf-8", errors="ignore")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(10 * (attempt + 1) if e.code == 429 else 2 ** attempt)
        except Exception:  # noqa: BLE001
            time.sleep(2 ** attempt)
    return None


def list_snapshots(page_url):
    """One archived capture per day (HTTP 200 only) for this product page. Returns [(timestamp, original)]."""
    bare = re.sub(r"^https?://", "", page_url)
    query = urllib.parse.urlencode({
        "url": bare, "matchType": "prefix", "output": "json",
        "fl": "timestamp,original", "filter": "statuscode:200", "collapse": "timestamp:8",
    })
    body = _get(f"{CDX}?{query}")
    try:
        rows = json.loads(body or "[]")[1:]
    except ValueError:
        return []
    return [(r[0], r[1]) for r in rows if len(r) >= 2]


def sample_evenly(rows, limit):
    """Keep at most one snapshot per month, then thin evenly to `limit`, always keeping first and last."""
    by_month = {}
    for ts, orig in sorted(rows):
        by_month.setdefault(ts[:6], (ts, orig))
    rows = list(by_month.values())
    if len(rows) <= limit:
        return rows
    step = (len(rows) - 1) / (limit - 1)
    return [rows[round(i * step)] for i in range(limit)]


def _offers(array_text):
    """Yields each top-level {...} object of a JSON array that starts at array_text[0] == '['."""
    depth, obj_start, in_str, esc = 0, None, False, False
    for i, ch in enumerate(array_text):
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            if depth == 0:
                obj_start = i
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0 and obj_start is not None:
                yield array_text[obj_start: i + 1]
        elif ch == "]" and depth == 0:
            return


def _public_inr_price(offer):
    """(listPrice, msrp) for a public, purchasable INR offer; None for member-only or non-INR offers.
    Handles both page formats: 2022-23 ("remediations") and 2024+ ("eligibilityInfo")."""
    lp = re.search(r'"listPrice":' + _NUM, offer)
    ms = re.search(r'"msrp":' + _NUM, offer)
    cur = re.search(r'"currency(?:Code)?":"([A-Z]{3})"', offer)
    actions = re.search(r'"availabilityActions":\[([^\]]*)\]', offer)
    if not (lp and ms and cur and actions) or cur.group(1) != "INR" or '"Purchase"' not in actions.group(1):
        return None
    if re.search(r'"remediations":\[\s*\{', offer):
        return None  # needs Game Pass / EA Play ("Upsell")
    for elig in re.findall(r'"eligibility":"([^"]*)"', offer):
        if elig not in ("None", ""):
            return None  # member-only discount
    price, msrp = float(lp.group(1)), float(ms.group(1))
    return (price, msrp) if price > 0 else None


def price_from_page(html, product_id):
    """Finds this product's public INR purchase price in an archived page. Returns (price, mrp) or None."""
    for m in re.finditer(r'"productId":"%s"' % product_id, html, flags=re.IGNORECASE):
        window = html[m.start(): m.start() + 20000]
        start = window.find('"purchaseable":[')
        if start < 0:
            continue
        nxt = window.find('"productId":"', start)  # never read the next product's prices
        if 0 < nxt < start:
            continue
        array = window[start + len('"purchaseable":'):]
        prices = [p for p in (_public_inr_price(o) for o in _offers(array)) if p]
        if prices:
            lp, ms = min(prices)
            return round(lp), max(round(lp), round(ms))
    return None


def backfill(data_dir, ranked_ids, pages, minutes):
    """Process games in `ranked_ids` order (most popular first) until the time budget runs out."""
    os.makedirs(os.path.join(data_dir, "archive"), exist_ok=True)
    progress_path = os.path.join(data_dir, "archive", "progress.json")
    try:
        with open(progress_path, encoding="utf-8") as f:
            progress = json.load(f)
    except (FileNotFoundError, ValueError):
        progress = {"done": {}}
    done = progress.setdefault("done", {})  # id -> {"checked": "YYYY-MM-DD", "points": n}

    deadline = time.monotonic() + minutes * 60
    today = time.strftime("%Y-%m-%d")

    def due(pid):
        info = done.get(pid)
        if not info:
            return True
        y, m, d = map(int, info["checked"].split("-"))
        import datetime as dt
        return (dt.date.today() - dt.date(y, m, d)).days >= RECHECK_DAYS

    queue = [pid for pid in ranked_ids if pid in pages and due(pid)]
    processed, added_total = 0, 0
    print(f"archive backfill: {len(queue)} games due, budget {minutes:.0f} min")

    for pid in queue:
        if time.monotonic() > deadline:
            break
        path = os.path.join(data_dir, "prices", f"{pid}.json")
        try:
            with open(path, encoding="utf-8") as f:
                record = json.load(f)
        except (FileNotFoundError, ValueError):
            continue

        existing = {row[0]: row for row in record.get("a", [])}
        snapshots = sample_evenly(list_snapshots(pages[pid]), MAX_SNAPSHOTS_PER_GAME)
        for ts, original in snapshots:
            if time.monotonic() > deadline:
                break
            day = f"{ts[:4]}-{ts[4:6]}-{ts[6:8]}"
            if day in existing:
                continue
            html = _get(f"https://web.archive.org/web/{ts}id_/{original}")
            found = price_from_page(html or "", pid)
            if found:
                existing[day] = [day, found[0], found[1]]
                added_total += 1
        else:
            # Only mark finished when every snapshot was looked at within the budget.
            done[pid] = {"checked": today, "points": len(existing)}

        if existing:
            record["a"] = [existing[d] for d in sorted(existing)]
            with open(path, "w", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
        processed += 1

        # Save progress as we go so a cancelled run loses little.
        with open(progress_path, "w", encoding="utf-8") as f:
            json.dump(progress, f, separators=(",", ":"))

    remaining = sum(1 for pid in ranked_ids if pid in pages and due(pid))
    print(f"archive backfill: {processed} games looked at, {added_total} archived prices added, {remaining} games still due")
