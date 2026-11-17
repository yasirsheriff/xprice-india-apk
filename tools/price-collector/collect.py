#!/usr/bin/env python3
"""
Lootwise price collector: snapshots every game on Microsoft Store India once a day.

Sources (all public):
  * https://www.xbox.com/sitemap.xml          -> every en-IN product page (product IDs)
  * catalog.gamepass.com sigls                -> Game Pass catalogue IDs
  * displaycatalog.mp.microsoft.com           -> live prices + Microsoft's recent best-price windows

Output (written into --data-dir, committed to the `price-data` branch by CI):
  meta.json                 {"updated": "YYYY-MM-DD", "games": N, ...}
  prices/<PRODUCTID>.json   {"id", "title", "h": [["YYYY-MM-DD", priceInr], ...]}  (change points only)
  lists/top.json            most-rated games      [[id, title, price, mrp, discountPct], ...]
  lists/deals.json          discounted games, biggest discount first
  lists/new.json            released in the last 120 days, newest first
  skip.json                 non-game product IDs (DLC, currency...), re-checked weekly
  archive/progress.json     archive backfill progress (see archive.py)

prices/<ID>.json may also hold "a": [["YYYY-MM-DD", priceInr, mrpInr], ...] -- prices read from
archived copies of the xbox.com/en-IN product page on web.archive.org (sparse, but genuine).

Only the Python standard library is used.
"""
import argparse
import datetime as dt
import gzip
import json
import os
import re
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
USER_AGENT = "LootwisePriceCollector/1.0"
SITEMAP_INDEX = "https://www.xbox.com/sitemap.xml"
DISPLAYCATALOG = "https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds={ids}&market=IN&languages=en-in"
GAMEPASS_SIGLS = [
    "f6f1f99f-9b49-4ccd-b3bf-4d9767a77f5e",  # All console games
    "fdd9e2a7-0fee-49f6-ad69-4354098401ff",  # All PC games
    "393f05bf-e596-4ef6-9487-6d4fa0eab987",  # Leaving soon
]
ID_RE = re.compile(r"^[9BC][A-Z0-9]{11}$")
BATCH = 100
LIST_SIZE = 300
SKIP_RECHECK_DAYS = 7


def http_get(url, retries=4, timeout=40):
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = resp.read()
                if url.endswith(".gz"):
                    data = gzip.decompress(data)
                return data.decode("utf-8")
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2 ** attempt)
    print(f"  ! giving up on {url[:120]}: {last}", file=sys.stderr)
    return None


# --------------------------------------------------------------------------- discovery
def sitemap_ids():
    """Returns {productId: product page URL} for every en-IN product page."""
    index = http_get(SITEMAP_INDEX) or ""
    maps = sorted(set(re.findall(r"https://www\.xbox\.com/sitemap/pdp-en-IN-sitemap-\d+\.xml\.gz", index)))
    pages = {}
    for m in maps:
        body = http_get(m) or ""
        for loc in re.findall(r"<loc>([^<]+)</loc>", body):
            pid = loc.rstrip("/").rsplit("/", 1)[-1].upper()
            if ID_RE.match(pid):
                pages[pid] = loc.rstrip("/")
    print(f"sitemap: {len(maps)} files, {len(pages)} product IDs")
    return pages


def gamepass_ids():
    ids = set()
    for sigl in GAMEPASS_SIGLS:
        body = http_get(f"https://catalog.gamepass.com/sigls/v2?id={sigl}&language=en-in&market=IN")
        try:
            for item in json.loads(body or "[]"):
                pid = item.get("id", "")
                if ID_RE.match(pid):
                    ids.add(pid)
        except ValueError:
            pass
    print(f"game pass: {len(ids)} product IDs")
    return ids


# --------------------------------------------------------------------------- parsing
def iso_date(s):
    """'2026-10-20T09:59:59.0000000Z' -> date (None if missing / sentinel)."""
    if not s or len(s) < 10:
        return None
    try:
        d = dt.date.fromisoformat(s[:10])
    except ValueError:
        return None
    if d.year < 2010 or d.year > 9000:
        return None
    return d


def parse_product(p, today):
    """Returns a dict for a purchasable game, {"skip": True} for non-games, or None."""
    kind, ptype = p.get("ProductKind", ""), p.get("ProductType", "")
    if kind in ("Durable", "Consumable") or ptype in ("Durable", "Consumable"):
        return {"skip": True}
    loc = (p.get("LocalizedProperties") or [{}])[0]
    title = (loc.get("ProductTitle") or "").strip()
    if not title:
        return None

    best_price, best_msrp, best_sku = None, None, None
    for sku in p.get("DisplaySkuAvailabilities") or []:
        for av in sku.get("Availabilities") or []:
            if "Purchase" not in (av.get("Actions") or []) or av.get("RemediationRequired"):
                continue  # not buyable, or a member-only (Game Pass / EA Play) price
            price = (av.get("OrderManagementData") or {}).get("Price") or {}
            lp, msrp = price.get("ListPrice", -1), price.get("MSRP", -1)
            if lp and msrp and lp > 0 and msrp > 0 and (best_price is None or lp < best_price):
                best_price, best_msrp, best_sku = lp, msrp, sku

    if best_price is None:
        return None  # free, unreleased or not purchasable in India

    price = round(best_price)
    mrp = max(price, round(best_msrp))

    # Microsoft's recent best-price windows for the cheapest SKU -> real dated change points.
    window_points = []
    for hb in best_sku.get("HistoricalBestAvailabilities") or []:
        hp = (hb.get("OrderManagementData") or {}).get("Price") or {}
        lp, msrp = hp.get("ListPrice", -1), hp.get("MSRP", -1)
        if not (lp and msrp and 0 < lp < msrp):
            continue
        cond = hb.get("Conditions") or {}
        start, end = iso_date(cond.get("StartDate")), iso_date(cond.get("EndDate"))
        if start is None or start > today:
            continue
        window_points.append((start, round(lp)))
        if end is not None and end < today:
            window_points.append((end, round(msrp)))  # sale ended -> back to regular price

    usage = ((p.get("MarketProperties") or [{}])[0].get("UsageData") or [])
    rating_count = max((u.get("RatingCount", 0) for u in usage), default=0)
    release = iso_date(((p.get("MarketProperties") or [{}])[0].get("OriginalReleaseDate")))

    return {
        "id": p.get("ProductId"),
        "title": title,
        "price": price,
        "mrp": mrp,
        "pct": round((mrp - price) * 100 / mrp) if mrp > price else 0,
        "ratings": rating_count,
        "release": release,
        "windows": window_points,
    }


# --------------------------------------------------------------------------- history files
def merge_history(existing, windows, today, price):
    """Union of points with priority today's observation > stored history > window-derived; keep change points only."""
    by_day = {}
    for d, p in windows:
        by_day[d.isoformat()] = p
    for d, p in existing:
        by_day[d] = p
    by_day[today.isoformat()] = price
    out = []
    for d in sorted(by_day):
        p = by_day[d]
        if not out or out[-1][1] != p:
            out.append([d, p])
    return out


def write_if_changed(path, obj):
    text = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
    try:
        with open(path, encoding="utf-8") as f:
            if f.read() == text:
                return False
    except FileNotFoundError:
        pass
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return True


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, ValueError):
        return default


# --------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="data")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--max-ids", type=int, default=0, help="limit for local testing")
    ap.add_argument("--backfill-minutes", type=float, default=0, help="time budget for the web.archive.org backfill")
    ap.add_argument("--backfill-top", type=int, default=1500, help="backfill the N most-rated games")
    args = ap.parse_args()

    today = dt.datetime.now(IST).date()
    prices_dir = os.path.join(args.data_dir, "prices")
    lists_dir = os.path.join(args.data_dir, "lists")
    os.makedirs(prices_dir, exist_ok=True)
    os.makedirs(lists_dir, exist_ok=True)

    skip = load_json(os.path.join(args.data_dir, "skip.json"), {"refreshed": "2000-01-01", "ids": []})
    recheck = (today - dt.date.fromisoformat(skip["refreshed"])).days >= SKIP_RECHECK_DAYS
    skip_ids = set() if recheck else set(skip["ids"])

    known = {f[:-5] for f in os.listdir(prices_dir) if f.endswith(".json")}
    pages = sitemap_ids()
    ids = sorted((set(pages) | gamepass_ids() | known) - skip_ids)
    if args.max_ids:
        ids = ids[: args.max_ids]
    print(f"checking {len(ids)} products ({'full re-check' if recheck else f'{len(skip_ids)} skipped'})")

    def fetch(batch):
        body = http_get(DISPLAYCATALOG.format(ids=",".join(batch)))
        if body is None:
            return batch, None
        try:
            return batch, json.loads(body).get("Products", [])
        except ValueError:
            return batch, None

    games, new_skips, failed_batches, changed = [], set(), 0, 0
    batches = [ids[i:i + BATCH] for i in range(0, len(ids), BATCH)]
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for n, (batch, products) in enumerate(pool.map(fetch, batches), 1):
            if products is None:
                failed_batches += 1
                continue
            for p in products:
                g = parse_product(p, today)
                if g is None:
                    continue
                if g.get("skip"):
                    new_skips.add(p.get("ProductId"))
                    continue
                games.append(g)
            if n % 50 == 0:
                print(f"  {n}/{len(batches)} batches")

    for g in games:
        path = os.path.join(prices_dir, f"{g['id']}.json")
        old = load_json(path, {})
        hist = merge_history(old.get("h", []), g["windows"], today, g["price"])
        out = {"id": g["id"], "title": g["title"], "h": hist}
        if old.get("a"):
            out["a"] = old["a"]
        if write_if_changed(path, out):
            changed += 1

    def row(g):
        return [g["id"], g["title"], g["price"], g["mrp"], g["pct"]]

    top = sorted(games, key=lambda g: -g["ratings"])[:LIST_SIZE]
    deals = sorted((g for g in games if g["pct"] > 0), key=lambda g: (-g["pct"], -g["ratings"]))[:LIST_SIZE]
    cutoff = today - dt.timedelta(days=120)
    new = sorted((g for g in games if g["release"] and cutoff <= g["release"] <= today), key=lambda g: g["release"], reverse=True)[:LIST_SIZE]
    for name, rows in (("top", top), ("deals", deals), ("new", new)):
        write_if_changed(os.path.join(lists_dir, f"{name}.json"), [row(g) for g in rows])

    if not args.max_ids:
        all_skips = new_skips if recheck else (skip_ids | new_skips)
        write_if_changed(os.path.join(args.data_dir, "skip.json"),
                         {"refreshed": today.isoformat() if recheck else skip["refreshed"], "ids": sorted(all_skips)})

    if failed_batches > len(batches) * 0.5:
        print(f"ERROR: {failed_batches}/{len(batches)} batches failed; not updating meta.json", file=sys.stderr)
        sys.exit(1)

    write_if_changed(os.path.join(args.data_dir, "meta.json"), {
        "updated": today.isoformat(),
        "games": len(games),
        "deals": sum(1 for g in games if g["pct"] > 0),
        "failedBatches": failed_batches,
    })
    print(f"done: {len(games)} priced games, {changed} history files changed, {len(new_skips)} non-games, {failed_batches} failed batches")

    if args.backfill_minutes > 0:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import archive  # noqa: E402
        ranked = [g["id"] for g in sorted(games, key=lambda g: -g["ratings"])][: args.backfill_top]
        archive.backfill(args.data_dir, ranked, pages, args.backfill_minutes)


if __name__ == "__main__":
    main()
