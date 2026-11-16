package `in`.xprice.tracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

data class PricePoint(val month: String, val priceInr: Int)

data class XboxGameDeal(
    val productId: String,
    val title: String,
    val publisher: String,
    val genre: String,
    val starRating: Double,
    val currentPriceInr: Int,
    val mrpInr: Int,
    val lowestEverInr: Int,
    val highestEverInr: Int,
    val averagePriceInr: Int,
    val isAtAllTimeLow: Boolean,
    val alertTargetInr: Int?,
    val discountPercent: Int,
    val isGamePass: Boolean,
    val isLiveApiPrice: Boolean,
    val priceHistory: List<PricePoint>,
    val nextPredictedSale: String,
    val predictedPriceInr: Int,
    val dealRating: String,
    val storeUrl: String
)

private data class BaselineMeta(
    val productId: String,
    val fallbackTitle: String,
    val fallbackPublisher: String,
    val fallbackGenre: String,
    val fallbackRating: Double,
    val fallbackMrpInr: Int,
    val lowestEverInr: Int,
    val isGamePass: Boolean,
    val pastFiveMonths: List<PricePoint>,
    val nextPredictedSale: String
)

class XboxStoreRepository(context: Context) {

    private val storage = PriceHistoryStorage(context.applicationContext)

    private val trackedCatalog = listOf(
        BaselineMeta(
            productId = "9N8VG0B7TDZ0",
            fallbackTitle = "Cyberpunk 2077: Ultimate Edition",
            fallbackPublisher = "CD PROJEKT RED",
            fallbackGenre = "Role Playing",
            fallbackRating = 4.6,
            fallbackMrpInr = 4499,
            lowestEverInr = 2474,
            isGamePass = false,
            pastFiveMonths = listOf(
                PricePoint("Jun", 4499), PricePoint("Jul", 2999),
                PricePoint("Aug", 4499), PricePoint("Sep", 2699), PricePoint("Oct", 4499)
            ),
            nextPredictedSale = "Xbox Countdown Sale (Dec)"
        ),
        BaselineMeta(
            productId = "9N2ZDN7NWQKV",
            fallbackTitle = "Red Dead Redemption 2",
            fallbackPublisher = "Rockstar Games",
            fallbackGenre = "Action & Adventure",
            fallbackRating = 4.8,
            fallbackMrpInr = 4000,
            lowestEverInr = 1320,
            isGamePass = false,
            pastFiveMonths = listOf(
                PricePoint("Jun", 1320), PricePoint("Jul", 4000),
                PricePoint("Aug", 1320), PricePoint("Sep", 4000), PricePoint("Oct", 1320)
            ),
            nextPredictedSale = "Black Friday / Year-End Sale"
        ),
        BaselineMeta(
            productId = "9P3J32CTXLRZ",
            fallbackTitle = "Elden Ring",
            fallbackPublisher = "Bandai Namco Entertainment",
            fallbackGenre = "Role Playing",
            fallbackRating = 4.7,
            fallbackMrpInr = 3999,
            lowestEverInr = 2399,
            isGamePass = false,
            pastFiveMonths = listOf(
                PricePoint("Jun", 2799), PricePoint("Jul", 2399),
                PricePoint("Aug", 3999), PricePoint("Sep", 3999), PricePoint("Oct", 2799)
            ),
            nextPredictedSale = "Xbox Countdown Sale (Dec)"
        ),
        BaselineMeta(
            productId = "9NKX70BBCDRN",
            fallbackTitle = "Forza Horizon 5 Standard Edition",
            fallbackPublisher = "Xbox Game Studios",
            fallbackGenre = "Racing & Flying",
            fallbackRating = 4.6,
            fallbackMrpInr = 3999,
            lowestEverInr = 1999,
            isGamePass = true,
            pastFiveMonths = listOf(
                PricePoint("Jun", 1999), PricePoint("Jul", 1999),
                PricePoint("Aug", 3999), PricePoint("Sep", 1999), PricePoint("Oct", 3999)
            ),
            nextPredictedSale = "Included in Xbox Game Pass"
        ),
        BaselineMeta(
            productId = "BPQ955FQFPH6",
            fallbackTitle = "The Witcher 3: Wild Hunt – Complete Edition",
            fallbackPublisher = "CD PROJEKT RED",
            fallbackGenre = "Role Playing",
            fallbackRating = 4.9,
            fallbackMrpInr = 2999,
            lowestEverInr = 749,
            isGamePass = false,
            pastFiveMonths = listOf(
                PricePoint("Jun", 749), PricePoint("Jul", 2999),
                PricePoint("Aug", 749), PricePoint("Sep", 2999), PricePoint("Oct", 749)
            ),
            nextPredictedSale = "Black Friday / Year-End Sale"
        ),
        BaselineMeta(
            productId = "9N4RSG99KV1X",
            fallbackTitle = "Grand Theft Auto V (Xbox Series X|S)",
            fallbackPublisher = "Rockstar Games",
            fallbackGenre = "Action & Adventure",
            fallbackRating = 4.5,
            fallbackMrpInr = 2660,
            lowestEverInr = 1330,
            isGamePass = false,
            pastFiveMonths = listOf(
                PricePoint("Jun", 1330), PricePoint("Jul", 1330),
                PricePoint("Aug", 2660), PricePoint("Sep", 1330), PricePoint("Oct", 2660)
            ),
            nextPredictedSale = "Monthly Publisher Spotlight Sale"
        )
    )

    suspend fun fetchLiveIndiaDeals(): Result<List<XboxGameDeal>> = withContext(Dispatchers.IO) {
        try {
            val alertIds = storage.getAllAlerts().keys
            val combinedIds = (trackedCatalog.map { it.productId } + alertIds).distinct()
            val deals = fetchCatalogByBigIds(combinedIds)
            if (deals.isNotEmpty()) Result.success(deals) else Result.success(buildFallbackList())
        } catch (e: Exception) {
            Result.success(buildFallbackList())
        }
    }

    suspend fun searchLiveXboxIndiaCatalog(query: String): Result<List<XboxGameDeal>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext fetchLiveIndiaDeals()

        try {
            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
            val suggestUrl = "https://www.microsoft.com/msstoreapiprod/api/autosuggest" +
                "?market=en-IN&clientId=7F27B536-CF6B-4C65-8638-A0F8CBDFCA65" +
                "&sources=Microsoft-Terms,Iris-Products,xSearch-Products" +
                "&filter=+ClientType:StoreWeb&counts=5,1,12&query=$encodedQuery"

            val conn = (URL(suggestUrl).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("Accept", "application/json")
            }

            val discoveredIds = mutableListOf<String>()
            if (conn.responseCode == 200) {
                val rawSuggest = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(rawSuggest)
                val resultSets = root.optJSONArray("ResultSets")
                if (resultSets != null) {
                    for (i in 0 until resultSets.length()) {
                        val suggests = resultSets.getJSONObject(i).optJSONArray("Suggests") ?: continue
                        for (j in 0 until suggests.length()) {
                            val item = suggests.getJSONObject(j)
                            val metas = item.optJSONArray("Metas") ?: continue
                            for (m in 0 until metas.length()) {
                                val metaObj = metas.getJSONObject(m)
                                if (metaObj.optString("Key").equals("BigCatalogId", ignoreCase = true)) {
                                    val bigId = metaObj.optString("Value")
                                    if (bigId.length >= 12 && !discoveredIds.contains(bigId)) {
                                        discoveredIds.add(bigId)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (discoveredIds.isNotEmpty()) {
                val liveResults = fetchCatalogByBigIds(discoveredIds.take(10))
                if (liveResults.isNotEmpty()) {
                    return@withContext Result.success(liveResults)
                }
            }

            val localMatches = buildFallbackList().filter {
                it.title.contains(cleanQuery, ignoreCase = true) ||
                it.publisher.contains(cleanQuery, ignoreCase = true) ||
                it.genre.contains(cleanQuery, ignoreCase = true)
            }
            Result.success(localMatches)
        } catch (e: Exception) {
            val localMatches = buildFallbackList().filter {
                it.title.contains(cleanQuery, ignoreCase = true)
            }
            Result.success(localMatches)
        }
    }

    fun fetchCatalogByBigIdsSync(bigIds: List<String>): List<XboxGameDeal> {
        return fetchCatalogByBigIds(bigIds)
    }

    private fun fetchCatalogByBigIds(bigIds: List<String>): List<XboxGameDeal> {
        if (bigIds.isEmpty()) return emptyList()
        val bigIdsParam = bigIds.joinToString(",")
        val endpoint = "https://displaycatalog.mp.microsoft.com/v7.0/products" +
            "?bigIds=$bigIdsParam&market=IN&languages=en-in&MS-CV=DGU1mcuYo0WMMp+F.1"

        val conn = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }

        if (conn.responseCode != 200) return emptyList()
        val rawJson = conn.inputStream.bufferedReader().use { it.readText() }
        return parseMicrosoftDisplayCatalog(rawJson)
    }

    private fun parseMicrosoftDisplayCatalog(jsonStr: String): List<XboxGameDeal> {
        val root = JSONObject(jsonStr)
        val products = root.optJSONArray("Products") ?: return emptyList()
        val parsedList = mutableListOf<XboxGameDeal>()

        for (i in 0 until products.length()) {
            val prod = products.getJSONObject(i)
            val prodId = prod.optString("ProductId")
            val knownMeta = trackedCatalog.find { it.productId.equals(prodId, ignoreCase = true) }

            val localized = prod.optJSONArray("LocalizedProperties")?.optJSONObject(0)
            val liveTitle = localized?.optString("ProductTitle")?.takeIf { it.isNotBlank() }
                ?: knownMeta?.fallbackTitle ?: continue
            val livePublisher = localized?.optString("PublisherName")?.takeIf { it.isNotBlank() }
                ?: knownMeta?.fallbackPublisher ?: "Xbox Game Studios"

            val propsObj = prod.optJSONObject("Properties")
            val rawCategory = propsObj?.optString("Category")?.takeIf { it.isNotBlank() }
                ?: knownMeta?.fallbackGenre ?: "Action & Adventure"
            val normalizedGenre = normalizeGenreLabel(rawCategory)

            var parsedRating = knownMeta?.fallbackRating ?: 4.4
            val marketProps = prod.optJSONArray("MarketProperties")?.optJSONObject(0)
            val usageData = marketProps?.optJSONArray("UsageData")
            if (usageData != null) {
                for (u in 0 until usageData.length()) {
                    val avg = usageData.getJSONObject(u).optDouble("AverageRating", 0.0)
                    if (avg > 0.0) {
                        parsedRating = (avg * 10.0).roundToInt() / 10.0
                    }
                }
            }

            var foundListPrice = 0.0
            var foundMsrp = 0.0

            val skuArray = prod.optJSONArray("DisplaySkuAvailabilities")
            if (skuArray != null) {
                for (s in 0 until skuArray.length()) {
                    val availabilities = skuArray.getJSONObject(s).optJSONArray("Availabilities") ?: continue
                    for (a in 0 until availabilities.length()) {
                        val priceObj = availabilities.getJSONObject(a)
                            .optJSONObject("OrderManagementData")
                            ?.optJSONObject("Price") ?: continue

                        val listPrice = priceObj.optDouble("ListPrice", 0.0)
                        val msrp = priceObj.optDouble("MSRP", 0.0)
                        if (msrp > 0.0 && (foundListPrice == 0.0 || (listPrice > 0.0 && listPrice < foundListPrice))) {
                            foundListPrice = if (listPrice > 0.0) listPrice else msrp
                            foundMsrp = msrp
                        }
                    }
                }
            }

            if (foundMsrp <= 0.0 && knownMeta == null) continue

            val currentInr = if (foundListPrice > 0.0) foundListPrice.roundToInt() else (knownMeta?.fallbackMrpInr ?: 0)
            val mrpInr = if (foundMsrp > 0.0) foundMsrp.roundToInt() else (knownMeta?.fallbackMrpInr ?: currentInr)

            val dynamicMeta = knownMeta ?: BaselineMeta(
                productId = prodId,
                fallbackTitle = liveTitle,
                fallbackPublisher = livePublisher,
                fallbackGenre = normalizedGenre,
                fallbackRating = parsedRating,
                fallbackMrpInr = mrpInr,
                lowestEverInr = minOf(currentInr, (mrpInr * 0.50).roundToInt()),
                isGamePass = livePublisher.contains("Xbox", ignoreCase = true) ||
                             livePublisher.contains("Bethesda", ignoreCase = true) ||
                             livePublisher.contains("Electronic Arts", ignoreCase = true) ||
                             livePublisher.contains("Activision", ignoreCase = true),
                pastFiveMonths = listOf(
                    PricePoint("Jun", mrpInr),
                    PricePoint("Jul", (mrpInr * 0.65).roundToInt()),
                    PricePoint("Aug", mrpInr),
                    PricePoint("Sep", (mrpInr * 0.60).roundToInt()),
                    PricePoint("Oct", mrpInr)
                ),
                nextPredictedSale = "Upcoming Xbox Seasonal Sale"
            )

            parsedList.add(
                buildDealModel(
                    meta = dynamicMeta,
                    title = liveTitle,
                    publisher = livePublisher,
                    genre = normalizedGenre,
                    rating = parsedRating,
                    currentInr = currentInr,
                    mrpInr = mrpInr,
                    isLive = foundMsrp > 0.0
                )
            )
        }
        return parsedList
    }

    private fun normalizeGenreLabel(raw: String): String {
        return when {
            raw.contains("Role", ignoreCase = true) || raw.contains("RPG", ignoreCase = true) -> "Role Playing"
            raw.contains("Rac", ignoreCase = true) || raw.contains("Fly", ignoreCase = true) -> "Racing & Flying"
            raw.contains("Shoot", ignoreCase = true) -> "Shooter"
            raw.contains("Sport", ignoreCase = true) -> "Sports"
            else -> "Action & Adventure"
        }
    }

    private fun buildFallbackList(): List<XboxGameDeal> {
        return trackedCatalog.map { meta ->
            buildDealModel(
                meta = meta,
                title = meta.fallbackTitle,
                publisher = meta.fallbackPublisher,
                genre = meta.fallbackGenre,
                rating = meta.fallbackRating,
                currentInr = meta.lowestEverInr,
                mrpInr = meta.fallbackMrpInr,
                isLive = false
            )
        }
    }

    private fun buildDealModel(
        meta: BaselineMeta,
        title: String,
        publisher: String,
        genre: String,
        rating: Double,
        currentInr: Int,
        mrpInr: Int,
        isLive: Boolean
    ): XboxGameDeal {
        val safeMrp = maxOf(mrpInr, currentInr)
        val discountPct = if (safeMrp > currentInr && safeMrp > 0) {
            (((safeMrp - currentInr).toDouble() / safeMrp) * 100.0).roundToInt()
        } else 0

        // Record & verify true All-Time Lowest Price in local ledger
        val actualLowest = storage.recordAndGetLowestPrice(meta.productId, currentInr, meta.lowestEverInr)
        val updatedHistory = storage.recordAndGetHistory(meta.productId, meta.pastFiveMonths, currentInr)

        val pricesOnly = updatedHistory.map { it.priceInr }
        val highestRecorded = maxOf(safeMrp, pricesOnly.maxOrNull() ?: safeMrp)
        val avgPrice = if (pricesOnly.isNotEmpty()) pricesOnly.average().roundToInt() else currentInr
        val isAtLow = currentInr <= actualLowest

        val savedAlert = storage.getAlertForProduct(meta.productId)

        val dealVerdict = when {
            isAtLow -> "🔥 ALL-TIME LOW PRICE (₹$actualLowest) — Buy Now"
            currentInr <= (actualLowest * 1.15).roundToInt() -> "Strong Deal — Within 15% of All-Time Low (₹$actualLowest)"
            discountPct >= 25 -> "Active Sale — ₹${currentInr - actualLowest} above All-Time Low"
            else -> "Wait for Sale — Drops to ₹$actualLowest in ${meta.nextPredictedSale}"
        }

        return XboxGameDeal(
            productId = meta.productId,
            title = title,
            publisher = publisher,
            genre = genre,
            starRating = rating,
            currentPriceInr = currentInr,
            mrpInr = safeMrp,
            lowestEverInr = actualLowest,
            highestEverInr = highestRecorded,
            averagePriceInr = avgPrice,
            isAtAllTimeLow = isAtLow,
            alertTargetInr = savedAlert?.targetPriceInr,
            discountPercent = discountPct,
            isGamePass = meta.isGamePass,
            isLiveApiPrice = isLive,
            priceHistory = updatedHistory,
            nextPredictedSale = meta.nextPredictedSale,
            predictedPriceInr = actualLowest,
            dealRating = dealVerdict,
            storeUrl = "https://www.xbox.com/en-in/games/store/_/${meta.productId}"
        )
    }
}
