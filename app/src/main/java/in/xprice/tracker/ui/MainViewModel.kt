package `in`.xprice.tracker.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.xprice.tracker.data.*
import `in`.xprice.tracker.widget.RupeeVaultWidget
import `in`.xprice.tracker.worker.PriceAlertWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Destination { EXPLORE, DEALS, GAME_PASS, NEWS, LIBRARY }

enum class DealFilter(val label: String) {
    ALL("Everything"),
    ON_SALE("On sale"),
    LOWEST("Lowest tracked"),
    FREE("Free"),
    FAVOURITES("Favourites"),
    ALERTS("With alerts"),
    OWNED("Owned"),
    LEAVING("Leaving Game Pass")
}

enum class SortOption(val label: String) {
    POPULAR("Popular"),
    DISCOUNT("Biggest discount"),
    PRICE_LOW("Price: low to high"),
    PRICE_HIGH("Price: high to low"),
    RATING("Top rated"),
    NAME("Name (A–Z)")
}

data class FilterState(
    val deal: DealFilter = DealFilter.ALL,
    val genre: String? = null,
    val minRating: Double = 0.0,
    val maxPrice: Int = MAX_PRICE_CAP
) {
    val activeCount: Int
        get() = listOf(deal != DealFilter.ALL, genre != null, minRating > 0.0, maxPrice < MAX_PRICE_CAP).count { it }

    companion object {
        const val MAX_PRICE_CAP = 8000
    }
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = XboxStoreRepository(app)
    private val newsRepository = XboxNewsRepository(app)
    val storage = PriceHistoryStorage(app)
    private val authManager = XboxLiveAuthManager()

    // --- Navigation & display ---
    var destination by mutableStateOf(Destination.EXPLORE)
        private set
    var exploreFeed by mutableStateOf(CatalogKey.ALL)
        private set
    var viewMode by mutableStateOf(ViewLayoutMode.DETAILED)

    // --- Data ---
    val catalogs = mutableStateMapOf<CatalogKey, List<XboxGameDeal>>()
    private val loadingKeys = mutableStateMapOf<CatalogKey, Boolean>()
    var loadError by mutableStateOf<String?>(null)
        private set

    var news by mutableStateOf(newsRepository.getInstantCachedNews())
        private set
    var newsLoading by mutableStateOf(false)
        private set
    var newsCategory by mutableStateOf("All")

    // --- Search / filters ---
    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<XboxGameDeal>>(emptyList())
        private set
    var searchLoading by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    var filters by mutableStateOf(FilterState())
    var sort by mutableStateOf(SortOption.POPULAR)

    // --- Selection ---
    var selectedGameId by mutableStateOf<String?>(null)
    var compareSelection by mutableStateOf<List<XboxGameDeal>>(emptyList())
        private set

    // --- Xbox profile & library ---
    var profile by mutableStateOf(storage.getXboxProfile())
        private set
    var library by mutableStateOf(storage.getOwnedVault().values.toList())
        private set
    var syncState by mutableStateOf<SyncState>(SyncState.Idle)
        private set
    private var tokenHandled = false

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    init {
        CatalogKey.entries.forEach { catalogs[it] = repository.getInstantCache(it) }
        load(CatalogKey.ALL)
        viewModelScope.launch {
            if (repository.syncSharedHistory(storage.trackedProductIds()) > 0) reEnrichAll()
        }
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------
    fun navigate(dest: Destination) {
        destination = dest
        when (dest) {
            Destination.EXPLORE -> load(exploreFeed)
            Destination.DEALS -> load(CatalogKey.DEALS)
            Destination.GAME_PASS -> load(CatalogKey.GAME_PASS)
            Destination.NEWS -> if (news.isEmpty()) refreshNews()
            Destination.LIBRARY -> refreshLibrary()
        }
    }

    fun selectExploreFeed(key: CatalogKey) {
        exploreFeed = key
        load(key)
    }

    val activeCatalogKey: CatalogKey?
        get() = when (destination) {
            Destination.EXPLORE -> exploreFeed
            Destination.DEALS -> CatalogKey.DEALS
            Destination.GAME_PASS -> CatalogKey.GAME_PASS
            else -> null
        }

    fun isLoading(key: CatalogKey?): Boolean = key != null && loadingKeys[key] == true

    fun lastUpdated(key: CatalogKey): Long = repository.lastUpdated(key)

    // ---------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------
    fun load(key: CatalogKey, force: Boolean = false) {
        if (loadingKeys[key] == true) return
        loadingKeys[key] = true
        viewModelScope.launch {
            repository.fetch(key, force)
                .onSuccess { catalogs[key] = it; loadError = null }
                .onFailure { loadError = it.message }
            loadingKeys[key] = false
            updateWidget()
        }
    }

    fun refreshCurrent() {
        when (destination) {
            Destination.NEWS -> refreshNews()
            Destination.LIBRARY -> refreshLibrary()
            else -> activeCatalogKey?.let { load(it, force = true) }
        }
        if (searchQuery.trim().length >= 2) runSearch(searchQuery)
    }

    fun refreshNews() {
        if (newsLoading) return
        newsLoading = true
        viewModelScope.launch {
            newsRepository.fetchLiveXboxWireNews().onSuccess { news = it }
            newsLoading = false
        }
    }

    private fun refreshLibrary() {
        profile = storage.getXboxProfile()
        library = storage.getOwnedVault().values.toList()
    }

    // ---------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------
    fun updateSearch(query: String) {
        searchQuery = query
        if (destination == Destination.NEWS || destination == Destination.LIBRARY) return
        runSearch(query)
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            searchResults = emptyList()
            searchLoading = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // debounce typing
            searchLoading = true
            repository.search(q).onSuccess { searchResults = it }
            searchLoading = false
        }
    }

    fun clearSearch() = updateSearch("")

    /** Games visible on the current tab after search, filters and sort. */
    fun visibleGames(): List<XboxGameDeal> {
        val key = activeCatalogKey ?: return emptyList()
        val base = catalogs[key].orEmpty()
        val q = searchQuery.trim()
        val source = if (q.length < 2) base else {
            val local = base.filter { it.title.contains(q, true) || it.publisher.contains(q, true) }
            (local + searchResults).distinctBy { it.productId }
        }
        val f = filters
        val filtered = source.filter { g ->
            val dealOk = when (f.deal) {
                DealFilter.ALL -> true
                DealFilter.ON_SALE -> g.discountPercent > 0
                DealFilter.LOWEST -> g.isAtAllTimeLow
                DealFilter.FREE -> g.currentPriceInr == 0
                DealFilter.FAVOURITES -> g.isFavourite
                DealFilter.ALERTS -> g.alertTargetInr != null
                DealFilter.OWNED -> g.ownedPaidInr != null
                DealFilter.LEAVING -> g.isLeavingGamePassSoon
            }
            dealOk &&
                (f.genre == null || g.genre == f.genre) &&
                (f.minRating <= 0.0 || g.starRating >= f.minRating) &&
                (f.maxPrice >= FilterState.MAX_PRICE_CAP || g.currentPriceInr <= f.maxPrice)
        }
        return when (sort) {
            SortOption.POPULAR -> filtered
            SortOption.DISCOUNT -> filtered.sortedByDescending { it.discountPercent }
            SortOption.PRICE_LOW -> filtered.sortedBy { if (it.currentPriceInr < 0) Int.MAX_VALUE else it.currentPriceInr }
            SortOption.PRICE_HIGH -> filtered.sortedByDescending { it.currentPriceInr }
            SortOption.RATING -> filtered.sortedByDescending { it.starRating }
            SortOption.NAME -> filtered.sortedBy { it.title.lowercase() }
        }
    }

    fun availableGenres(): List<String> =
        catalogs.values.flatten().map { it.genre }.distinct().sorted()

    fun findGame(productId: String): XboxGameDeal? =
        (catalogs.values.flatten() + searchResults).firstOrNull { it.productId == productId }

    fun allKnownGames(): List<XboxGameDeal> = (catalogs.values.flatten() + searchResults).distinctBy { it.productId }

    // ---------------------------------------------------------------------
    // User actions
    // ---------------------------------------------------------------------
    /** Re-applies favourites / alerts / owned / history to every loaded list. */
    private fun reEnrichAll() {
        CatalogKey.entries.forEach { k -> catalogs[k]?.let { catalogs[k] = storage.enrich(it) } }
        searchResults = storage.enrich(searchResults)
        library = storage.getOwnedVault().values.toList()
        updateWidget()
    }

    fun toggleFavourite(productId: String) {
        val nowFav = storage.toggleFavourite(productId)
        reEnrichAll()
        messageChannel.trySend(if (nowFav) "Added to favourites" else "Removed from favourites")
    }

    fun setAlert(game: XboxGameDeal, targetInr: Int) {
        storage.setPriceAlert(game.productId, game.title, targetInr)
        reEnrichAll()
        messageChannel.trySend("We'll notify you when it drops to ${formatInr(targetInr)}")
        if (game.currentPriceInr in 1..targetInr && storage.shouldNotify(game.productId, game.currentPriceInr)) {
            PriceAlertWorker.sendPriceDropNotification(
                getApplication(), game.productId, game.title, game.currentPriceInr, targetInr, game.storeUrl
            )
        }
    }

    fun removeAlert(productId: String) {
        storage.removePriceAlert(productId)
        reEnrichAll()
        messageChannel.trySend("Alert removed")
    }

    fun setOwned(game: XboxGameDeal, paidInr: Int?) {
        if (paidInr != null) {
            storage.markGameOwned(game.productId, game.title, paidInr, game.mrpInr, game.currentPriceInr, game.coverImageUrl)
            messageChannel.trySend("Added to your library")
        } else {
            storage.removeGameFromOwned(game.productId)
            messageChannel.trySend("Removed from your library")
        }
        reEnrichAll()
    }

    fun toggleCompare(game: XboxGameDeal) {
        val current = compareSelection
        compareSelection = when {
            current.any { it.productId == game.productId } -> current.filterNot { it.productId == game.productId }
            current.size >= 2 -> listOf(current.last(), game)
            else -> current + game
        }
        if (compareSelection.size == 1) messageChannel.trySend("Pick one more game to compare")
    }

    /** Opens the detail sheet and pulls this game's shared daily history in the background. */
    fun openGame(productId: String) {
        selectedGameId = productId
        viewModelScope.launch {
            if (repository.syncSharedHistory(listOf(productId)) > 0) reEnrichAll()
        }
    }

    fun clearCompare() {
        compareSelection = emptyList()
    }

    // ---------------------------------------------------------------------
    // Xbox sign-in
    // ---------------------------------------------------------------------
    /** Current sign-in attempt; null when sign-in isn't in progress. */
    var oauthSession by mutableStateOf<OAuthSession?>(null)
        private set

    /** Client ID in use: the one entered in the app, else the built-in one. */
    var xboxClientIdOverride by mutableStateOf(storage.getXboxClientIdOverride())
        private set
    val effectiveXboxClientId: String
        get() = xboxClientIdOverride.ifBlank { XboxLiveAuthManager.BUILT_IN_CLIENT_ID }

    /** Shown when sign-in is tapped but no client ID is available yet. */
    var showClientIdSetup by mutableStateOf(false)

    fun saveXboxClientId(id: String) {
        storage.setXboxClientIdOverride(id)
        xboxClientIdOverride = id.trim()
        showClientIdSetup = false
        messageChannel.trySend(if (id.isBlank()) "Using the built-in Xbox client ID" else "Xbox client ID saved")
    }

    /** Returns true if the sign-in screen should open. */
    fun beginXboxSignIn(): Boolean {
        val clientId = effectiveXboxClientId
        if (!XboxLiveAuthManager.isValidClientId(clientId)) {
            showClientIdSetup = true
            return false
        }
        tokenHandled = false
        syncState = SyncState.Idle
        oauthSession = XboxLiveAuthManager.newSession(clientId)
        return true
    }

    fun cancelXboxSignIn() {
        oauthSession = null
    }

    fun onXboxSignInError(message: String) {
        oauthSession = null
        syncState = SyncState.Failed(message)
    }

    fun onXboxCodeCaptured(code: String) {
        // The WebView can report the redirect more than once; only handle the first.
        val session = oauthSession ?: return
        if (tokenHandled) return
        tokenHandled = true
        oauthSession = null
        syncState = SyncState.Running("Signing in to Xbox Live…")
        viewModelScope.launch {
            authManager.authenticateWithCode(code, session)
                .onSuccess { data ->
                    syncState = SyncState.Running("Fetching store prices for ${data.titles.size} games…")
                    storage.saveXboxProfile(data.gamertag, data.xuid, data.gamerscore, data.avatarUrl)

                    val ids = data.titles.map { it.productId }.filter { it.isNotBlank() }.distinct()
                    val priced = repository.fetchCatalogByBigIds(ids).associateBy { it.productId }

                    val entries = data.titles.map { t ->
                        val store = priced[t.productId]
                        val current = store?.currentPriceInr?.coerceAtLeast(0) ?: 0
                        OwnedVaultEntry(
                            productId = t.productId.ifBlank { "title:${t.titleId}" },
                            title = t.name,
                            paidInr = null,
                            mrpInr = store?.mrpInr ?: 0,
                            currentInr = current,
                            hoursPlayed = (t.minutesPlayed / 60.0 * 10.0).roundToInt() / 10.0,
                            achievementsPct = if (t.progressPercent > 0) t.progressPercent
                            else if (t.maxGamerscore > 0) (t.currentGamerscore * 100 / t.maxGamerscore) else 0,
                            imageUrl = t.imageUrl.ifBlank { store?.coverImageUrl.orEmpty() },
                            fromXbox = true,
                            lastPlayed = t.lastPlayed
                        )
                    }
                    storage.mergeXboxLibrary(entries)
                    refreshLibrary()
                    reEnrichAll()
                    syncState = SyncState.Idle
                    messageChannel.trySend("Synced ${data.titles.size} games for ${data.gamertag}")
                }
                .onFailure { err ->
                    syncState = SyncState.Failed(err.message ?: "Xbox sign-in failed. Please try again.")
                }
        }
    }

    fun signOutXbox() {
        storage.disconnectXboxProfile()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        syncState = SyncState.Idle
        refreshLibrary()
        reEnrichAll()
        messageChannel.trySend("Signed out of Xbox")
    }

    fun dismissSyncError() {
        syncState = SyncState.Idle
    }

    private fun updateWidget() {
        viewModelScope.launch {
            try { RupeeVaultWidget().updateAll(getApplication()) } catch (_: Exception) {}
        }
    }
}
