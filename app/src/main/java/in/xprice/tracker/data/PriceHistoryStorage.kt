package `in`.xprice.tracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class TargetPriceAlert(
    val productId: String,
    val title: String,
    val targetPriceInr: Int
)

data class OwnedVaultEntry(
    val productId: String,
    val title: String,
    val paidInr: Int,
    val mrpInr: Int,
    val hoursPlayed: Double,
    val achievementsPct: Int
)

data class XboxPlayerProfile(
    val gamertag: String,
    val xuid: String,
    val gamerscore: Int,
    val accountTier: String,
    val isConnected: Boolean
)

class PriceHistoryStorage(context: Context) {
    private val prefs = context.getSharedPreferences("rupeevault_x_v6_real_ledger", Context.MODE_PRIVATE)

    // --- 1. Xbox Live Account Profile ---
    fun saveXboxProfile(gamertag: String, xuid: String, gamerscore: Int, tier: String) {
        prefs.edit()
            .putString("xbl_gamertag", gamertag)
            .putString("xbl_xuid", xuid)
            .putInt("xbl_gamerscore", gamerscore)
            .putString("xbl_tier", tier)
            .putBoolean("xbl_connected", gamertag.isNotBlank())
            .apply()
    }

    fun getXboxProfile(): XboxPlayerProfile {
        return XboxPlayerProfile(
            gamertag = prefs.getString("xbl_gamertag", "") ?: "",
            xuid = prefs.getString("xbl_xuid", "") ?: "",
            gamerscore = prefs.getInt("xbl_gamerscore", 0),
            accountTier = prefs.getString("xbl_tier", "Xbox Live Member") ?: "Xbox Live Member",
            isConnected = prefs.getBoolean("xbl_connected", false)
        )
    }

    fun disconnectXboxProfile() {
        prefs.edit().putBoolean("xbl_connected", false).apply()
    }

    // --- 2. Favourites ---
    fun isFavourite(productId: String): Boolean = getFavouriteIds().contains(productId)

    fun toggleFavourite(productId: String): Boolean {
        val current = getFavouriteIds().toMutableSet()
        val nowFav = if (current.contains(productId)) {
            current.remove(productId)
            false
        } else {
            current.add(productId)
            true
        }
        prefs.edit().putStringSet("favourite_ids", current).apply()
        return nowFav
    }

    fun getFavouriteIds(): Set<String> = prefs.getStringSet("favourite_ids", emptySet()) ?: emptySet()

    // --- 3. Owned Vault & Real Playtime Tracker ---
    fun markGameOwned(
        productId: String,
        title: String,
        paidInr: Int,
        mrpInr: Int,
        hoursPlayed: Double = 0.0,
        achievementsPct: Int = 0
    ) {
        val vault = getOwnedVault().toMutableMap()
        val existing = vault[productId]
        vault[productId] = OwnedVaultEntry(
            productId = productId,
            title = title,
            paidInr = paidInr.coerceAtLeast(0),
            mrpInr = maxOf(paidInr.coerceAtLeast(0), mrpInr.coerceAtLeast(0)),
            hoursPlayed = if (hoursPlayed > 0.0) hoursPlayed else (existing?.hoursPlayed ?: 0.0),
            achievementsPct = if (achievementsPct > 0) achievementsPct else (existing?.achievementsPct ?: 0)
        )
        saveOwnedVault(vault)
    }

    fun updatePlaytimeHours(productId: String, deltaHours: Double) {
        val vault = getOwnedVault().toMutableMap()
        val existing = vault[productId] ?: return
        val updatedHours = (existing.hoursPlayed + deltaHours).coerceAtLeast(0.0)
        vault[productId] = existing.copy(hoursPlayed = updatedHours)
        saveOwnedVault(vault)
    }

    fun removeGameFromOwned(productId: String) {
        val vault = getOwnedVault().toMutableMap()
        vault.remove(productId)
        saveOwnedVault(vault)
    }

    fun getOwnedEntry(productId: String): OwnedVaultEntry? = getOwnedVault()[productId]

    fun getOwnedVault(): Map<String, OwnedVaultEntry> {
        val raw = prefs.getString("owned_vault_entries", null) ?: return emptyMap()
        val map = mutableMapOf<String, OwnedVaultEntry>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            map[id] = OwnedVaultEntry(
                productId = id,
                title = o.getString("title"),
                paidInr = o.getInt("paid"),
                mrpInr = o.getInt("mrp"),
                hoursPlayed = o.optDouble("hours", 0.0),
                achievementsPct = o.optInt("ach", 0)
            )
        }
        return map
    }

    private fun saveOwnedVault(map: Map<String, OwnedVaultEntry>) {
        val arr = JSONArray()
        map.values.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.productId)
                put("title", e.title)
                put("paid", e.paidInr)
                put("mrp", e.mrpInr)
                put("hours", e.hoursPlayed)
                put("ach", e.achievementsPct)
            })
        }
        prefs.edit().putString("owned_vault_entries", arr.toString()).apply()
    }

    // --- 4. 100% Real Microsoft API Price History Ledger (Zero Fake Simulated Data) ---
    fun recordAndGetLowestPrice(productId: String, currentInr: Int, mrpInr: Int): Int {
        if (currentInr <= 0) return 0
        val key = "real_lowest_$productId"
        val savedLowest = prefs.getInt(key, currentInr)
        val trueLowest = if (savedLowest > 0) minOf(savedLowest, currentInr) else currentInr
        if (trueLowest != savedLowest || !prefs.contains(key)) {
            prefs.edit().putInt(key, trueLowest).apply()
        }
        return trueLowest
    }

    fun recordRealApiPriceSnapshot(productId: String, mrpInr: Int, livePriceInr: Int): List<PricePoint> {
        if (livePriceInr < 0) return emptyList()
        val key = "real_snapshots_$productId"
        val rawJson = prefs.getString(key, null)
        val points = mutableListOf<PricePoint>()

        if (rawJson != null) {
            try {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    points.add(PricePoint(obj.getString("label"), obj.getInt("price")))
                }
            } catch (_: Exception) {}
        }

        // Seed first point with official Microsoft Store MRP if this is the first time seeing the game
        if (points.isEmpty() && mrpInr > 0) {
            points.add(PricePoint("MSRP", mrpInr))
        }

        val todayLabel = try {
            LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM"))
        } catch (_: Exception) {
            "Live"
        }

        if (points.isNotEmpty() && points.last().label == todayLabel) {
            points[points.lastIndex] = PricePoint(todayLabel, livePriceInr)
        } else {
            points.add(PricePoint(todayLabel, livePriceInr))
        }

        val capped = points.takeLast(12)
        val saveArr = JSONArray()
        capped.forEach { pt ->
            saveArr.put(JSONObject().apply {
                put("label", pt.label)
                put("price", pt.priceInr)
            })
        }
        prefs.edit().putString(key, saveArr.toString()).apply()
        return capped
    }

    // --- 5. Offline Catalog Cache (With Timestamp for Fast Startup) ---
    fun isCacheFresh(cacheKey: String, maxAgeMinutes: Int = 30): Boolean {
        val lastSync = prefs.getLong("ts_$cacheKey", 0L)
        if (lastSync == 0L) return false
        val ageMs = System.currentTimeMillis() - lastSync
        return ageMs < (maxAgeMinutes * 60 * 1000L)
    }

    fun saveCachedDeals(deals: List<XboxGameDeal>, cacheKey: String) {
        val arr = JSONArray()
        deals.take(80).forEach { d ->
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
                put("criticScore", d.criticScore)
                put("releaseDate", d.releaseDate)
                put("isNewLaunch", d.isNewLaunch)
                put("isUnreleasedOrNoPrice", d.isUnreleasedOrNoPrice)
                put("isLeavingGamePassSoon", d.isLeavingGamePassSoon)
                put("saleCountdown", d.saleCountdownText)
                put("saleCadence", d.saleCadenceNote)
                put("editions", edArr)
                put("platforms", JSONArray(d.supportedPlatforms))
                put("newsHeadline", d.latestNewsHeadline)
                put("communitySummary", d.communityReviewSummary)
                put("coverUrl", d.coverImageUrl)
                put("screenshots", JSONArray(d.screenshotUrls))
                put("trailerUrl", d.trailerUrl)
                put("currentPriceInr", d.currentPriceInr)
                put("mrpInr", d.mrpInr)
                put("lowestEverInr", d.lowestEverInr)
                put("highestEverInr", d.highestEverInr)
                put("averagePriceInr", d.averagePriceInr)
                put("isGamePass", d.isGamePass)
                put("nextPredictedSale", d.nextPredictedSale)
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
            val list = mutableListOf<XboxGameDeal>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pid = o.getString("productId")
                val curr = o.getInt("currentPriceInr")
                val mrp = o.getInt("mrpInr")
                val low = o.getInt("lowestEverInr")
                val isUnpriced = o.optBoolean("isUnreleasedOrNoPrice", curr < 0)
                val shotsArr = o.optJSONArray("screenshots") ?: JSONArray()
                val shots = (0 until shotsArr.length()).map { shotsArr.getString(it) }
                val platArr = o.optJSONArray("platforms") ?: JSONArray()
                val plats = (0 until platArr.length()).map { platArr.getString(it) }

                val edArr = o.optJSONArray("editions") ?: JSONArray()
                val eds = (0 until edArr.length()).map { idx ->
                    val eo = edArr.getJSONObject(idx)
                    GameEditionOption(eo.getString("skuTitle"), eo.getInt("priceInr"), eo.getInt("mrpInr"), eo.getInt("discountPct"))
                }

                val discount = if (mrp > curr && mrp > 0 && curr >= 0) (((mrp - curr).toDouble() / mrp) * 100).toInt() else 0
                val isPass = o.optBoolean("isGamePass", false)
                val isLeaving = o.optBoolean("isLeavingGamePassSoon", false)
                val nextSale = o.optString("nextPredictedSale", "Microsoft Store India")

                val realSnapshots = recordRealApiPriceSnapshot(pid, mrp, curr)
                val rec = computeRecommendation(curr, mrp, low, discount, isPass, isLeaving, isUnpriced)

                list.add(
                    XboxGameDeal(
                        productId = pid,
                        title = o.getString("title"),
                        publisher = o.optString("publisher", "Xbox Publisher"),
                        description = o.optString("description", "Official Xbox India digital title."),
                        genre = o.optString("genre", "Action & Adventure"),
                        starRating = o.optDouble("starRating", 0.0),
                        ratingCount = o.optInt("ratingCount", 0),
                        criticScore = o.optInt("criticScore", 0),
                        releaseDate = o.optString("releaseDate", "TBA"),
                        isNewLaunch = o.optBoolean("isNewLaunch", false),
                        isUnreleasedOrNoPrice = isUnpriced,
                        isLeavingGamePassSoon = isLeaving,
                        saleCountdownText = o.optString("saleCountdown", ""),
                        saleCadenceNote = o.optString("saleCadence", "Official Microsoft Store IN Price"),
                        editions = eds,
                        supportedPlatforms = if (plats.isNotEmpty()) plats else listOf("Xbox Series X|S"),
                        latestNewsHeadline = o.optString("newsHeadline", ""),
                        communityReviewSummary = o.optString("communitySummary", ""),
                        coverImageUrl = o.optString("coverUrl", ""),
                        screenshotUrls = shots,
                        trailerUrl = o.optString("trailerUrl", ""),
                        currentPriceInr = curr,
                        mrpInr = mrp,
                        lowestEverInr = low,
                        highestEverInr = o.optInt("highestEverInr", mrp),
                        averagePriceInr = o.optInt("averagePriceInr", curr.coerceAtLeast(0)),
                        isAtAllTimeLow = curr > 0 && curr <= low,
                        isFavourite = isFavourite(pid),
                        ownedPaidInr = getOwnedEntry(pid)?.paidInr,
                        alertTargetInr = getAlertForProduct(pid)?.targetPriceInr,
                        discountPercent = discount,
                        isGamePass = isPass,
                        isLiveApiPrice = true,
                        monthHistory = realSnapshots.takeLast(4),
                        yearHistory = realSnapshots.takeLast(8),
                        allTimeHistory = realSnapshots,
                        nextPredictedSale = nextSale,
                        predictedPriceInr = low,
                        recommendationEmoji = rec.first,
                        dealRating = rec.second,
                        storeUrl = o.optString("storeUrl", "https://www.xbox.com/en-in/games/store/_/$pid")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setPriceAlert(productId: String, title: String, targetInr: Int) {
        val alerts = getAllAlerts().toMutableMap()
        alerts[productId] = TargetPriceAlert(productId, title, targetInr)
        saveAlertsMap(alerts)
    }

    fun removePriceAlert(productId: String) {
        val alerts = getAllAlerts().toMutableMap()
        alerts.remove(productId)
        saveAlertsMap(alerts)
    }

    fun getAlertForProduct(productId: String): TargetPriceAlert? = getAllAlerts()[productId]

    fun getAllAlerts(): Map<String, TargetPriceAlert> {
        val raw = prefs.getString("active_alerts", null) ?: return emptyMap()
        val map = mutableMapOf<String, TargetPriceAlert>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.getString("id")
            map[id] = TargetPriceAlert(id, obj.getString("title"), obj.getInt("target"))
        }
        return map
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

    fun exportWatchlistAsCsv(allKnownGames: List<XboxGameDeal>): String {
        val sb = StringBuilder()
        sb.appendLine("ProductId,Title,Genre,CurrentPriceINR,MRPINR,RecordedLowestINR,DiscountPct,IsFavourite,OwnedPaidINR,HoursPlayed,AlertTargetINR")
        val favs = getFavouriteIds()
        val alerts = getAllAlerts()
        val owned = getOwnedVault()

        allKnownGames.filter {
            favs.contains(it.productId) || alerts.containsKey(it.productId) || owned.containsKey(it.productId)
        }.forEach { g ->
            val safeTitle = g.title.replace(",", " ")
            val ownEntry = owned[g.productId]
            sb.appendLine(
                "${g.productId},$safeTitle,${g.genre},${g.currentPriceInr},${g.mrpInr},${g.lowestEverInr},${g.discountPercent}%,${favs.contains(g.productId)},${ownEntry?.paidInr ?: ""},${ownEntry?.hoursPlayed ?: ""},${alerts[g.productId]?.targetPriceInr ?: ""}"
            )
        }
        return sb.toString()
    }

    fun exportWatchlistAsJson(): String {
        val root = JSONObject()
        root.put("app", "RupeeVault X v6.0")
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
                put("paidInr", o.paidInr)
                put("mrpInr", o.mrpInr)
                put("hoursPlayed", o.hoursPlayed)
                put("achievementsPct", o.achievementsPct)
            })
        }
        root.put("ownedVault", ownedArr)
        return root.toString(2)
    }

    companion object {
        fun computeRecommendation(
            curr: Int,
            mrp: Int,
            low: Int,
            discountPct: Int,
            isGamePass: Boolean,
            isLeavingSoon: Boolean,
            isUnreleasedOrNoPrice: Boolean
        ): Pair<String, String> {
            return when {
                isUnreleasedOrNoPrice || curr < 0 -> "📅" to "COMING SOON — Unreleased / Pre-Order Not Priced on MS Store IN"
                curr == 0 -> "🆓" to "FREE TO PLAY — Official Free Game on Microsoft Store India"
                isLeavingSoon -> "⚠️" to "LEAVING GAME PASS SOON — 20% member purchase discount active"
                discountPct > 0 && curr <= low -> "🔥" to "BUY NOW — Lowest Recorded MS Store IN Price (₹$curr, -$discountPct%)"
                discountPct >= 35 -> "✅" to "ACTIVE MICROSOFT STORE SALE — Save ₹${mrp - curr} (-$discountPct%)"
                discountPct > 0 -> "🏷️" to "ON SALE — Discounted by ₹${mrp - curr} (-$discountPct%)"
                isGamePass -> "🎮" to "INCLUDED IN GAME PASS — Or buy permanently for ₹$curr"
                else -> "⏳" to "FULL MRP (₹$mrp) — Set a Price Alert to get notified on next Xbox sale"
            }
        }
    }
}
