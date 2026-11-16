package `in`.xprice.tracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

data class PricePoint(val label: String, val priceInr: Int)

enum class ViewLayoutMode(val label: String) {
    GRID("Grid"),
    LIST("List"),
    DETAILED("Detailed")
}

data class XboxGameDeal(
    val productId: String,
    val title: String,
    val publisher: String,
    val genre: String,
    val starRating: Double,
    val coverImageUrl: String,
    val screenshotUrls: List<String>,
    val trailerUrl: String,
    val currentPriceInr: Int,
    val mrpInr: Int,
    val lowestEverInr: Int,
    val highestEverInr: Int,
    val averagePriceInr: Int,
    val isAtAllTimeLow: Boolean,
    val isFavourite: Boolean,
    val alertTargetInr: Int?,
    val discountPercent: Int,
    val isGamePass: Boolean,
    val isLiveApiPrice: Boolean,
    val monthHistory: List<PricePoint>,
    val yearHistory: List<PricePoint>,
    val allTimeHistory: List<PricePoint>,
    val nextPredictedSale: String,
    val predictedPriceInr: Int,
    val recommendationEmoji: String,
    val dealRating: String,
    val storeUrl: String
)

private data class BaselineMeta(
    val productId: String,
    val fallbackTitle: String,
    val fallbackPublisher: String,
    val fallbackGenre: String,
    val fallbackRating: Double,
    val fallbackCoverUrl: String,
    val fallbackScreenshots: List<String>,
    val fallbackTrailerUrl: String,
    val fallbackMrpInr: Int,
    val lowestEverInr: Int,
    val isGamePass: Boolean,
    val nextPredictedSale: String
)

class XboxStoreRepository(context: Context) {

    private val storage = PriceHistoryStorage(context.applicationContext)

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private val PRODUCT_ID_REGEX = Regex("\b[9BC][A-Z0-9]{11}\b")
    }

    private val trackedCatalog = listOf(
        BaselineMeta(
            productId = "9N8VG0B7TDZ0",
            fallbackTitle = "Cyberpunk 2077: Ultimate Edition",
            fallbackPublisher = "CD PROJEKT RED",
            fallbackGenre = "Role Playing",
            fallbackRating = 4.6,
            fallbackCoverUrl = "",
            fallbackScreenshots = emptyList(),
            fallbackTrailerUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
            fallbackMrpInr = 4499,
            lowestEverInr = 2474,
            isGamePass = false,
            nextPredictedSale = "Xbox Countdown Sale (Dec)"
        ),
        BaselineMeta(
            productId = "9N2ZDN7NWQKV",
            fallbackTitle = "Red Dead Redemption 2",
            fallbackPublisher = "Rockstar Games",
            fallbackGenre = "Action & Adventure",
            fallbackRating = 4.8,
            fallbackCoverUrl = "",
            fallbackScreenshots = emptyList(),
            fallbackTrailerUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            fallbackMrpInr = 4000,
            lowestEverInr = 1320,
            isGamePass = false,
            nextPredictedSale = "Black Friday / Year-End Sale"
        ),
        BaselineMeta(
            productId = "9P3J32CTXLRZ",
            fallbackTitle = "Elden Ring",
            fallbackPublisher = "Bandai Namco Entertainment",
            fallbackGenre = "Role Playing",
            fallbackRating = 4.7,
            fallbackCoverUrl = "",
            fallbackScreenshots = emptyList(),
            fallbackTrailerUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
            fallbackMrpInr = 3999,
            lowestEverInr = 2399,
            isGamePass = false,
            nextPredictedSale = "Xbox Countdown Sale (Dec)"
        ),
        BaselineMeta(
            productId = "9NKX70BBCDRN",
            fallbackTitle = "Forza Horizon 5 Standard Edition",
            fallbackPublisher = "Xbox Game Studios",
            fallbackGenre = "Racing & Flying",
            fallbackRating = 4.6,
            fallbackCoverUrl = "",
            fallbackScreenshots = emptyList(),
            fallbackTrailerUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
            fallbackMrpInr = 3999,
            lowestEverInr = 1999,
            isGamePass = true,
            nextPredictedSale = "Included in Xbox Game Pass"
        ),
        BaselineMeta(
            productId = "BPQ955FQFPH6",
            fallbackTitle = "The Witcher 3: Wild Hunt – Complete Edition",
            fallbackPublisher = "CD PROJEKT RED",
            fallbackGenre = "Role Playing",
            fallbackRating = 4.9,
            fallbackCoverUrl = "",
            fallbackScreenshots = emptyList(),
            fallbackTrailerUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoy.mp4",
            fallbackMrpInr = 2999,
            lowestEverInr = 749,
            isGamePass = false,
            nextPredictedSale = "Black Friday / Year-End Sale"
        ),
        BaselineMeta(
            productId = "9N4RSG99KV1X",
            fallbackTitle = "Grand Theft Auto V (Xbox Series X|S)",
            fallbackPublisher = "Rockstar Games",
            fallbackGenre = "Action & Adventure",
            fallbackRating = 4.5,
            fallbackCoverUrl = "",
            fallbackScreenshots = emptyList(),
            fallbackTrailerUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerMeltdowns.mp4",
            fallbackMrpInr = 2660,
            lowestEverInr = 1330,
            isGamePass = false,
            nextPredictedSale = "Monthly Publisher Spotlight Sale"
        )
    )

    fun getInstantOfflineCache(): List<XboxGameDeal> {
        val cached = storage.loadCachedDeals("offline_catalog_cache")
        return if (cached.isNotEmpty()) cached else buildFallbackList()
    }

    fun getInstantOfflineGamePassCache(): List<XboxGameDeal> {
        val cached = storage.loadCachedDeals("offline_gamepass_cache")
        return if (cached.isNotEmpty()) cached else getInstantOfflineCache().filter { it.isGamePass }
    }

    private fun httpGetText(urlStr: String, timeoutMs: Int = 8000): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // 1. Fetch Live Store Deals & Top Paid Xbox India Catalog
    suspend fun fetchLiveIndiaDeals(): Result<List<XboxGameDeal>> = withContext(Dispatchers.IO) {
        try {
            val discoveredIds = LinkedHashSet<String>()
            discoveredIds.addAll(storage.getAllAlerts().keys)
            discoveredIds.addAll(storage.getFavouriteIds())
            discoveredIds.addAll(trackedCatalog.map { it.productId })

            val dealFeedUrl = "https://reco-public.rec.mp.microsoft.com/channels/Reco/V8.0/Lists/Computed/Deal" +
                "?Market=in&Language=en&ItemTypes=Game&deviceFamily=Windows.Xbox&count=25"
            httpGetText(dealFeedUrl)?.let { body ->
                PRODUCT_ID_REGEX.findAll(body).forEach { match ->
                    if (discoveredIds.size < 45) discoveredIds.add(match.value)
                }
            }

            val topPaidUrl = "https://reco-public.rec.mp.microsoft.com/channels/Reco/V8.0/Lists/Computed/TopPaid" +
                "?Market=in&Language=en&ItemTypes=Game&deviceFamily=Windows.Xbox&count=20"
            httpGetText(topPaidUrl)?.let { body ->
                PRODUCT_ID_REGEX.findAll(body).forEach { match ->
                    if (discoveredIds.size < 45) discoveredIds.add(match.value)
                }
            }

            val allDeals = mutableListOf<XboxGameDeal>()
            discoveredIds.toList().chunked(20).forEach { chunk ->
                allDeals.addAll(fetchCatalogByBigIds(chunk))
            }

            if (allDeals.isNotEmpty()) {
                val distinctDeals = allDeals.distinctBy { it.productId }
                storage.saveCachedDeals(distinctDeals, "offline_catalog_cache")
                Result.success(distinctDeals)
            } else {
                Result.success(getInstantOfflineCache())
            }
        } catch (e: Exception) {
            Result.success(getInstantOfflineCache())
        }
    }

    // 2. Fetch Dedicated Xbox Game Pass India Catalog via Microsoft Reco & SIGL APIs
    suspend fun fetchGamePassIndiaCatalog(): Result<List<XboxGameDeal>> = withContext(Dispatchers.IO) {
        try {
            val gamePassIds = LinkedHashSet<String>()

            // Primary: Microsoft Reco Game Pass Collection Endpoint (India Market)
            val recoGamePassUrl = "https://reco-public.rec.mp.microsoft.com/channels/Reco/V8.0/Lists/Collection/XboxGamePass" +
                "?Market=in&Language=en&ItemTypes=Game&deviceFamily=Windows.Xbox&count=45"
            httpGetText(recoGamePassUrl)?.let { body ->
                PRODUCT_ID_REGEX.findAll(body).forEach { match ->
                    if (gamePassIds.size < 50) gamePassIds.add(match.value)
                }
            }

            // Secondary: Microsoft Official Game Pass Console SIGL Catalog (IN Market)
            if (gamePassIds.size < 25) {
                val siglUrl = "https://catalog.gamepass.com/sigls/v2" +
                    "?id=f6f1f99f-9b49-4ccd-b3bf-4d9767a77f5e&language=en-in&market=IN"
                httpGetText(siglUrl)?.let { rawSigl ->
                    try {
                        val arr = JSONArray(rawSigl)
                        for (i in 0 until arr.length()) {
                            val id = arr.getJSONObject(i).optString("id")
                            if (id.length >= 12 && gamePassIds.size < 50) {
                                gamePassIds.add(id)
                            }
                        }
                    } catch (_: Exception) {
                        PRODUCT_ID_REGEX.findAll(rawSigl).forEach { match ->
                            if (gamePassIds.size < 50) gamePassIds.add(match.value)
                        }
                    }
                }
            }

            val allGamePassDeals = mutableListOf<XboxGameDeal>()
            gamePassIds.toList().chunked(20).forEach { chunk ->
                allGamePassDeals.addAll(fetchCatalogByBigIds(chunk, forceGamePassFlag = true))
            }

            if (allGamePassDeals.isNotEmpty()) {
                val distinctGp = allGamePassDeals.distinctBy { it.productId }
                storage.saveCachedDeals(distinctGp, "offline_gamepass_cache")
                Result.success(distinctGp)
            } else {
                Result.success(getInstantOfflineGamePassCache())
            }
        } catch (e: Exception) {
            Result.success(getInstantOfflineGamePassCache())
        }
    }

    // 3. Live Type-Ahead Autocomplete Search across Microsoft Xbox India Store
    suspend fun searchLiveXboxIndiaCatalog(query: String): Result<List<XboxGameDeal>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext fetchLiveIndiaDeals()

        try {
            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
            val discoveredIds = LinkedHashSet<String>()

            val catalogSuggestUrl = "https://displaycatalog.mp.microsoft.com/v7.0/productFamilies/autosuggest" +
                "?market=IN&languages=en-in&query=$encodedQuery&top=15"
            httpGetText(catalogSuggestUrl, 6000)?.let { raw ->
                try {
                    val root = JSONObject(raw)
                    val results = root.optJSONArray("Results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val products = results.getJSONObject(i).optJSONArray("Products") ?: continue
                            for (p in 0 until products.length()) {
                                val pid = products.getJSONObject(p).optString("ProductId")
                                if (pid.length >= 12) discoveredIds.add(pid)
                            }
                        }
                    }
                } catch (_: Exception) {}
                PRODUCT_ID_REGEX.findAll(raw).forEach { discoveredIds.add(it.value) }
            }

            if (discoveredIds.size < 6) {
                val webSuggestUrl = "https://www.microsoft.com/msstoreapiprod/api/autosuggest" +
                    "?market=en-IN&clientId=7F27B536-CF6B-4C65-8638-A0F8CBDFCA65" +
                    "&sources=Iris-Products,xSearch-Products&filter=+ClientType:StoreWeb&counts=10,10&query=$encodedQuery"
                httpGetText(webSuggestUrl, 6000)?.let { raw ->
                    PRODUCT_ID_REGEX.findAll(raw).forEach { discoveredIds.add(it.value) }
                }
            }

            val allCached = (getInstantOfflineCache() + getInstantOfflineGamePassCache()).distinctBy { it.productId }
            val cachedMatches = allCached.filter {
                it.title.contains(cleanQuery, ignoreCase = true) ||
                it.publisher.contains(cleanQuery, ignoreCase = true)
            }

            if (discoveredIds.isNotEmpty()) {
                val liveResults = fetchCatalogByBigIds(discoveredIds.take(15))
                val merged = (liveResults + cachedMatches).distinctBy { it.productId }
                if (merged.isNotEmpty()) {
                    return@withContext Result.success(merged)
                }
            }

            Result.success(cachedMatches)
        } catch (e: Exception) {
            val cachedMatches = getInstantOfflineCache().filter {
                it.title.contains(cleanQuery, ignoreCase = true)
            }
            Result.success(cachedMatches)
        }
    }

    fun fetchCatalogByBigIdsSync(bigIds: List<String>): List<XboxGameDeal> = fetchCatalogByBigIds(bigIds)

    private fun fetchCatalogByBigIds(bigIds: List<String>, forceGamePassFlag: Boolean = false): List<XboxGameDeal> {
        if (bigIds.isEmpty()) return emptyList()
        val bigIdsParam = bigIds.joinToString(",")
        val endpoint = "https://displaycatalog.mp.microsoft.com/v7.0/products" +
            "?bigIds=$bigIdsParam&market=IN&languages=en-in&MS-CV=DGU1mcuYo0WMMp+F.1"

        val rawJson = httpGetText(endpoint, 8000) ?: return emptyList()
        return parseMicrosoftDisplayCatalog(rawJson, forceGamePassFlag)
    }

    private fun normalizeStoreMediaUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "https://")
            else -> trimmed
        }
    }

    private fun parseMicrosoftDisplayCatalog(jsonStr: String, forceGamePassFlag: Boolean = false): List<XboxGameDeal> {
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

            var coverUrl = knownMeta?.fallbackCoverUrl ?: ""
            val screenshots = mutableListOf<String>()
            val imagesArr = localized?.optJSONArray("Images")
            if (imagesArr != null) {
                for (imgIdx in 0 until imagesArr.length()) {
                    val imgObj = imagesArr.getJSONObject(imgIdx)
                    val purpose = imgObj.optString("ImagePurpose")
                    val uri = normalizeStoreMediaUrl(imgObj.optString("Uri"))
                    if (uri.isNotBlank()) {
                        if (purpose.equals("BoxArt", true) || purpose.equals("Poster", true) || purpose.equals("BrandedKeyArt", true)) {
                            coverUrl = uri
                        } else if (purpose.equals("Screenshot", true) || purpose.equals("SuperHeroArt", true) || purpose.equals("TitledHeroArt", true)) {
                            if (screenshots.size < 5 && !screenshots.contains(uri)) {
                                screenshots.add(uri)
                            }
                        }
                    }
                }
            }
            if (coverUrl.isBlank() && screenshots.isNotEmpty()) {
                coverUrl = screenshots.first()
            }

            var trailerUrl = knownMeta?.fallbackTrailerUrl
                ?: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
            val videosArr = localized?.optJSONArray("CMSVideos")
            if (videosArr != null && videosArr.length() > 0) {
                for (vIdx in 0 until videosArr.length()) {
                    val vObj = videosArr.getJSONObject(vIdx)
                    val videoUri = normalizeStoreMediaUrl(vObj.optString("Uri"))
                    if (videoUri.endsWith(".mp4", ignoreCase = true) || videoUri.contains("video", ignoreCase = true)) {
                        trailerUrl = videoUri
                        break
                    }
                }
            }

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
            var detectedGamePass = forceGamePassFlag || (knownMeta?.isGamePass == true)

            val skuArray = prod.optJSONArray("DisplaySkuAvailabilities")
            if (skuArray != null) {
                for (s in 0 until skuArray.length()) {
                    val availabilities = skuArray.getJSONObject(s).optJSONArray("Availabilities") ?: continue
                    for (a in 0 until availabilities.length()) {
                        val availObj = availabilities.getJSONObject(a)
                        val remTags = availObj.optJSONArray("RemediationRequired")
                        if (remTags != null && remTags.toString().contains("GamePass", ignoreCase = true)) {
                            detectedGamePass = true
                        }
                        val priceObj = availObj.optJSONObject("OrderManagementData")
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

            val dynamicMeta = knownMeta?.copy(isGamePass = detectedGamePass) ?: BaselineMeta(
                productId = prodId,
                fallbackTitle = liveTitle,
                fallbackPublisher = livePublisher,
                fallbackGenre = normalizedGenre,
                fallbackRating = parsedRating,
                fallbackCoverUrl = coverUrl,
                fallbackScreenshots = screenshots,
                fallbackTrailerUrl = trailerUrl,
                fallbackMrpInr = mrpInr,
                lowestEverInr = minOf(currentInr, (mrpInr * 0.50).roundToInt()),
                isGamePass = detectedGamePass ||
                             livePublisher.contains("Xbox", ignoreCase = true) ||
                             livePublisher.contains("Bethesda", ignoreCase = true),
                nextPredictedSale = if (detectedGamePass) "Included in Xbox Game Pass" else "Upcoming Xbox Seasonal Sale"
            )

            parsedList.add(
                buildDealModel(
                    meta = dynamicMeta,
                    title = liveTitle,
                    publisher = livePublisher,
                    genre = normalizedGenre,
                    rating = parsedRating,
                    coverUrl = coverUrl,
                    screenshots = screenshots,
                    trailerUrl = trailerUrl,
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
                coverUrl = meta.fallbackCoverUrl,
                screenshots = meta.fallbackScreenshots,
                trailerUrl = meta.fallbackTrailerUrl,
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
        coverUrl: String,
        screenshots: List<String>,
        trailerUrl: String,
        currentInr: Int,
        mrpInr: Int,
        isLive: Boolean
    ): XboxGameDeal {
        val safeMrp = maxOf(mrpInr, currentInr)
        val discountPct = if (safeMrp > currentInr && safeMrp > 0) {
            (((safeMrp - currentInr).toDouble() / safeMrp) * 100.0).roundToInt()
        } else 0

        val actualLowest = storage.recordAndGetLowestPrice(meta.productId, currentInr, meta.lowestEverInr)

        val monthHist = storage.getTimeframeHistory(
            meta.productId, "1M",
            PriceHistoryStorage.buildDefaultMonthHistory(safeMrp, currentInr), currentInr
        )
        val yearHist = storage.getTimeframeHistory(
            meta.productId, "1Y",
            PriceHistoryStorage.buildDefaultYearHistory(safeMrp, actualLowest, currentInr), currentInr
        )
        val allTimeHist = storage.getTimeframeHistory(
            meta.productId, "ALL",
            PriceHistoryStorage.buildDefaultAllTimeHistory(safeMrp, actualLowest, currentInr), currentInr
        )

        val allPrices = allTimeHist.map { it.priceInr }
        val highestRecorded = maxOf(safeMrp, allPrices.maxOrNull() ?: safeMrp)
        val avgPrice = if (allPrices.isNotEmpty()) allPrices.average().roundToInt() else currentInr
        val isAtLow = currentInr <= actualLowest

        val (emoji, verdict) = PriceHistoryStorage.computeRecommendation(
            curr = currentInr,
            mrp = safeMrp,
            low = actualLowest,
            discountPct = discountPct,
            isGamePass = meta.isGamePass,
            nextSale = meta.nextPredictedSale
        )

        return XboxGameDeal(
            productId = meta.productId,
            title = title,
            publisher = publisher,
            genre = genre,
            starRating = rating,
            coverImageUrl = coverUrl,
            screenshotUrls = screenshots,
            trailerUrl = trailerUrl,
            currentPriceInr = currentInr,
            mrpInr = safeMrp,
            lowestEverInr = actualLowest,
            highestEverInr = highestRecorded,
            averagePriceInr = avgPrice,
            isAtAllTimeLow = isAtLow,
            isFavourite = storage.isFavourite(meta.productId),
            alertTargetInr = storage.getAlertForProduct(meta.productId)?.targetPriceInr,
            discountPercent = discountPct,
            isGamePass = meta.isGamePass,
            isLiveApiPrice = isLive,
            monthHistory = monthHist,
            yearHistory = yearHist,
            allTimeHistory = allTimeHist,
            nextPredictedSale = meta.nextPredictedSale,
            predictedPriceInr = actualLowest,
            recommendationEmoji = emoji,
            dealRating = verdict,
            storeUrl = "https://www.xbox.com/en-in/games/store/_/${meta.productId}"
        )
    }
}
