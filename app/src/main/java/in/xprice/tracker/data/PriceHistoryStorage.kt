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
    private val prefs = context.getSharedPreferences("xprice_india_ledger", Context.MODE_PRIVATE)

    // 1. Track and update All-Time Lowest INR Price ever seen on the device
    fun recordAndGetLowestPrice(productId: String, currentInr: Int, baselineLowestInr: Int): Int {
        val key = "lowest_$productId"
        val savedLowest = prefs.getInt(key, baselineLowestInr)
        val trueLowest = minOf(savedLowest, currentInr, baselineLowestInr)
        if (trueLowest < savedLowest || !prefs.contains(key)) {
            prefs.edit().putInt(key, trueLowest).apply()
        }
        return trueLowest
    }

    // 2. Append live price point to persistent local history
    fun recordAndGetHistory(productId: String, defaultHistory: List<PricePoint>, livePriceInr: Int): List<PricePoint> {
        val key = "history_$productId"
        val rawJson = prefs.getString(key, null)
        val points = mutableListOf<PricePoint>()

        if (rawJson != null) {
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                points.add(PricePoint(obj.getString("m"), obj.getInt("p")))
            }
        } else {
            points.addAll(defaultHistory)
        }

        // Update or append the latest "Live" node
        if (points.isNotEmpty() && points.last().month == "Live") {
            points[points.lastIndex] = PricePoint("Live", livePriceInr)
        } else {
            points.add(PricePoint("Live", livePriceInr))
        }

        val saveArr = JSONArray()
        points.takeLast(7).forEach { pt ->
            saveArr.put(JSONObject().apply {
                put("m", pt.month)
                put("p", pt.priceInr)
            })
        }
        prefs.edit().putString(key, saveArr.toString()).apply()
        return points.takeLast(7)
    }

    // 3. Save or Remove User Price Drop Alert
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

    fun getAlertForProduct(productId: String): TargetPriceAlert? {
        return getAllAlerts()[productId]
    }

    fun getAllAlerts(): Map<String, TargetPriceAlert> {
        val raw = prefs.getString("active_alerts", null) ?: return emptyMap()
        val map = mutableMapOf<String, TargetPriceAlert>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.getString("id")
            map[id] = TargetPriceAlert(
                productId = id,
                title = obj.getString("title"),
                targetPriceInr = obj.getInt("target")
            )
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
}
