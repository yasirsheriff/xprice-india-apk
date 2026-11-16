package `in`.xprice.tracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TargetPriceAlert(
    val productId: String,
    val title: String,
    val targetPriceInr: Int
)

data class OwnedVaultEntry(
    val productId: String,
    val title: String,
    /** What the user actually paid. null when unknown (e.g. imported from Xbox history). */
    val paidInr: Int?,
    val mrpInr: Int,
    /** Today's Microsoft Store India price, refreshed on each Xbox sync. */
    val currentInr: Int,
    val hoursPlayed: Double,
    val achievementsPct: Int,
    val imageUrl: String = "",
    val fromXbox: Boolean = false,
    val lastPlayed: String = ""
)

data class XboxPlayerProfile(
    val gamertag: String,
    val xuid: String,
    val gamerscore: Int,
    val avatarUrl: String,
    val isConnected: Boolean,
    val lastSyncMs: Long
)

/**
 * All on-device persistence. SharedPreferences file name is kept from earlier versions
 * so existing favourites, alerts and library survive the upgrade.
 */
class PriceHistoryStorage(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("rupeevault_x_v6_real_ledger", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------
    // Xbox profile
    // ---------------------------------------------------------------------
    fun saveXboxProfile(gamertag: String, xuid: String, gamerscore: Int, avatarUrl: String) {
        prefs.edit()
            .putString("xbl_gamertag", gamertag)
            .putString("xbl_xuid", xuid)
            .putInt("xbl_gamerscore", gamerscore)
            .putString("xbl_avatar", avatarUrl)
            .putBoolean("xbl_connected", gamertag.isNotBlank())
            .putLong("xbl_last_sync", System.currentTimeMillis())
            .apply()
    }

    fun getXboxProfile(): XboxPlayerProfile = XboxPlayerProfile(
        gamertag = prefs.getString("xbl_gamertag", "") ?: "",
        xuid = prefs.getString("xbl_xuid", "") ?: "",
        gamerscore = prefs.getInt("xbl_gamerscore", 0),
        avatarUrl = prefs.getString("xbl_avatar", "") ?: "",
        isConnected = prefs.getBoolean("xbl_connected", false),
        lastSyncMs = prefs.getLong("xbl_last_sync", 0L)
    )

    /** Signs out and removes games that were imported from Xbox (manual entries are kept). */
    fun disconnectXboxProfile() {
        prefs.edit()
            .remove("xbl_gamertag").remove("xbl_xuid").remove("xbl_gamerscore")
            .remove("xbl_avatar").remove("xbl_last_sync")
            .putBoolean("xbl_connected", false)
            .apply()
        saveOwnedVault(getOwnedVault().filterValues { !it.fromXbox })
    }

    // ---------------------------------------------------------------------
    // Favourites
    // ---------------------------------------------------------------------
    fun getFavouriteIds(): Set<String> = prefs.getStringSet("favourite_ids", emptySet()) ?: emptySet()

    fun toggleFavourite(productId: String): Boolean {
        val current = getFavouriteIds().toMutableSet()
        val nowFav = current.add(productId) || run { current.remove(productId); false }
        prefs.edit().putStringSet("favourite_ids", current).apply()
        return nowFav
    }

    // ---------------------------------------------------------------------
    // Owned library
    // ---------------------------------------------------------------------
    fun markGameOwned(productId: String, title: String, paidInr: Int, mrpInr: Int, currentInr: Int, imageUrl: String) {
        val vault = getOwnedVault().toMutableMap()
        val existing = vault[productId]
        vault[productId] = OwnedVaultEntry(
            productId = productId,
            title = title,
            paidInr = paidInr.coerceAtLeast(0),
            mrpInr = maxOf(mrpInr, paidInr, 0),
            currentInr = currentInr.coerceAtLeast(0),
            hoursPlayed = existing?.hoursPlayed ?: 0.0,
            achievementsPct = existing?.achievementsPct ?: 0,
            imageUrl = imageUrl.ifBlank { existing?.imageUrl ?: "" },
            fromXbox = existing?.fromXbox ?: false,
            lastPlayed = existing?.lastPlayed ?: ""
        )
        saveOwnedVault(vault)
    }

    /** Merges titles from Xbox Live, keeping any price the user entered manually. */
    fun mergeXboxLibrary(entries: List<OwnedVaultEntry>) {
        val vault = getOwnedVault().toMutableMap()
        entries.forEach { incoming ->
            val existing = vault[incoming.productId]
            vault[incoming.productId] = incoming.copy(
                paidInr = existing?.paidInr ?: incoming.paidInr,
                mrpInr = if (incoming.mrpInr > 0) incoming.mrpInr else existing?.mrpInr ?: 0
            )
        }
        saveOwnedVault(vault)
    }

    fun removeGameFromOwned(productId: String) {
        val vault = getOwnedVault().toMutableMap()
        vault.remove(productId)
        saveOwnedVault(vault)
    }

    fun getOwnedVault(): Map<String, OwnedVaultEntry> {
        val raw = prefs.getString("owned_vault_entries", null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val paid = if (o.has("paid") && !o.isNull("paid")) o.getInt("paid") else null
                id to OwnedVaultEntry(
                    productId = id,
                    title = o.optString("title"),
                    // Older versions stored the live price as "paid" for Xbox imports.
                    paidInr = if (o.optBoolean("xbox", false) && !o.has("cur")) null else paid,
                    mrpInr = o.optInt("mrp", 0),
                    currentInr = o.optInt("cur", paid ?: 0),
                    hoursPlayed = o.optDouble("hours", 0.0),
                    achievementsPct = o.optInt("ach", 0),
                    imageUrl = o.optString("img", ""),
                    fromXbox = o.optBoolean("xbox", false),
                    lastPlayed = o.optString("last", "")
                )
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveOwnedVault(map: Map<String, OwnedVaultEntry>) {
        val arr = JSONArray()
        map.values.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.productId)
                put("title", e.title)
                if (e.paidInr != null) put("paid", e.paidInr)
                put("mrp", e.mrpInr)
                put("cur", e.currentInr)
                put("hours", e.hoursPlayed)
                put("ach", e.achievementsPct)
                put("img", e.imageUrl)
                put("xbox", e.fromXbox)
                put("last", e.lastPlayed)
            })
        }
        prefs.edit().putString("owned_vault_entries", arr.toString()).apply()
    }

    // ---------------------------------------------------------------------
    // Price history
    //
    // Stored as change points: each price "run" keeps its first and last day seen,
    // so a game that sat at ₹3,499 for 200 days costs two entries, not 200.
    // ---------------------------------------------------------------------
    private fun historyKey(productId: String) = "price_hist_v2_$productId"

    fun getHistory(productId: String): List<PricePoint> {
        val raw = prefs.getString(historyKey(productId), null)
        if (raw == null) return migrateLegacyHistory(productId)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val pair = arr.getJSONArray(i)
                PricePoint(pair.getLong(0), pair.getInt(1))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(productId: String, points: List<PricePoint>) {
        val arr = JSONArray()
        points.takeLast(MAX_HISTORY_POINTS).forEach { arr.put(JSONArray().put(it.epochDay).put(it.priceInr)) }
        prefs.edit().putString(historyKey(productId), arr.toString()).apply()
    }

    /**
     * Records a LIVE price observation (only call with fresh Microsoft Store data,
     * never with cached data, or stale prices get written as today's).
     */
    fun recordPrice(productId: String, priceInr: Int, today: Long = LocalDate.now().toEpochDay()): List<PricePoint> {
        val points = getHistory(productId).toMutableList()
        if (priceInr <= 0) return points

        val last = points.lastOrNull()
        when {
            last == null -> points.add(PricePoint(today, priceInr))
            last.epochDay > today -> return points // device clock went backwards, ignore
            last.epochDay == today -> {
                points[points.lastIndex] = PricePoint(today, priceInr)
                // Collapse if the replacement now equals the previous run.
                val prev = points.getOrNull(points.lastIndex - 1)
                val prevPrev = points.getOrNull(points.lastIndex - 2)
                if (prev != null && prevPrev != null && prev.priceInr == priceInr && prevPrev.priceInr == priceInr) {
                    points.removeAt(points.lastIndex - 1)
                }
            }
            last.priceInr == priceInr -> {
                val prev = points.getOrNull(points.lastIndex - 1)
                if (prev != null && prev.priceInr == priceInr) {
                    // Extend the existing run to today.
                    points[points.lastIndex] = PricePoint(today, priceInr)
                } else {
                    points.add(PricePoint(today, priceInr))
                }
            }
            else -> points.add(PricePoint(today, priceInr))
        }
        saveHistory(productId, points)
        return points
    }

    /**
     * Merges dated points from another source (shared tracker, Microsoft best-price windows)
     * into local history. On the same day, local observations win.
     */
    fun mergePoints(productId: String, extra: List<PricePoint>) {
        if (extra.isEmpty()) return
        val merged = normalizeRuns(extra.filter { it.priceInr > 0 } + getHistory(productId))
        saveHistory(productId, merged)
    }

    // --- Shared history sync bookkeeping ---
    fun remoteHistoryFetchedAt(productId: String): Long = prefs.getLong("remote_hist_ts_$productId", 0L)
    fun markRemoteHistoryFetched(productId: String) {
        prefs.edit().putLong("remote_hist_ts_$productId", System.currentTimeMillis()).apply()
    }

    /** Converts the old "dd MMM" label snapshots (no year) into dated points, once. */
    private fun migrateLegacyHistory(productId: String): List<PricePoint> {
        val legacyRaw = prefs.getString("real_snapshots_$productId", null) ?: return emptyList()
        val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
        val today = LocalDate.now()
        val migrated = mutableListOf<PricePoint>()
        try {
            val arr = JSONArray(legacyRaw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val label = obj.optString("label")
                val price = obj.optInt("price", -1)
                if (price <= 0 || label == "MSRP" || label == "Live") continue
                var date = try { LocalDate.parse("$label ${today.year}", fmt) } catch (_: Exception) { null } ?: continue
                if (date.isAfter(today)) date = date.minusYears(1)
                migrated.add(PricePoint(date.toEpochDay(), price))
            }
        } catch (_: Exception) {
        }
        val sorted = migrated.sortedBy { it.epochDay }.distinctBy { it.epochDay }
        prefs.edit().remove("real_snapshots_$productId").apply()
        if (sorted.isNotEmpty()) saveHistory(productId, sorted)
        return sorted
    }

    private fun legacyLowest(productId: String): Int = prefs.getInt("real_lowest_$productId", 0)

    // ---------------------------------------------------------------------
    // Enrichment: attach user state + tracked history to store data
    // ---------------------------------------------------------------------
    fun enrich(deals: List<XboxGameDeal>): List<XboxGameDeal> {
        if (deals.isEmpty()) return deals
        val favs = getFavouriteIds()
        val alerts = getAllAlerts()
        val owned = getOwnedVault()
        return deals.map { d ->
            val history = getHistory(d.productId)
            val tracked = history.map { it.priceInr }.filter { it > 0 }
            val legacyLow = legacyLowest(d.productId).takeIf { it > 0 }
            val lowest = listOfNotNull(tracked.minOrNull(), legacyLow, d.currentPriceInr.takeIf { it > 0 }).minOrNull() ?: 0
            val highest = maxOf(d.mrpInr, tracked.maxOrNull() ?: 0, d.currentPriceInr)
            val atLow = d.currentPriceInr > 0 && d.discountPercent > 0 && d.currentPriceInr <= lowest
            d.copy(
                isFavourite = favs.contains(d.productId),
                ownedPaidInr = owned[d.productId]?.let { it.paidInr ?: it.currentInr },
                alertTargetInr = alerts[d.productId]?.targetPriceInr,
                history = history,
                lowestEverInr = lowest,
                highestEverInr = highest,
                isAtAllTimeLow = atLow,
                verdict = computeVerdict(d, atLow)
            )
        }
    }

    // ---------------------------------------------------------------------
    // Catalog cache (fast startup / offline)
    // ---------------------------------------------------------------------
    fun isCacheFresh(cacheKey: String, maxAgeMinutes: Int = 30): Boolean {
        val lastSync = prefs.getLong("ts_$cacheKey", 0L)
        return lastSync != 0L && System.currentTimeMillis() - lastSync < maxAgeMinutes * 60_000L
    }

    fun cacheTimestamp(cacheKey: String): Long = prefs.getLong("ts_$cacheKey", 0L)

    fun saveCachedDeals(deals: List<XboxGameDeal>, cacheKey: String) {
        val arr = JSONArray()
        deals.take(120).forEach { d ->
            val edArr = JSONArray()
            d.editions.forEach { ed ->
                edArr.put(JSONObject().apply {
                    put("skuTitle", ed.skuTitle)
                    put("priceInr", ed.priceInr)
                    put("mrpInr", ed.mrpInr)
                    put("discountPct", ed.discountPercent)
                })
            }
            arr.put(JSONObject().apply {
                put("productId", d.productId)
                put("title", d.title)
                put("publisher", d.publisher)
                put("description", d.description)
                put("genre", d.genre)
                put("starRating", d.starRating)
                put("ratingCount", d.ratingCount)
                put("releaseDate", d.releaseDate)
                put("isNewLaunch", d.isNewLaunch)
                put("isUnreleasedOrNoPrice", d.isUnreleasedOrNoPrice)
                put("isLeavingGamePassSoon", d.isLeavingGamePassSoon)
                put("saleEndsAtMs", d.saleEndsAtMs)
                put("editions", edArr)
                put("platforms", JSONArray(d.supportedPlatforms))
                put("coverUrl", d.coverImageUrl)
                put("screenshots", JSONArray(d.screenshotUrls))
                put("trailerUrl", d.trailerUrl)
                put("currentPriceInr", d.currentPriceInr)
                put("mrpInr", d.mrpInr)
                put("discountPct", d.discountPercent)
                put("isGamePass", d.isGamePass)
                put("storeUrl", d.storeUrl)
            })
        }
        prefs.edit()
            .putString(cacheKey, arr.toString())
            .putLong("ts_$cacheKey", System.currentTimeMillis())
            .apply()
    }

    fun loadCachedDeals(cacheKey: String): List<XboxGameDeal> {
        val raw = prefs.getString(cacheKey, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pid = o.getString("productId")
                val curr = o.getInt("currentPriceInr")
                val mrp = o.optInt("mrpInr", 0)
                val discount = o.optInt(
                    "discountPct",
                    if (mrp > curr && curr > 0) (((mrp - curr).toDouble() / mrp) * 100).toInt() else 0
                )
                val shotsArr = o.optJSONArray("screenshots") ?: JSONArray()
                val platArr = o.optJSONArray("platforms") ?: JSONArray()
                val edArr = o.optJSONArray("editions") ?: JSONArray()
                XboxGameDeal(
                    productId = pid,
                    title = o.getString("title"),
                    publisher = o.optString("publisher", ""),
                    description = o.optString("description", ""),
                    genre = o.optString("genre", "Action & Adventure"),
                    starRating = o.optDouble("starRating", 0.0),
                    ratingCount = o.optInt("ratingCount", 0),
                    releaseDate = o.optString("releaseDate", "TBA"),
                    isNewLaunch = o.optBoolean("isNewLaunch", false),
                    isUnreleasedOrNoPrice = o.optBoolean("isUnreleasedOrNoPrice", curr < 0),
                    isLeavingGamePassSoon = o.optBoolean("isLeavingGamePassSoon", false),
                    saleEndsAtMs = o.optLong("saleEndsAtMs", 0L),
                    editions = (0 until edArr.length()).map { idx ->
                        val eo = edArr.getJSONObject(idx)
                        GameEditionOption(eo.getString("skuTitle"), eo.getInt("priceInr"), eo.getInt("mrpInr"), eo.getInt("discountPct"))
                    },
                    supportedPlatforms = (0 until platArr.length()).map { platArr.getString(it) },
                    coverImageUrl = o.optString("coverUrl", ""),
                    screenshotUrls = (0 until shotsArr.length()).map { shotsArr.getString(it) },
                    trailerUrl = o.optString("trailerUrl", ""),
                    currentPriceInr = curr,
                    mrpInr = mrp,
                    discountPercent = discount,
                    isGamePass = o.optBoolean("isGamePass", false),
                    storeUrl = o.optString("storeUrl", "https://www.xbox.com/en-in/games/store/_/$pid")
                )
            }
            enrich(list)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------------
    // Price alerts
    // ---------------------------------------------------------------------
    fun setPriceAlert(productId: String, title: String, targetInr: Int) {
        val alerts = getAllAlerts().toMutableMap()
        alerts[productId] = TargetPriceAlert(productId, title, targetInr)
        saveAlertsMap(alerts)
        prefs.edit().remove("notified_$productId").apply()
    }

    fun removePriceAlert(productId: String) {
        val alerts = getAllAlerts().toMutableMap()
        alerts.remove(productId)
        saveAlertsMap(alerts)
    }

    fun getAllAlerts(): Map<String, TargetPriceAlert> {
        val raw = prefs.getString("active_alerts", null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                id to TargetPriceAlert(id, obj.getString("title"), obj.getInt("target"))
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveAlertsMap(map: Map<String, TargetPriceAlert>) {
        val arr = JSONArray()
        map.values.forEach { alert ->
            arr.put(JSONObject().apply {
                put("id", alert.productId)
                put("title", alert.title)
                put("target", alert.targetPriceInr)
            })
        }
        prefs.edit().putString("active_alerts", arr.toString()).apply()
    }

    /** Returns true if we haven't already notified for this exact price (avoids repeat pings every 12h). */
    fun shouldNotify(productId: String, priceInr: Int): Boolean {
        val last = prefs.getInt("notified_$productId", -1)
        if (last == priceInr) return false
        prefs.edit().putInt("notified_$productId", priceInr).apply()
        return true
    }

    /** Every product the user cares about; the background worker tracks these daily. */
    fun trackedProductIds(): Set<String> =
        getFavouriteIds() + getAllAlerts().keys + getOwnedVault().keys.filter { PRODUCT_ID_REGEX.matches(it) }

    // ---------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------
    fun exportWatchlistAsCsv(allKnownGames: List<XboxGameDeal>): String {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder()
        sb.appendLine("ProductId,Title,Genre,CurrentPriceINR,MRPINR,LowestTrackedINR,DiscountPct,Favourite,PaidINR,HoursPlayed,AlertTargetINR")
        val favs = getFavouriteIds()
        val alerts = getAllAlerts()
        val owned = getOwnedVault()
        val known = allKnownGames.associateBy { it.productId }
        val ids = (favs + alerts.keys + owned.keys)
        ids.forEach { id ->
            val g = known[id]
            val own = owned[id]
            val title = g?.title ?: own?.title ?: alerts[id]?.title ?: id
            sb.appendLine(
                listOf(
                    id, esc(title), esc(g?.genre ?: ""), "${g?.currentPriceInr ?: own?.currentInr ?: ""}",
                    "${g?.mrpInr ?: own?.mrpInr ?: ""}", "${g?.lowestEverInr ?: ""}", "${g?.discountPercent ?: ""}",
                    "${favs.contains(id)}", "${own?.paidInr ?: ""}", "${own?.hoursPlayed ?: ""}", "${alerts[id]?.targetPriceInr ?: ""}"
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    fun exportWatchlistAsJson(): String {
        val root = JSONObject()
        root.put("app", APP_NAME)
        root.put("favourites", JSONArray(getFavouriteIds().toList()))
        val alertsArr = JSONArray()
        getAllAlerts().values.forEach { a ->
            alertsArr.put(JSONObject().apply { put("id", a.productId); put("title", a.title); put("targetInr", a.targetPriceInr) })
        }
        root.put("priceAlerts", alertsArr)
        val ownedArr = JSONArray()
        getOwnedVault().values.forEach { o ->
            ownedArr.put(JSONObject().apply {
                put("id", o.productId)
                put("title", o.title)
                put("paidInr", o.paidInr ?: JSONObject.NULL)
                put("mrpInr", o.mrpInr)
                put("hoursPlayed", o.hoursPlayed)
                put("achievementsPct", o.achievementsPct)
            })
        }
        root.put("library", ownedArr)
        val histObj = JSONObject()
        trackedProductIds().forEach { id ->
            val h = getHistory(id)
            if (h.isNotEmpty()) {
                histObj.put(id, JSONArray().apply { h.forEach { put(JSONArray().put(it.date.toString()).put(it.priceInr)) } })
            }
        }
        root.put("priceHistory", histObj)
        return root.toString(2)
    }

    companion object {
        const val APP_NAME = "Lootwise"
        private const val MAX_HISTORY_POINTS = 400
        val PRODUCT_ID_REGEX = Regex("^[9BC][A-Z0-9]{11}$")

        /**
         * Sorts by day (later entries win on the same day) and keeps only the first and last
         * day of each run of equal prices.
         */
        fun normalizeRuns(points: List<PricePoint>): List<PricePoint> {
            val byDay = LinkedHashMap<Long, Int>()
            points.forEach { byDay[it.epochDay] = it.priceInr }
            val sorted = byDay.entries.sortedBy { it.key }.map { PricePoint(it.key, it.value) }
            return sorted.filterIndexed { i, p ->
                i == 0 || i == sorted.lastIndex ||
                    sorted[i - 1].priceInr != p.priceInr || sorted[i + 1].priceInr != p.priceInr
            }
        }

        fun computeVerdict(d: XboxGameDeal, atLow: Boolean): DealVerdict = when {
            d.isUnreleasedOrNoPrice || d.currentPriceInr < 0 -> DealVerdict.COMING_SOON
            d.currentPriceInr == 0 -> DealVerdict.FREE
            d.isLeavingGamePassSoon -> DealVerdict.LEAVING_SOON
            atLow -> DealVerdict.ALL_TIME_LOW
            d.discountPercent >= 35 -> DealVerdict.BIG_SALE
            d.discountPercent > 0 -> DealVerdict.ON_SALE
            d.isGamePass -> DealVerdict.GAME_PASS
            else -> DealVerdict.FULL_PRICE
        }
    }
}
