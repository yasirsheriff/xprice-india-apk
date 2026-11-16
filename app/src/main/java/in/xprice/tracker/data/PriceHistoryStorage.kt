package `in`.xprice.tracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TargetPriceAlert(
    val productId: String,
    val title: String,
    val targetPriceInr: Int
)

class PriceHistoryStorage(context: Context) {
    private val prefs = context.getSharedPreferences("xprice_india_v2_ledger", Context.MODE_PRIVATE)

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

    fun saveCachedDeals(deals: List<XboxGameDeal>, cacheKey: String = "offline_catalog_cache") {
        val arr = JSONArray()
        deals.take(60).forEach { d ->
            arr.put(JSONObject().apply {
                put("productId", d.productId)
                put("title", d.title)
                put("publisher", d.publisher)
                put("genre", d.genre)
                put("starRating", d.starRating)
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

    fun loadCachedDeals(cacheKey: String = "offline_catalog_cache"): List<XboxGameDeal> {
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
                val discount = if (mrp > curr && mrp > 0) (((mrp - curr).toDouble() / mrp) * 100).toInt() else 0
                val isPass = o.optBoolean("isGamePass", false)
                val nextSale = o.optString("nextPredictedSale", "Upcoming Xbox Sale")

                val mHist = getTimeframeHistory(pid, "1M", buildDefaultMonthHistory(mrp, curr), curr)
                val yHist = getTimeframeHistory(pid, "1Y", buildDefaultYearHistory(mrp, low, curr), curr)
                val aHist = getTimeframeHistory(pid, "ALL", buildDefaultAllTimeHistory(mrp, low, curr), curr)
                val rec = computeRecommendation(curr, mrp, low, discount, isPass, nextSale)

                list.add(
                    XboxGameDeal(
                        productId = pid,
                        title = o.getString("title"),
                        publisher = o.optString("publisher", "Xbox Publisher"),
                        genre = o.optString("genre", "Action & Adventure"),
                        starRating = o.optDouble("starRating", 4.5),
                        coverImageUrl = o.optString("coverUrl", ""),
                        screenshotUrls = shots,
                        trailerUrl = o.optString("trailerUrl", ""),
                        currentPriceInr = curr,
                        mrpInr = mrp,
                        lowestEverInr = low,
                        highestEverInr = o.optInt("highestEverInr", mrp),
                        averagePriceInr = o.optInt("averagePriceInr", curr),
                        isAtAllTimeLow = curr <= low,
                        isFavourite = isFavourite(pid),
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
            nextSale: String
        ): Pair<String, String> {
            return when {
                curr <= low -> "🔥" to "BUY NOW — Record All-Time Low (₹$low)!"
                isGamePass && discountPct < 40 -> "🎮" to "PLAY ON GAME PASS — Included in Sub (Buy at ₹$low)"
                curr <= (low * 1.15).toInt() -> "✅" to "GREAT DEAL — Within 15% of Lowest Price"
                discountPct >= 30 -> "👍" to "GOOD OFFER — Save ₹${mrp - curr} today"
                else -> "⏳" to "WAIT FOR SALE — Expected drop to ₹$low ($nextSale)"
            }
        }
    }
}
