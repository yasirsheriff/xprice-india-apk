package `in`.xprice.tracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TargetPriceAlert(
    val productId: String,
    val title: String,
    val targetPriceInr: Int
)

data class OwnedVaultEntry(
    val productId: String,
    val title: String,
    val paidInr: Int,
    val mrpInr: Int
)

class PriceHistoryStorage(context: Context) {
    private val prefs = context.getSharedPreferences("rupeevault_x_v4_ledger", Context.MODE_PRIVATE)

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

    fun markGameOwned(productId: String, title: String, paidInr: Int, mrpInr: Int) {
        val vault = getOwnedVault().toMutableMap()
        vault[productId] = OwnedVaultEntry(productId, title, paidInr, maxOf(paidInr, mrpInr))
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
            map[id] = OwnedVaultEntry(id, o.getString("title"), o.getInt("paid"), o.getInt("mrp"))
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
            })
        }
        prefs.edit().putString("owned_vault_entries", arr.toString()).apply()
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
        if (currentInr <= 0) return 0
        val key = "lowest_$productId"
        val savedLowest = prefs.getInt(key, baselineLowestInr)
        val trueLowest = minOf(savedLowest, currentInr, baselineLowestInr)
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
                points.add(PricePoint(obj.getString("label"), obj.getInt("price")))
            }
        } else {
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
        sb.appendLine("ProductId,Title,Genre,CurrentPriceINR,MRPINR,AllTimeLowINR,DiscountPct,IsFavourite,OwnedPaidINR,AlertTargetINR")
        val favs = getFavouriteIds()
        val alerts = getAllAlerts()
        val owned = getOwnedVault()

        allKnownGames.filter {
            favs.contains(it.productId) || alerts.containsKey(it.productId) || owned.containsKey(it.productId)
        }.forEach { g ->
            val safeTitle = g.title.replace(",", " ")
            sb.appendLine(
                "${g.productId},$safeTitle,${g.genre},${g.currentPriceInr},${g.mrpInr},${g.lowestEverInr},${g.discountPercent}%,${favs.contains(g.productId)},${owned[g.productId]?.paidInr ?: ""},${alerts[g.productId]?.targetPriceInr ?: ""}"
            )
        }
        return sb.toString()
    }

    fun exportWatchlistAsJson(): String {
        val root = JSONObject()
        root.put("app", "RupeeVault X")
        root.put("favourites", JSONArray(getFavouriteIds().toList()))
        val alertsArr = JSONArray()
        getAllAlerts().values.forEach { a ->
            alertsArr.put(JSONObject().apply { put("id", a.productId); put("title", a.title); put("targetInr", a.targetPriceInr) })
        }
        root.put("priceAlerts", alertsArr)
        val ownedArr = JSONArray()
        getOwnedVault().values.forEach { o ->
            ownedArr.put(JSONObject().apply { put("id", o.productId); put("title", o.title); put("paidInr", o.paidInr); put("mrpInr", o.mrpInr) })
        }
        root.put("ownedVault", ownedArr)
        return root.toString(2)
    }

    companion object {
        fun buildDefaultMonthHistory(mrp: Int, curr: Int): List<PricePoint> = listOf(
            PricePoint("W1", mrp),
            PricePoint("W2", mrp),
            PricePoint("W3", if (curr < mrp) curr else (mrp * 0.85).toInt()),
            PricePoint("W4", curr)
        )

        fun buildDefaultYearHistory(mrp: Int, low: Int, curr: Int): List<PricePoint> = listOf(
            PricePoint("Jan", mrp),
            PricePoint("Mar", (mrp * 0.70).toInt()),
            PricePoint("May", mrp),
            PricePoint("Jul", low),
            PricePoint("Sep", (mrp * 0.65).toInt()),
            PricePoint("Nov", curr)
        )

        fun buildDefaultAllTimeHistory(mrp: Int, low: Int, curr: Int): List<PricePoint> = listOf(
            PricePoint("Launch", mrp),
            PricePoint("Yr 1", (mrp * 0.75).toInt()),
            PricePoint("Yr 2", (mrp * 0.55).toInt()),
            PricePoint("Floor", low),
            PricePoint("2026", curr)
        )

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
                isLeavingSoon -> "⚠️" to "LEAVING GAME PASS SOON — Buy with 20% member discount before removal!"
                curr <= low -> "🔥" to "BUY NOW — Record All-Time Low (₹$low)!"
                isGamePass && discountPct < 40 -> "🎮" to "PLAY ON GAME PASS — Included in Sub (Buy at ₹$low)"
                curr <= (low * 1.15).toInt() -> "✅" to "GREAT DEAL — Within 15% of Lowest Price"
                discountPct >= 30 -> "👍" to "GOOD OFFER — Save ₹${mrp - curr} today"
                else -> "⏳" to "WAIT FOR SALE — Expected drop to ₹$low ($nextSale)"
            }
        }
    }
}
