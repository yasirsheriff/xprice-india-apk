package `in`.xprice.tracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class CatalogKey(val cacheKey: String) {
    ALL("cache_all_games"),
    NEW("cache_new_launches"),
    DEALS("cache_hot_deals"),
    GAME_PASS("cache_gamepass")
}

class XboxStoreRepository(context: Context) {

    private val appCtx = context.applicationContext
    private val storage = PriceHistoryStorage(appCtx)
    private val client = getHttpClient(appCtx)

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private val PRODUCT_ID_REGEX = Regex("\\b[9BC][A-Z0-9]{11}\\b")
        private const val RECO = "https://reco-public.rec.mp.microsoft.com/channels/Reco/V8.0/Lists"
        private const val RECO_PARAMS = "Market=in&Language=en&ItemTypes=Game&deviceFamily=Windows.Xbox"
        private const val MAX_IDS_PER_FEED = 90

        private val FREE_TO_PLAY_HINTS = listOf(
            "Fortnite", "Warzone", "Apex Legends", "Roblox", "Valorant", "Overwatch", "Rocket League",
            "Halo Infinite", "eFootball", "Marvel Rivals", "Destiny 2", "Genshin", "Free"
        )

        @Volatile
        private var sharedHttpClient: OkHttpClient? = null

        fun getHttpClient(context: Context): OkHttpClient =
            sharedHttpClient ?: synchronized(this) {
                sharedHttpClient ?: OkHttpClient.Builder()
                    .cache(Cache(File(context.cacheDir, "ms_store_http_cache"), 15L * 1024L * 1024L))
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                    .also { sharedHttpClient = it }
            }
    }

    // Always included in "Explore" so the list is never empty on a cold start.
    private val seedProductIds = listOf(
        "9N8VG0B7TDZ0", // Cyberpunk 2077: Ultimate Edition
        "9N2ZDN7NWQKV", // Red Dead Redemption 2
        "9P3J32CTXLRZ", // Elden Ring
        "9NKX70BBCDRN", // Forza Horizon 5
        "BPQ955FQFPH6", // The Witcher 3: Complete Edition
        "9N4RSG99KV1X"  // Grand Theft Auto V
    )

    fun getInstantCache(key: CatalogKey): List<XboxGameDeal> = storage.loadCachedDeals(key.cacheKey)

    fun lastUpdated(key: CatalogKey): Long = storage.cacheTimestamp(key.cacheKey)

    suspend fun fetch(key: CatalogKey, forceRefresh: Boolean): Result<List<XboxGameDeal>> = when (key) {
        CatalogKey.ALL -> fetchFeed(
            key, forceRefresh, includeTracked = true,
            urls = listOf(
                "$RECO/Computed/TopPaid?$RECO_PARAMS&count=30",
                "$RECO/Computed/BestRated?$RECO_PARAMS&count=25",
                "$RECO/Computed/Deal?$RECO_PARAMS&count=25",
                "$RECO/Computed/New?$RECO_PARAMS&count=15"
            )
        )
        CatalogKey.NEW -> fetchFeed(
            key, forceRefresh, forceNewLaunch = true,
            urls = listOf("$RECO/Computed/New?$RECO_PARAMS&count=60")
        )
        CatalogKey.DEALS -> fetchFeed(
            key, forceRefresh,
            urls = listOf("$RECO/Computed/Deal?$RECO_PARAMS&count=90")
        )
        CatalogKey.GAME_PASS -> fetchGamePass(forceRefresh)
    }

    private fun httpGetText(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-IN,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchFeed(
        key: CatalogKey,
        forceRefresh: Boolean,
        urls: List<String>,
        includeTracked: Boolean = false,
        forceGamePass: Boolean = false,
        forceNewLaunch: Boolean = false,
        leavingIds: Set<String> = emptySet()
    ): Result<List<XboxGameDeal>> = withContext(Dispatchers.IO) {
        if (!forceRefresh && storage.isCacheFresh(key.cacheKey, maxAgeMinutes = 25)) {
            val cached = storage.loadCachedDeals(key.cacheKey)
            if (cached.isNotEmpty()) return@withContext Result.success(cached)
        }

        val ids = LinkedHashSet<String>()
        if (includeTracked) {
            ids.addAll(storage.trackedProductIds())
            ids.addAll(seedProductIds)
        }
        ids.addAll(leavingIds)

        val bodies = coroutineScope { urls.map { async { httpGetText(it) } }.awaitAll() }
        bodies.filterNotNull().forEach { body ->
            PRODUCT_ID_REGEX.findAll(body).forEach { if (ids.size < MAX_IDS_PER_FEED) ids.add(it.value) }
        }

        val games = fetchCatalogByBigIds(ids.toList(), forceGamePass, forceNewLaunch, leavingIds)
        when {
            games.isNotEmpty() -> {
                storage.saveCachedDeals(games, key.cacheKey)
                Result.success(games)
            }
            else -> {
                val cached = storage.loadCachedDeals(key.cacheKey)
                if (cached.isNotEmpty()) Result.success(cached)
                else Result.failure(Exception("Couldn't reach Microsoft Store. Check your connection and pull to refresh."))
            }
        }
    }

    private suspend fun fetchGamePass(forceRefresh: Boolean): Result<List<XboxGameDeal>> = withContext(Dispatchers.IO) {
        val leaving = LinkedHashSet<String>()
        httpGetText("https://catalog.gamepass.com/sigls/v2?id=393f05bf-e596-4ef6-9487-6d4fa0eab987&language=en-in&market=IN")
            ?.let { raw -> PRODUCT_ID_REGEX.findAll(raw).forEach { leaving.add(it.value) } }
        fetchFeed(
            CatalogKey.GAME_PASS, forceRefresh,
            urls = listOf(
                "$RECO/Collection/XboxGamePass?$RECO_PARAMS&count=60",
                "https://catalog.gamepass.com/sigls/v2?id=f6f1f99f-9b49-4ccd-b3bf-4d9767a77f5e&language=en-in&market=IN"
            ),
            forceGamePass = true,
            leavingIds = leaving
        )
    }

    suspend fun search(query: String): Result<List<XboxGameDeal>> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext Result.success(emptyList())
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "https://displaycatalog.mp.microsoft.com/v7.0/productFamilies/autosuggest?market=IN&languages=en-in&query=$encoded&top=15",
            "https://www.microsoft.com/msstoreapiprod/api/autosuggest?market=en-IN&clientId=7F27B536-CF6B-4C65-8638-A0F8CBDFCA65&sources=Iris-Products,xSearch-Products&filter=+ClientType:StoreWeb&counts=10,10&query=$encoded"
        )
        val ids = LinkedHashSet<String>()
        coroutineScope { urls.map { async { httpGetText(it) } }.awaitAll() }
            .filterNotNull()
            .forEach { raw -> PRODUCT_ID_REGEX.findAll(raw).forEach { ids.add(it.value) } }

        val live = if (ids.isNotEmpty()) fetchCatalogByBigIds(ids.take(24)) else emptyList()
        if (live.isNotEmpty()) return@withContext Result.success(live)

        val cachedMatches = CatalogKey.entries
            .flatMap { storage.loadCachedDeals(it.cacheKey) }
            .distinctBy { it.productId }
            .filter { it.title.contains(q, ignoreCase = true) || it.publisher.contains(q, ignoreCase = true) }
        Result.success(cachedMatches)
    }

    /**
     * Fetches live prices from DisplayCatalog (25 IDs per request, in parallel),
     * records each price into history, and returns enriched deals.
     */
    suspend fun fetchCatalogByBigIds(
        bigIds: List<String>,
        forceGamePass: Boolean = false,
        forceNewLaunch: Boolean = false,
        leavingIds: Set<String> = emptySet()
    ): List<XboxGameDeal> = withContext(Dispatchers.IO) {
        if (bigIds.isEmpty()) return@withContext emptyList()
        val raw = coroutineScope {
            bigIds.distinct().chunked(25).map { chunk ->
                async {
                    val url = "https://displaycatalog.mp.microsoft.com/v7.0/products" +
                        "?bigIds=${chunk.joinToString(",")}&market=IN&languages=en-in&MS-CV=DGU1mcuYo0WMMp+F.1"
                    httpGetText(url)?.let {
                        try { parseDisplayCatalog(it, forceGamePass, forceNewLaunch, leavingIds) } catch (_: Exception) { emptyList() }
                    } ?: emptyList()
                }
            }.awaitAll().flatten().distinctBy { it.productId }
        }
        raw.forEach { storage.recordPrice(it.productId, it.currentPriceInr) }
        storage.enrich(raw)
    }

    private fun normalizeMediaUrl(rawUrl: String): String {
        val t = rawUrl.trim()
        return when {
            t.startsWith("//") -> "https:$t"
            t.startsWith("http://") -> t.replaceFirst("http://", "https://")
            else -> t
        }
    }

    private fun parseDisplayCatalog(
        jsonStr: String,
        forceGamePass: Boolean,
        forceNewLaunch: Boolean,
        leavingIds: Set<String>
    ): List<XboxGameDeal> {
        val products = JSONObject(jsonStr).optJSONArray("Products") ?: return emptyList()
        val result = mutableListOf<XboxGameDeal>()
        val now = System.currentTimeMillis()
        val thisYear = java.time.LocalDate.now().year

        for (i in 0 until products.length()) {
            val prod = products.getJSONObject(i)
            val prodId = prod.optString("ProductId")

            // Skip DLC, currency packs and add-ons.
            val kind = prod.optString("ProductKind", "Game")
            val type = prod.optString("ProductType", "Game")
            if (listOf(kind, type).any { it.equals("Durable", true) || it.equals("Consumable", true) }) continue

            val loc = prod.optJSONArray("LocalizedProperties")?.optJSONObject(0) ?: continue
            val title = loc.optString("ProductTitle").trim()
            if (title.isBlank()) continue
            val publisher = loc.optString("PublisherName").ifBlank { "Xbox Game Studios" }
            val description = loc.optString("ShortDescription").ifBlank { loc.optString("ProductDescription").take(400) }

            var cover = ""
            var poster = ""
            val shots = mutableListOf<String>()
            loc.optJSONArray("Images")?.let { images ->
                for (k in 0 until images.length()) {
                    val img = images.getJSONObject(k)
                    val uri = normalizeMediaUrl(img.optString("Uri"))
                    if (uri.isBlank()) continue
                    when (img.optString("ImagePurpose")) {
                        "SuperHeroArt", "TitledHeroArt" -> { if (cover.isBlank()) cover = uri; if (shots.size < 8 && uri !in shots) shots.add(uri) }
                        "BoxArt", "Poster", "BrandedKeyArt" -> if (poster.isBlank()) poster = uri
                        "Screenshot" -> if (shots.size < 8 && uri !in shots) shots.add(uri)
                    }
                }
            }
            // Wide hero art looks best on cards; fall back to box art.
            val coverUrl = cover.ifBlank { poster.ifBlank { shots.firstOrNull() ?: "" } }

            var trailer = ""
            loc.optJSONArray("CMSVideos")?.let { vids ->
                for (v in 0 until vids.length()) {
                    val uri = normalizeMediaUrl(vids.getJSONObject(v).optString("Uri"))
                    if (uri.endsWith(".mp4", true) || uri.contains("video", true)) { trailer = uri; break }
                }
            }

            val rawCategory = prod.optJSONObject("Properties")?.optString("Category").orEmpty().ifBlank { "Action & Adventure" }

            var rating = 0.0
            var ratingCount = 0
            var releaseDate = "TBA"
            var isNew = forceNewLaunch
            prod.optJSONArray("MarketProperties")?.optJSONObject(0)?.let { mp ->
                val orig = mp.optString("OriginalReleaseDate")
                if (orig.length >= 10) {
                    val year = orig.substring(0, 4).toIntOrNull() ?: thisYear
                    releaseDate = if (year > thisYear + 1) "TBA" else orig.substring(0, 10)
                    val releaseMs = parseIsoToEpochMs(orig)
                    if (releaseMs > 0 && now - releaseMs in 0..(90L * 24 * 3_600_000)) isNew = true
                }
                mp.optJSONArray("UsageData")?.let { usage ->
                    for (u in 0 until usage.length()) {
                        val uo = usage.getJSONObject(u)
                        val avg = uo.optDouble("AverageRating", 0.0)
                        val cnt = uo.optInt("RatingCount", 0)
                        if (avg > 0.0 && cnt >= ratingCount) {
                            rating = (avg * 10.0).roundToInt() / 10.0
                            ratingCount = cnt
                        }
                    }
                }
            }

            val prodStr = prod.toString()
            val platforms = buildList {
                add("Xbox Series X|S")
                if (prodStr.contains("XboxOne", true)) add("Xbox One")
                if (prodStr.contains("Windows.Desktop", true)) add("PC")
                if (forceGamePass || prodStr.contains("Cloud", true)) add("Cloud")
            }

            // --- Pricing: only availabilities that allow "Purchase" ---
            var lowestPaid = Double.MAX_VALUE
            var lowestPaidMsrp = -1.0
            var hasFreeSku = false
            var saleEnds = 0L
            var gamePass = forceGamePass
            val editions = mutableListOf<GameEditionOption>()
            val looksFreeToPlay = rawCategory.contains("Free", true) || FREE_TO_PLAY_HINTS.any { title.contains(it, true) }

            prod.optJSONArray("DisplaySkuAvailabilities")?.let { skus ->
                for (s in 0 until skus.length()) {
                    val skuWrap = skus.getJSONObject(s)
                    val skuTitle = skuWrap.optJSONObject("Sku")?.optJSONArray("LocalizedProperties")
                        ?.optJSONObject(0)?.optString("SkuTitle")?.takeIf { it.isNotBlank() } ?: title
                    val avails = skuWrap.optJSONArray("Availabilities") ?: continue
                    for (a in 0 until avails.length()) {
                        val av = avails.getJSONObject(a)
                        if (av.optJSONArray("RemediationRequired")?.toString()?.contains("GamePass", true) == true) gamePass = true

                        val actions = av.optJSONArray("Actions")
                        val canPurchase = actions != null && (0 until actions.length()).any { actions.optString(it).equals("Purchase", true) }
                        if (!canPurchase) continue

                        val price = av.optJSONObject("OrderManagementData")?.optJSONObject("Price") ?: continue
                        val list = price.optDouble("ListPrice", -1.0)
                        val msrp = price.optDouble("MSRP", -1.0)

                        if (msrp > 0.0 && list > 0.0) {
                            val p = list.roundToInt()
                            val m = maxOf(p, msrp.roundToInt())
                            val pct = if (m > p) (((m - p).toDouble() / m) * 100.0).roundToInt() else 0
                            if (editions.none { it.skuTitle == skuTitle && it.priceInr == p }) {
                                editions.add(GameEditionOption(skuTitle, p, m, pct))
                            }
                            if (list < lowestPaid) {
                                lowestPaid = list
                                lowestPaidMsrp = msrp
                                // A sale has a near end date; regular prices end in year 9998.
                                val end = parseIsoToEpochMs(av.optJSONObject("Conditions")?.optString("EndDate").orEmpty())
                                saleEnds = if (pct > 0 && end > now && end - now < 60L * 24 * 3_600_000) end else 0L
                            }
                        } else if (msrp == 0.0 && list == 0.0 && looksFreeToPlay) {
                            hasFreeSku = true
                        }
                    }
                }
            }

            val unpriced = lowestPaid == Double.MAX_VALUE && !hasFreeSku
            val current = when {
                lowestPaid < Double.MAX_VALUE -> lowestPaid.roundToInt()
                hasFreeSku -> 0
                else -> -1
            }
            val mrp = if (lowestPaidMsrp > 0.0) maxOf(current, lowestPaidMsrp.roundToInt()) else 0
            val discount = if (mrp > current && current > 0) (((mrp - current).toDouble() / mrp) * 100.0).roundToInt() else 0

            result.add(
                XboxGameDeal(
                    productId = prodId,
                    title = title,
                    publisher = publisher,
                    description = description,
                    genre = normalizeGenre(rawCategory),
                    starRating = rating,
                    ratingCount = ratingCount,
                    releaseDate = releaseDate,
                    isNewLaunch = isNew,
                    isUnreleasedOrNoPrice = unpriced,
                    isLeavingGamePassSoon = leavingIds.contains(prodId),
                    saleEndsAtMs = saleEnds,
                    editions = editions.sortedBy { it.priceInr }.take(6),
                    supportedPlatforms = platforms,
                    coverImageUrl = coverUrl,
                    screenshotUrls = shots,
                    trailerUrl = trailer,
                    currentPriceInr = current,
                    mrpInr = mrp,
                    discountPercent = discount,
                    isGamePass = gamePass,
                    storeUrl = "https://www.xbox.com/en-in/games/store/_/$prodId"
                )
            )
        }
        return result
    }

    private fun normalizeGenre(raw: String): String = when {
        raw.contains("Role", true) || raw.contains("RPG", true) -> "Role Playing"
        raw.contains("Rac", true) || raw.contains("Fly", true) -> "Racing & Flying"
        raw.contains("Shoot", true) -> "Shooter"
        raw.contains("Sport", true) -> "Sports"
        raw.contains("Fight", true) -> "Fighting"
        raw.contains("Strateg", true) -> "Strategy"
        raw.contains("Simulat", true) -> "Simulation"
        raw.contains("Puzzle", true) || raw.contains("Trivia", true) -> "Puzzle"
        raw.contains("Platform", true) -> "Platformer"
        raw.contains("Family", true) || raw.contains("Kids", true) -> "Family"
        else -> "Action & Adventure"
    }
}
