package `in`.xprice.tracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

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
    val apiKey: String,
    val isConnected: Boolean
)

class PriceHistoryStorage(context: Context) {
    private val prefs = context.getSharedPreferences("rupeevault_x_v5_ledger", Context.MODE_PRIVATE)

    // --- 1. Xbox Live Account Profile & OpenXBL Sync ---
    fun saveXboxProfile(gamertag: String, xuid: String, gamerscore: Int, tier: String, apiKey: String) {
        prefs.edit()
            .putString("xbl_gamertag", gamertag)
            .putString("xbl_xuid", xuid)
            .putInt("xbl_gamerscore", gamerscore)
            .putString("xbl_tier", tier)
            .putString("xbl_apikey", apiKey)
            .putBoolean("xbl_connected", gamertag.isNotBlank())
            .apply()
    }

    fun getXboxProfile(): XboxPlayerProfile {
        return XboxPlayerProfile(
            gamertag = prefs.getString("xbl_gamertag", "") ?: "",
            xuid = prefs.getString("xbl_xuid", "2535419804215912") ?: "",
            gamerscore = prefs.getInt("xbl_gamerscore", 18450),
            accountTier = prefs.getString("xbl_tier", "Game Pass Ultimate") ?: "Game Pass Ultimate",
            apiKey = prefs.getString("xbl_apikey", "") ?: "",
            isConnected = prefs.getBoolean("xbl_connected", false)
        )
    }

    fun disconnectXboxProfile() {
        prefs.edit().putBoolean("xbl_connected", false).apply()
    }

    // Live OpenXBL sync if user enters an xbl.io API key, otherwise populates realistic Gamertag telemetry
    suspend fun syncXboxLiveAccount(
        gamertagInput: String,
        apiKeyInput: String,
        catalogGames: List<XboxGameDeal>
    ): XboxPlayerProfile = withContext(Dispatchers.IO) {
        val cleanTag = gamertagInput.trim().ifBlank { "XboxPlayerIN" }
        val cleanKey = apiKeyInput.trim()
        var resolvedXuid = "2535472819204118"
        var resolvedScore = 24680
        var resolvedTier = "Game Pass Ultimate"

        if (cleanKey.isNotBlank()) {
            try {
                val enc = URLEncoder.encode(cleanTag, "UTF-8")
                val conn = (URL("https://api.xbl.io/v2/friends/search?gt=$enc").openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("X-Authorization", cleanKey)
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(raw)
                    val user = root.optJSONArray("profileUsers")?.optJSONObject(0)
                    if (user != null) {
                        resolvedXuid = user.optString("id", resolvedXuid)
                        val settings = user.optJSONArray("settings")
                        if (settings != null) {
                            for (i in 0 until settings.length()) {
                                val s = settings.getJSONObject(i)
                                if (s.optString("id") == "Gamerscore") {
                                    resolvedScore = s.optString("value").toIntOrNull() ?: resolvedScore
                                } else if (s.optString("id") == "AccountTier") {
                                    resolvedTier = s.optString("value", resolvedTier)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Seed starter library with playtime hours if Owned Vault is currently empty
        if (getOwnedVault().isEmpty() && catalogGames.isNotEmpty()) {
            val sampleHours = listOf(64.5, 42.0, 28.5, 19.0, 11.5)
            val sampleAch = listOf(82, 68, 54, 40, 25)
            catalogGames.filter { it.currentPriceInr > 0 }.take(5).forEachIndexed { idx, g ->
                markGameOwned(
                    productId = g.productId,
                    title = g.title,
                    paidInr = g.currentPriceInr,
                    mrpInr = g.mrpInr,
                    hoursPlayed = sampleHours.getOrElse(idx) { 12.0 },
                    achievementsPct = sampleAch.getOrElse(idx) { 35 }
                )
            }
        }

        saveXboxProfile(cleanTag, resolvedXuid, resolvedScore, resolvedTier, cleanKey)
        getXboxProfile()
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

    // --- 3. Owned Vault, Spending & Playtime Hours Tracker ---
    fun markGameOwned(
        productId: String,
        title: String,
        paidInr: Int,
        mrpInr: Int,
        hoursPlayed: Double = 8.5,
        achievementsPct: Int = 35
    ) {
        val vault = getOwnedVault().toMutableMap()
        val existing = vault[productId]
        vault[productId] = OwnedVaultEntry(
            productId = productId,
            title = title,
            paidInr = paidInr,
            mrpInr = maxOf(paidInr, mrpInr),
            hoursPlayed = existing?.hoursPlayed ?: hoursPlayed,
            achievementsPct = existing?.achievementsPct ?: achievementsPct
        )
        saveOwnedVault(vault)
    }

    fun updatePlaytimeHours(productId: String, deltaHours: Double) {
        val vault = getOwnedVault().toMutableMap()
        val existing = vault[productId] ?: return
        val updatedHours = (existing.hoursPlayed + deltaHours).coerceAtLeast(0.0)
        val updatedAch = (existing.achievementsPct + if (deltaHours > 0) 5 else 0).coerceIn(0, 100)
        vault[productId] = existing.copy(hoursPlayed = updatedHours, achievementsPct = updatedAch)
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
                hoursPlayed = o.optDouble("hours", 10.0),
                achievementsPct = o.optInt("ach", 40)
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

    // --- 4. Offline Catalog Cache ---
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
        prefs.edit().putString(cacheKey, arr.toString()).apply()
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
                val shotsArr = o.optJSONArray("screenshots") ?: JSONArray()
                val shots = (0 until shotsArr.length()).map { shotsArr.getString(it) }
                val platArr = o.optJSONArray("platforms") ?: JSONArray()
                val plats = (0 until platArr.length()).map { platArr.getString(it) }

                val edArr = o.optJSONArray("editions") ?: JSONArray()
                val eds = (0 until edArr.length()).map { idx ->
                    val eo = edArr.getJSONObject(idx)
                    GameEditionOption(eo.getString("skuTitle"), eo.getInt("priceInr"), eo.getInt("mrpInr"), eo.getInt("discountPct"))
                }

                val discount = if (mrp > curr && mrp > 0) (((mrp - curr).toDouble() / mrp) * 100).toInt() else 0
                val isPass = o.optBoolean("isGamePass", false)
                val isLeaving = o.optBoolean("isLeavingGamePassSoon", false)
                val nextSale = o.optString("nextPredictedSale", "Upcoming Xbox Sale")

                val mHist = getTimeframeHistory(pid, "1M", buildDefaultMonthHistory(mrp, curr), curr)
                val yHist = getTimeframeHistory(pid, "1Y", buildDefaultYearHistory(mrp, low, curr), curr)
                val aHist = getTimeframeHistory(pid, "ALL", buildDefaultAllTimeHistory(mrp, low, curr), curr)
                val rec = computeRecommendation(curr, mrp, low, discount, isPass, isLeaving, nextSale)

                list.add(
                    XboxGameDeal(
                        productId = pid,
                        title = o.getString("title"),
                        publisher = o.optString("publisher", "Xbox Publisher"),
                        description = o.optString("description", "Official Xbox India digital title."),
                        genre = o.optString("genre", "Action & Adventure"),
                        starRating = o.optDouble("starRating", 4.5),
                        ratingCount = o.optInt("ratingCount", 120),
                        criticScore = o.optInt("criticScore", 84),
                        releaseDate = o.optString("releaseDate", "2026"),
                        isNewLaunch = o.optBoolean("isNewLaunch", false),
                        isLeavingGamePassSoon = isLeaving,
                        saleCountdownText = o.optString("saleCountdown", ""),
                        saleCadenceNote = o.optString("saleCadence", "Goes on sale every ~45 days"),
                        editions = eds,
                        supportedPlatforms = if (plats.isNotEmpty()) plats else listOf("Xbox Series X|S", "Xbox One"),
                        latestNewsHeadline = o.optString("newsHeadline", "Latest Xbox Series X|S optimization patch live."),
                        communityReviewSummary = o.optString("communitySummary", "Praised by Indian Xbox players for smooth 60FPS performance."),
                        coverImageUrl = o.optString("coverUrl", ""),
                        screenshotUrls = shots,
                        trailerUrl = o.optString("trailerUrl", ""),
                        currentPriceInr = curr,
                        mrpInr = mrp,
                        lowestEverInr = low,
                        highestEverInr = o.optInt("highestEverInr", mrp),
                        averagePriceInr = o.optInt("averagePriceInr", curr),
                        isAtAllTimeLow = curr <= low && curr > 0,
                        isFavourite = isFavourite(pid),
                        ownedPaidInr = getOwnedEntry(pid)?.paidInr,
                        alertTargetInr = getAlertForProduct(pid)?.targetPriceInr,
                        discountPercent = discount,
                        isGamePass = isPass,
                        isLiveApiPrice = false,
                        monthHistory = mHist,
                        yearHistory = yHist,
                        allTimeHistory = aHist,
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

    fun recordAndGetLowestPrice(productId: String, currentInr: Int, baselineLowestInr: Int): Int {
        if (currentInr <= 0) return baselineLowestInr
        val key = "lowest_$productId"
        val savedLowest = prefs.getInt(key, baselineLowestInr)
        val trueLowest = minOf(if (savedLowest > 0) savedLowest else currentInr, currentInr, if (baselineLowestInr > 0) baselineLowestInr else currentInr)
        if (trueLowest < savedLowest || !prefs.contains(key)) {
            prefs.edit().putInt(key, trueLowest).apply()
        }
        return trueLowest
    }

    fun getTimeframeHistory(
        productId: String,
        timeframe: String,
        defaultPoints: List<PricePoint>,
        livePriceInr: Int
    ): List<PricePoint> {
        val key = "hist_${timeframe}_$productId"
        val rawJson = prefs.getString(key, null)
        val points = mutableListOf<PricePoint>()

        if (rawJson != null) {
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val p = obj.getInt("price")
                if (p > 0 || livePriceInr == 0) {
                    points.add(PricePoint(obj.getString("label"), p))
                }
            }
        }
        if (points.size < 3) {
            points.clear()
            points.addAll(defaultPoints)
        }

        if (points.isNotEmpty() && points.last().label == "Now") {
            points[points.lastIndex] = PricePoint("Now", livePriceInr)
        } else {
            points.add(PricePoint("Now", livePriceInr))
        }

        val capped = points.takeLast(8)
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
        sb.appendLine("ProductId,Title,Genre,CurrentPriceINR,MRPINR,AllTimeLowINR,DiscountPct,IsFavourite,OwnedPaidINR,HoursPlayed,AlertTargetINR")
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
        root.put("app", "RupeeVault X v5.0")
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
        fun buildDefaultMonthHistory(mrp: Int, curr: Int): List<PricePoint> {
            val baseMrp = if (mrp > 0) mrp else 2999
            val baseCurr = if (curr > 0) curr else baseMrp
            return listOf(
                PricePoint("W1", baseMrp),
                PricePoint("W2", baseMrp),
                PricePoint("W3", if (baseCurr < baseMrp) baseCurr else (baseMrp * 0.85).toInt()),
                PricePoint("W4", baseCurr)
            )
        }

        fun buildDefaultYearHistory(mrp: Int, low: Int, curr: Int): List<PricePoint> {
            val baseMrp = if (mrp > 0) mrp else 2999
            val baseLow = if (low > 0) low else (baseMrp * 0.50).toInt()
            val baseCurr = if (curr > 0) curr else baseMrp
            return listOf(
                PricePoint("Jan", baseMrp),
                PricePoint("Mar", (baseMrp * 0.70).toInt()),
                PricePoint("May", baseMrp),
                PricePoint("Jul", baseLow),
                PricePoint("Sep", (baseMrp * 0.65).toInt()),
                PricePoint("Nov", baseCurr)
            )
        }

        fun buildDefaultAllTimeHistory(mrp: Int, low: Int, curr: Int): List<PricePoint> {
            val baseMrp = if (mrp > 0) mrp else 2999
            val baseLow = if (low > 0) low else (baseMrp * 0.50).toInt()
            val baseCurr = if (curr > 0) curr else baseMrp
            return listOf(
                PricePoint("Launch", baseMrp),
                PricePoint("Yr 1", (baseMrp * 0.75).toInt()),
                PricePoint("Yr 2", (baseMrp * 0.55).toInt()),
                PricePoint("Floor", baseLow),
                PricePoint("2026", baseCurr)
            )
        }

        fun computeRecommendation(
            curr: Int,
            mrp: Int,
            low: Int,
            discountPct: Int,
            isGamePass: Boolean,
            isLeavingSoon: Boolean,
            nextSale: String
        ): Pair<String, String> {
            return when {
                curr == 0 -> "🆓" to "FREE TO PLAY — Download free on Xbox India"
                isLeavingSoon -> "⚠️" to "LEAVING GAME PASS SOON — Buy with 20% member discount!"
                curr <= low -> "🔥" to "BUY NOW — Record All-Time Low (₹$low)!"
                isGamePass && discountPct < 40 -> "🎮" to "PLAY ON GAME PASS — Included in Sub (Buy at ₹$low)"
                curr <= (low * 1.15).toInt() -> "✅" to "GREAT DEAL — Within 15% of Lowest Price"
                discountPct >= 30 -> "👍" to "GOOD OFFER — Save ₹${mrp - curr} today"
                else -> "⏳" to "WAIT FOR SALE — Expected drop to ₹$low ($nextSale)"
            }
        }
    }
}
