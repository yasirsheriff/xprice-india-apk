package `in`.xprice.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import `in`.xprice.tracker.data.*
import `in`.xprice.tracker.widget.RupeeVaultWidget
import `in`.xprice.tracker.worker.PriceAlertWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scheduleBackgroundPriceCheck()

        setContent {
            val darkPalette = darkColorScheme(
                primary = Color(0xFF10B981),
                secondary = Color(0xFFF59E0B),
                background = Color(0xFF090C12),
                surface = Color(0xFF131823)
            )
            MaterialTheme(colorScheme = darkPalette) {
                RupeeVaultMainScreen()
            }
        }
    }

    private fun scheduleBackgroundPriceCheck() {
        val workRequest = PeriodicWorkRequestBuilder<PriceAlertWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "rupeevault_x_background_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

fun formatStorePriceLabel(priceInr: Int): String {
    return when {
        priceInr < 0 -> "COMING SOON"
        priceInr == 0 -> "FREE"
        else -> "₹$priceInr"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RupeeVaultMainScreen() {
    val context = LocalContext.current
    val repository = remember { XboxStoreRepository(context) }
    val newsRepository = remember { XboxNewsRepository(context) }
    val storage = remember { PriceHistoryStorage(context) }
    val authManager = remember { XboxLiveAuthManager() }
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableIntStateOf(0) }

    var allGamesList by remember { mutableStateOf(repository.getInstantCache("cache_all_games")) }
    var newLaunchesList by remember { mutableStateOf(repository.getInstantCache("cache_new_launches")) }
    var hotDealsList by remember { mutableStateOf(repository.getInstantCache("cache_hot_deals")) }
    var gamePassList by remember { mutableStateOf(repository.getInstantCache("cache_gamepass")) }
    var newsArticles by remember { mutableStateOf(newsRepository.getInstantCachedNews()) }
    var searchResults by remember { mutableStateOf<List<XboxGameDeal>>(emptyList()) }

    var xboxProfile by remember { mutableStateOf(storage.getXboxProfile()) }
    var ownedVaultMap by remember { mutableStateOf(storage.getOwnedVault()) }
    var showXboxOAuthWebView by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    var viewMode by remember { mutableStateOf(ViewLayoutMode.DETAILED) }
    var showViewDropdown by remember { mutableStateOf(false) }

    var showFilterModal by remember { mutableStateOf(false) }
    var showSortModal by remember { mutableStateOf(false) }

    var appliedDealFilter by remember { mutableStateOf("Everything") }
    var appliedGenreFilter by remember { mutableStateOf("All Genres") }
    var appliedRatingFilter by remember { mutableStateOf("Any ★") }
    var appliedMaxPriceInr by remember { mutableIntStateOf(8000) }
    var appliedSortOption by remember { mutableStateOf("Popular") }

    var compareSelection by remember { mutableStateOf<List<XboxGameDeal>>(emptyList()) }
    var showCompareDialog by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedNewsCategory by remember { mutableStateOf("All News") }

    var selectedGame by remember { mutableStateOf<XboxGameDeal?>(null) }
    var selectedArticle by remember { mutableStateOf<XboxWireArticle?>(null) }

    val activeFilterCount = remember(appliedDealFilter, appliedGenreFilter, appliedRatingFilter, appliedMaxPriceInr) {
        var count = 0
        if (appliedDealFilter != "Everything") count++
        if (appliedGenreFilter != "All Genres") count++
        if (appliedRatingFilter != "Any ★") count++
        if (appliedMaxPriceInr < 8000) count++
        count
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun refreshActiveTab(forceNetwork: Boolean = false) {
        isLoading = true
        scope.launch {
            when (activeTab) {
                0 -> repository.fetchAllXboxIndiaGames(forceNetwork).onSuccess { allGamesList = it }
                1 -> repository.fetchNewLaunchesIndia(forceNetwork).onSuccess { newLaunchesList = it }
                2 -> repository.fetchHotDealsIndia(forceNetwork).onSuccess { hotDealsList = it }
                3 -> repository.fetchGamePassIndiaCatalog(forceNetwork).onSuccess { gamePassList = it }
                4 -> newsRepository.fetchLiveXboxWireNews().onSuccess { newsArticles = it }
                5 -> {
                    xboxProfile = storage.getXboxProfile()
                    ownedVaultMap = storage.getOwnedVault()
                }
            }
            ownedVaultMap = storage.getOwnedVault()
            isLoading = false
            try { RupeeVaultWidget().updateAll(context) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(activeTab) {
        refreshActiveTab(forceNetwork = false)
    }

    LaunchedEffect(searchQuery) {
        if (activeTab >= 4) return@LaunchedEffect
        val q = searchQuery.trim()
        if (q.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        isLoading = true
        delay(220)
        repository.searchLiveXboxIndiaCatalog(q).onSuccess {
            searchResults = it
            isLoading = false
        }
    }

    fun toggleGameFavourite(productId: String) {
        val nowFav = storage.toggleFavourite(productId)
        fun updateList(list: List<XboxGameDeal>) = list.map {
            if (it.productId == productId) it.copy(isFavourite = nowFav) else it
        }
        allGamesList = updateList(allGamesList)
        newLaunchesList = updateList(newLaunchesList)
        hotDealsList = updateList(hotDealsList)
        gamePassList = updateList(gamePassList)
        searchResults = updateList(searchResults)
        selectedGame = selectedGame?.let { if (it.productId == productId) it.copy(isFavourite = nowFav) else it }
        scope.launch { try { RupeeVaultWidget().updateAll(context) } catch (_: Exception) {} }
        Toast.makeText(context, if (nowFav) "❤️ Saved to Favourites" else "Removed from Favourites", Toast.LENGTH_SHORT).show()
    }

    fun toggleCompareGame(game: XboxGameDeal) {
        compareSelection = if (compareSelection.any { it.productId == game.productId }) {
            compareSelection.filterNot { it.productId == game.productId }
        } else if (compareSelection.size >= 2) {
            listOf(compareSelection.last(), game)
        } else {
            compareSelection + game
        }
        if (compareSelection.size == 2) {
            showCompareDialog = true
        } else {
            Toast.makeText(context, "Select 1 more game to compare VS ${game.title}", Toast.LENGTH_SHORT).show()
        }
    }

    val currentTabBase = when (activeTab) {
        1 -> newLaunchesList
        2 -> hotDealsList
        3 -> gamePassList
        else -> allGamesList
    }

    val activeSourceGames = remember(currentTabBase, searchResults, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) currentTabBase
        else {
            val localInstant = currentTabBase.filter {
                it.title.contains(q, ignoreCase = true) || it.publisher.contains(q, ignoreCase = true)
            }
            (searchResults + localInstant).distinctBy { it.productId }
        }
    }

    val filteredAndSortedGames = remember(
        activeSourceGames, appliedDealFilter, appliedGenreFilter, appliedRatingFilter, appliedMaxPriceInr, appliedSortOption
    ) {
        activeSourceGames.filter { game ->
            val matchesDeal = when (appliedDealFilter) {
                "❤️ Favourites" -> game.isFavourite
                "💼 Owned" -> game.ownedPaidInr != null
                "⚠️ Leaving Soon" -> game.isLeavingGamePassSoon
                "🔥 All-Time Low" -> game.isAtAllTimeLow
                "🏷️ On Sale" -> game.discountPercent > 0
                "🆓 Free Games" -> game.currentPriceInr == 0
                "🔔 Alerts" -> game.alertTargetInr != null
                else -> true
            }
            val matchesGenre = appliedGenreFilter == "All Genres" || game.genre.equals(appliedGenreFilter, true)
            val matchesRating = when (appliedRatingFilter) {
                "4.5★ & Up" -> game.starRating >= 4.5
                "4.0★ & Up" -> game.starRating >= 4.0
                else -> true
            }
            val matchesPrice = game.currentPriceInr <= appliedMaxPriceInr
            matchesDeal && matchesGenre && matchesRating && matchesPrice
        }.let { list ->
            when (appliedSortOption) {
                "Best Discount" -> list.sortedByDescending { it.discountPercent }
                "Lowest ₹" -> list.sortedBy { if (it.currentPriceInr < 0) Int.MAX_VALUE else it.currentPriceInr }
                "Highest ₹" -> list.sortedByDescending { it.currentPriceInr }
                "Top Rated ★" -> list.sortedByDescending { it.starRating }
                else -> list
            }
        }
    }

    val filteredNews = remember(newsArticles, searchQuery, selectedNewsCategory) {
        newsArticles.filter { article ->
            val matchesSearch = searchQuery.isBlank() ||
                article.title.contains(searchQuery, ignoreCase = true) ||
                article.summary.contains(searchQuery, ignoreCase = true)
            val matchesCat = when (selectedNewsCategory) {
                "Game Pass" -> article.title.contains("Game Pass", true) || article.category.contains("Game Pass", true)
                "New Releases" -> article.title.contains("Available", true) || article.title.contains("Next Week", true) || article.category.contains("Release", true)
                "Free Play Days" -> article.title.contains("Free Play", true)
                else -> true
            }
            matchesSearch && matchesCat
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RupeeVault ", fontWeight = FontWeight.Black, fontSize = 19.sp, color = Color(0xFF10B981))
                        Text("X", fontWeight = FontWeight.Black, fontSize = 19.sp, color = Color(0xFFF59E0B))
                    }
                },
                actions = {
                    if (activeTab < 4) {
                        Box {
                            OutlinedButton(
                                onClick = { showViewDropdown = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(viewMode.label, fontSize = 11.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF10B981))
                            }
                            DropdownMenu(
                                expanded = showViewDropdown,
                                onDismissRequest = { showViewDropdown = false },
                                modifier = Modifier.background(Color(0xFF1B2230))
                            ) {
                                ViewLayoutMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = mode.label,
                                                color = if (viewMode == mode) Color(0xFF10B981) else Color.White,
                                                fontWeight = if (viewMode == mode) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            viewMode = mode
                                            showViewDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { activeTab = 5 }) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Xbox Profile & Stats",
                            tint = if (xboxProfile.isConnected) Color(0xFF10B981) else Color.Gray
                        )
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "About Developer", tint = Color(0xFFF59E0B))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF090C12))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp)
        ) {
            ScrollableTabRow(
                selectedTabIndex = activeTab,
                containerColor = Color(0xFF131823),
                contentColor = Color(0xFF10B981),
                edgePadding = 4.dp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("🎯 All Games (${allGamesList.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) })
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("🆕 New Launches (${newLaunchesList.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) })
                Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("🔥 Hot Deals (${hotDealsList.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) })
                Tab(selected = activeTab == 3, onClick = { activeTab = 3 }, text = { Text("🎮 Game Pass (${gamePassList.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) })
                Tab(selected = activeTab == 4, onClick = { activeTab = 4 }, text = { Text("📰 Xbox News (${newsArticles.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) })
                Tab(selected = activeTab == 5, onClick = { activeTab = 5 }, text = { Text("👤 Xbox Profile & Stats", fontSize = 12.sp, fontWeight = FontWeight.Bold) })
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (activeTab == 5) {
                XboxProfileAndStatsScreen(
                    profile = xboxProfile,
                    ownedEntries = ownedVaultMap.values.toList(),
                    allKnownGames = (allGamesList + hotDealsList + gamePassList).distinctBy { it.productId },
                    storage = storage,
                    isSyncing = isLoading,
                    onLaunchMicrosoftOAuth = { showXboxOAuthWebView = true },
                    onDisconnectXbox = {
                        storage.disconnectXboxProfile()
                        xboxProfile = storage.getXboxProfile()
                    },
                    onAdjustHours = { pid, delta ->
                        storage.updatePlaytimeHours(pid, delta)
                        ownedVaultMap = storage.getOwnedVault()
                    }
                )
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = if (activeTab == 4) "Search official Xbox Wire headlines..."
                            else "Search Microsoft Store India (GTA, Halo, Forza, Elden Ring)...",
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF10B981)) },
                    trailingIcon = {
                        Row {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                            IconButton(onClick = { refreshActiveTab(forceNetwork = true) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Force Refresh", tint = Color(0xFF10B981))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true
                )

                if (activeTab == 4) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("All News", "Game Pass", "New Releases", "Free Play Days").forEach { cat ->
                            FilterChip(
                                selected = selectedNewsCategory == cat,
                                onClick = { selectedNewsCategory = cat },
                                label = { Text(cat, fontSize = 12.sp) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoading && filteredNews.isEmpty()) {
                        ShimmerLoadingFeed()
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(filteredNews, key = { it.id }) { article ->
                                XboxNewsCard(article = article, onClick = { selectedArticle = article })
                            }
                        }
                    }
                } else {
                    if (searchQuery.trim().length >= 2 && activeSourceGames.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Matches:", fontSize = 11.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                            activeSourceGames.take(8).forEach { suggestion ->
                                SuggestionChip(
                                    onClick = { selectedGame = suggestion },
                                    label = {
                                        val priceTag = formatStorePriceLabel(suggestion.currentPriceInr)
                                        Text("${suggestion.title} • $priceTag", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showFilterModal = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (activeFilterCount > 0) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFF131823)
                            )
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            val filterBtnText = if (activeFilterCount > 0) "Filters ($activeFilterCount Active)" else "Filter Games"
                            Text(filterBtnText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        OutlinedButton(
                            onClick = { showSortModal = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF131823))
                        ) {
                            Icon(Icons.Default.SwapVert, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sort: $appliedSortOption", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoading && filteredAndSortedGames.isEmpty()) {
                        ShimmerLoadingFeed()
                    } else {
                        if (isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                                color = Color(0xFF10B981),
                                trackColor = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        when (viewMode) {
                            ViewLayoutMode.GRID -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 160.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(bottom = 24.dp)
                                ) {
                                    items(filteredAndSortedGames, key = { it.productId }) { game ->
                                        GameGridCard(
                                            game = game,
                                            isSelectedForCompare = compareSelection.any { it.productId == game.productId },
                                            onToggleFav = { toggleGameFavourite(game.productId) },
                                            onToggleCompare = { toggleCompareGame(game) },
                                            onClick = { selectedGame = game }
                                        )
                                    }
                                }
                            }

                            ViewLayoutMode.LIST -> {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(bottom = 24.dp)
                                ) {
                                    items(filteredAndSortedGames, key = { it.productId }) { game ->
                                        GameCompactListCard(
                                            game = game,
                                            isSelectedForCompare = compareSelection.any { it.productId == game.productId },
                                            onToggleFav = { toggleGameFavourite(game.productId) },
                                            onToggleCompare = { toggleCompareGame(game) },
                                            onClick = { selectedGame = game }
                                        )
                                    }
                                }
                            }

                            ViewLayoutMode.DETAILED -> {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    contentPadding = PaddingValues(bottom = 24.dp)
                                ) {
                                    items(filteredAndSortedGames, key = { it.productId }) { game ->
                                        GameDetailedCard(
                                            game = game,
                                            isSelectedForCompare = compareSelection.any { it.productId == game.productId },
                                            onToggleFav = { toggleGameFavourite(game.productId) },
                                            onToggleCompare = { toggleCompareGame(game) },
                                            onClick = { selectedGame = game }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // In-App Official Microsoft Xbox Live OAuth 2.0 WebView Login Dialog
        if (showXboxOAuthWebView) {
            XboxOAuthLoginWebViewDialog(
                onDismiss = { showXboxOAuthWebView = false },
                onTokenCaptured = { accessToken ->
                    showXboxOAuthWebView = false
                    isLoading = true
                    scope.launch {
                        val syncRes = authManager.authenticateAndFetchLibrary(accessToken)
                        syncRes.onSuccess { authData ->
                            storage.saveXboxProfile(
                                gamertag = authData.gamertag,
                                xuid = authData.xuid,
                                gamerscore = authData.gamerscore,
                                tier = "Xbox Live Verified"
                            )
                            // Cross-reference owned ProductIds with Microsoft DisplayCatalog IN for real INR prices
                            val validBigIds = authData.titles.mapNotNull { t ->
                                t.productId.takeIf { it.length == 12 }
                            }.distinct()

                            val pricedMap = withContext(Dispatchers.IO) {
                                val list = mutableListOf<XboxGameDeal>()
                                validBigIds.chunked(20).forEach { chunk ->
                                    list.addAll(repository.fetchCatalogByBigIds(chunk))
                                }
                                list.associateBy { it.productId }
                            }

                            authData.titles.forEach { t ->
                                val storeMatch = pricedMap[t.productId]
                                val liveInr = storeMatch?.currentPriceInr?.coerceAtLeast(0) ?: 0
                                val mrpInr = storeMatch?.mrpInr?.coerceAtLeast(liveInr) ?: liveInr
                                val hrs = (t.minutesPlayed / 60.0 * 10.0).roundToInt() / 10.0
                                val achPct = if (t.maxGamerscore > 0) ((t.currentGamerscore.toDouble() / t.maxGamerscore) * 100.0).roundToInt() else 0
                                storage.markGameOwned(
                                    productId = t.productId.ifBlank { t.titleId },
                                    title = t.name,
                                    paidInr = liveInr,
                                    mrpInr = mrpInr,
                                    hoursPlayed = hrs,
                                    achievementsPct = achPct
                                )
                            }

                            xboxProfile = storage.getXboxProfile()
                            ownedVaultMap = storage.getOwnedVault()
                            isLoading = false
                            Toast.makeText(
                                context,
                                "✅ Synced ${authData.titles.size} Xbox games for ${authData.gamertag}!",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { err ->
                            isLoading = false
                            Toast.makeText(context, "Xbox Sync Notice: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }

        if (showFilterModal) {
            var tempDeal by remember { mutableStateOf(appliedDealFilter) }
            var tempGenre by remember { mutableStateOf(appliedGenreFilter) }
            var tempRating by remember { mutableStateOf(appliedRatingFilter) }
            var tempMaxPrice by remember { mutableFloatStateOf(appliedMaxPriceInr.toFloat()) }

            ModalBottomSheet(
                onDismissRequest = { showFilterModal = false },
                containerColor = Color(0xFF131823)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🌪️ Filter Games", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)

                    Text("Deal & Ownership Status", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Everything", "🏷️ On Sale", "🔥 All-Time Low", "⚠️ Leaving Soon", "❤️ Favourites", "💼 Owned", "🔔 Alerts", "🆓 Free Games").forEach { item ->
                            FilterChip(selected = tempDeal == item, onClick = { tempDeal = item }, label = { Text(item, fontSize = 12.sp) })
                        }
                    }

                    Text("Game Genre", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All Genres", "Action & Adventure", "Role Playing", "Racing & Flying", "Shooter", "Sports").forEach { g ->
                            FilterChip(selected = tempGenre == g, onClick = { tempGenre = g }, label = { Text(g, fontSize = 12.sp) })
                        }
                    }

                    Text("Minimum User Rating", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Any ★", "4.0★ & Up", "4.5★ & Up").forEach { r ->
                            FilterChip(selected = tempRating == r, onClick = { tempRating = r }, label = { Text(r, fontSize = 12.sp) })
                        }
                    }

                    Text("Max Price Cap: ₹${tempMaxPrice.roundToInt()}", fontSize = 12.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                    Slider(
                        value = tempMaxPrice,
                        onValueChange = { tempMaxPrice = it },
                        valueRange = 500f..8000f,
                        steps = 14
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                appliedDealFilter = "Everything"
                                appliedGenreFilter = "All Genres"
                                appliedRatingFilter = "Any ★"
                                appliedMaxPriceInr = 8000
                                showFilterModal = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset All", color = Color.LightGray)
                        }
                        Button(
                            onClick = {
                                appliedDealFilter = tempDeal
                                appliedGenreFilter = tempGenre
                                appliedRatingFilter = tempRating
                                appliedMaxPriceInr = tempMaxPrice.roundToInt()
                                showFilterModal = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("Apply Filters", color = Color.Black, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        if (showSortModal) {
            var tempSort by remember { mutableStateOf(appliedSortOption) }
            ModalBottomSheet(
                onDismissRequest = { showSortModal = false },
                containerColor = Color(0xFF131823)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("⇅ Sort Games By", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
                    listOf("Popular", "Best Discount", "Lowest ₹", "Highest ₹", "Top Rated ★").forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (tempSort == option) Color(0xFF1E293B) else Color.Transparent)
                                .clickable { tempSort = option }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(option, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (tempSort == option) Color(0xFF10B981) else Color.White)
                            RadioButton(selected = tempSort == option, onClick = { tempSort = option })
                        }
                    }
                    Button(
                        onClick = {
                            appliedSortOption = tempSort
                            showSortModal = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                    ) {
                        Text("Apply Sort", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        if (showCompareDialog && compareSelection.size == 2) {
            TwoGameCompareDialog(
                gameA = compareSelection[0],
                gameB = compareSelection[1],
                onDismiss = {
                    showCompareDialog = false
                    compareSelection = emptyList()
                }
            )
        }

        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
                containerColor = Color(0xFF131823),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RupeeVault X", fontWeight = FontWeight.Black, color = Color(0xFF10B981))
                        Text(" • v6.0 Real-API", fontSize = 14.sp, color = Color.Gray)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Created & Engineered by:", fontSize = 12.sp, color = Color.Gray)
                        Text("Yasir Sheriff", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text("Salesforce Lead Consultant & Engineering Lead", fontSize = 13.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.SemiBold)
                        Text("Salem, Tamil Nadu, India", fontSize = 12.sp, color = Color.LightGray)
                        HorizontalDivider(color = Color(0xFF263042))
                        Text(
                            "100% Real Microsoft DisplayCatalog India pricing engine with parallel OkHttp connection pooling, official Microsoft Xbox OAuth 2.0 TitleHub library & playtime sync, and real INR price history snapshots.",
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAboutDialog = false }) {
                        Text("Close", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        selectedArticle?.let { article ->
            ModalBottomSheet(
                onDismissRequest = { selectedArticle = null },
                containerColor = Color(0xFF131823)
            ) {
                NewsArticleReaderSheet(
                    article = article,
                    onCheckStorePrice = { extractedQuery ->
                        selectedArticle = null
                        activeTab = 0
                        searchQuery = extractedQuery
                    }
                )
            }
        }

        selectedGame?.let { game ->
            ModalBottomSheet(
                onDismissRequest = { selectedGame = null },
                containerColor = Color(0xFF131823)
            ) {
                GameDetailAnalysisSheet(
                    game = game,
                    onToggleFav = { toggleGameFavourite(game.productId) },
                    onToggleOwned = { paidInr ->
                        if (paidInr != null) {
                            storage.markGameOwned(game.productId, game.title, paidInr, game.mrpInr)
                            Toast.makeText(context, "💼 Added to Owned Library (Paid ₹$paidInr)", Toast.LENGTH_SHORT).show()
                        } else {
                            storage.removeGameFromOwned(game.productId)
                            Toast.makeText(context, "Removed from Owned Library", Toast.LENGTH_SHORT).show()
                        }
                        ownedVaultMap = storage.getOwnedVault()
                        selectedGame = null
                        refreshActiveTab()
                    },
                    onSaveAlert = { targetInr ->
                        storage.setPriceAlert(game.productId, game.title, targetInr)
                        Toast.makeText(context, "🔔 Alert set for ₹$targetInr!", Toast.LENGTH_SHORT).show()
                        if (game.currentPriceInr in 1..targetInr) {
                            PriceAlertWorker.sendPriceDropNotification(
                                context, game.productId, game.title, game.currentPriceInr, targetInr, game.storeUrl
                            )
                        }
                        selectedGame = null
                        refreshActiveTab()
                    },
                    onRemoveAlert = {
                        storage.removePriceAlert(game.productId)
                        Toast.makeText(context, "Alert removed", Toast.LENGTH_SHORT).show()
                        selectedGame = null
                        refreshActiveTab()
                    }
                )
            }
        }
    }
}

// =========================================================================
// IN-APP MICROSOFT XBOX LIVE OAUTH 2.0 WEBVIEW LOGIN DIALOG
// =========================================================================
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XboxOAuthLoginWebViewDialog(
    onDismiss: () -> Unit,
    onTokenCaptured: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090C12))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131823))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Sign in with Microsoft Xbox", fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color(0xFF10B981))
                    Text("Official login.live.com OAuth 2.0 • Syncs Owned Games & Hours", fontSize = 11.sp, color = Color.LightGray)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (url != null && url.contains("access_token=")) {
                                    val tokenPart = url.substringAfter("access_token=").substringBefore("&")
                                    val decoded = URLDecoder.decode(tokenPart, "UTF-8")
                                    if (decoded.isNotBlank()) {
                                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                        view?.clearCache(true)
                                        onTokenCaptured(decoded)
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val u = request?.url?.toString() ?: ""
                                if (u.contains("access_token=")) {
                                    val tokenPart = u.substringAfter("access_token=").substringBefore("&")
                                    val decoded = URLDecoder.decode(tokenPart, "UTF-8")
                                    if (decoded.isNotBlank()) {
                                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                        view?.clearCache(true)
                                        onTokenCaptured(decoded)
                                        return true
                                    }
                                }
                                return false
                            }
                        }
                        loadUrl(XboxLiveAuthManager.MS_OAUTH_AUTHORIZE_URL)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// =========================================================================
// TAB 5: XBOX LIVE LOGIN, OWNED LIBRARY SPENDING & PLAYTIME ANALYTICS HUB
// =========================================================================
@Composable
fun XboxProfileAndStatsScreen(
    profile: XboxPlayerProfile,
    ownedEntries: List<OwnedVaultEntry>,
    allKnownGames: List<XboxGameDeal>,
    storage: PriceHistoryStorage,
    isSyncing: Boolean,
    onLaunchMicrosoftOAuth: () -> Unit,
    onDisconnectXbox: () -> Unit,
    onAdjustHours: (String, Double) -> Unit
) {
    val context = LocalContext.current

    val totalGamesOwned = ownedEntries.size
    val totalSpentInr = ownedEntries.sumOf { it.paidInr }
    val totalMrpInr = ownedEntries.sumOf { it.mrpInr }
    val totalSavedInr = (totalMrpInr - totalSpentInr).coerceAtLeast(0)
    val totalHoursPlayed = ownedEntries.sumOf { it.hoursPlayed }
    val costPerHourInr = if (totalHoursPlayed > 0.0) (totalSpentInr / totalHoursPlayed).roundToInt() else 0
    val completedGames = ownedEntries.count { it.achievementsPct >= 75 || it.hoursPlayed >= 25.0 }
    val backlogCount = (totalGamesOwned - completedGames).coerceAtLeast(0)
    val avgAchievementPct = if (ownedEntries.isNotEmpty()) ownedEntries.map { it.achievementsPct }.average().roundToInt() else 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131823)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (profile.isConnected) "🎮 ${profile.gamertag}" else "🎮 Official Xbox Live Account Login",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF10B981)
                        )
                        Text(
                            text = if (profile.isConnected) "XUID: ${profile.xuid} • 🏆 ${profile.gamerscore} G"
                            else "Sign in with your Microsoft Xbox credentials to automatically pull your real Xbox library, playtime hours, and INR store valuation.",
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                    }
                    if (profile.isConnected) {
                        TextButton(onClick = onDisconnectXbox) {
                            Text("Sign Out", color = Color(0xFFEF4444), fontSize = 12.sp)
                        }
                    }
                }

                if (isSyncing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = Color(0xFF10B981)
                    )
                    Text("Authenticating with Xbox Live TitleHub & fetching INR store prices...", fontSize = 11.sp, color = Color(0xFF10B981))
                }

                Button(
                    onClick = onLaunchMicrosoftOAuth,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (profile.isConnected) "Re-Sync Xbox Account Library & Playtime" else "Sign in with Microsoft Xbox Account",
                        color = Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Text("💰 Financial & Collection Valuation (INR)", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PriceStatBox("GAMES OWNED", "$totalGamesOwned", Color.White, Modifier.weight(1f))
            PriceStatBox("STORE VALUE", "₹$totalSpentInr", Color(0xFFF59E0B), Modifier.weight(1f))
            PriceStatBox("MRP VALUE", "₹$totalMrpInr", Color.LightGray, Modifier.weight(1f))
            PriceStatBox("SAVED ₹", "₹$totalSavedInr", Color(0xFF10B981), Modifier.weight(1f))
        }

        Text("⏱️ Playtime Hours & Gaming ROI Analytics", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val formattedHrs = String.format(Locale.US, "%.1f hrs", totalHoursPlayed)
            PriceStatBox("TOTAL PLAYTIME", formattedHrs, Color(0xFF10B981), Modifier.weight(1f))
            PriceStatBox("COST / HOUR", "₹$costPerHourInr/hr", Color(0xFFF59E0B), Modifier.weight(1f))
            PriceStatBox("AVG COMPLETION", "$avgAchievementPct%", Color.White, Modifier.weight(1f))
            PriceStatBox("BACKLOG", "$backlogCount Left", Color.LightGray, Modifier.weight(1f))
        }

        Text("📚 Owned Games Library, Playtime & MRP Breakdown", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        if (ownedEntries.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF131823)), shape = RoundedCornerShape(12.dp)) {
                Text(
                    "Tap 'Sign in with Microsoft Xbox Account' above to import your real Xbox game library and playtime, or tap 'Mark as Owned' inside any game sheet.",
                    modifier = Modifier.padding(14.dp),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        } else {
            ownedEntries.forEach { entry ->
                val savedOnGame = (entry.mrpInr - entry.paidInr).coerceAtLeast(0)
                val perHr = if (entry.hoursPlayed > 0) (entry.paidInr / entry.hoursPlayed).roundToInt() else entry.paidInr
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131823)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("₹${entry.paidInr} (MRP ₹${entry.mrpInr})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⏱️ ${String.format(Locale.US, "%.1f", entry.hoursPlayed)} hrs • ₹$perHr/hr • 🏆 ${entry.achievementsPct}% • Saved ₹$savedOnGame",
                                fontSize = 11.sp,
                                color = Color(0xFFF59E0B)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = { onAdjustHours(entry.productId, 2.0) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("+2h", fontSize = 10.sp, color = Color(0xFF10B981))
                                }
                            }
                        }
                        LinearProgressIndicator(
                            progress = { entry.achievementsPct / 100f },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                            color = Color(0xFF10B981),
                            trackColor = Color(0xFF1E293B)
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val csv = storage.exportWatchlistAsCsv(allKnownGames)
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "RupeeVault X Library & Playtime Backup (CSV)")
                        putExtra(Intent.EXTRA_TEXT, csv)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Export CSV"))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Text("Export CSV", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    val json = storage.exportWatchlistAsJson()
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_SUBJECT, "RupeeVault X Full Backup (JSON)")
                        putExtra(Intent.EXTRA_TEXT, json)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Export JSON"))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
            ) {
                Text("Export JSON", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TwoGameCompareDialog(gameA: XboxGameDeal, gameB: XboxGameDeal, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131823),
        title = {
            Text("⚔️ Side-by-Side Game Comparison", fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color(0xFF10B981))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(gameA.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f), maxLines = 2)
                    Text("VS", fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color(0xFFF59E0B))
                    Text(gameB.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f), maxLines = 2)
                }
                HorizontalDivider(color = Color(0xFF263042))
                CompareMetricRow("Live Price", formatStorePriceLabel(gameA.currentPriceInr), formatStorePriceLabel(gameB.currentPriceInr))
                CompareMetricRow("MSRP", "₹${gameA.mrpInr}", "₹${gameB.mrpInr}")
                CompareMetricRow("Discount", "-${gameA.discountPercent}%", "-${gameB.discountPercent}%")
                CompareMetricRow("User Score", "${gameA.starRating}★", "${gameB.starRating}★")
                CompareMetricRow("Game Pass", if (gameA.isGamePass) "Included" else "No", if (gameB.isGamePass) "Included" else "No")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun CompareMetricRow(label: String, valA: String, valB: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(valA, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981), modifier = Modifier.weight(1f))
        Text(label, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        Text(valB, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B), modifier = Modifier.weight(1f))
    }
}

@Composable
fun XboxNewsCard(article: XboxWireArticle, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131823))
    ) {
        Column {
            if (article.imageUrl.isNotBlank()) {
                Box(modifier = Modifier.fillMaxWidth().height(155.dp)) {
                    AsyncImage(
                        model = article.imageUrl,
                        contentDescription = article.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(Color(0xFF1E2636))
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC131823)))))
                    Box(modifier = Modifier.padding(10.dp).align(Alignment.TopStart)) {
                        BadgePill(article.category.uppercase(), Color(0xFF10B981), Color.Black)
                    }
                }
            }
            Column(modifier = Modifier.padding(14.dp)) {
                if (article.imageUrl.isBlank()) {
                    BadgePill(article.category.uppercase(), Color(0xFF10B981), Color.Black)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(article.title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(article.publishedDate, fontSize = 11.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.SemiBold)
                if (article.summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(article.summary, fontSize = 12.sp, color = Color.LightGray, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun NewsArticleReaderSheet(article: XboxWireArticle, onCheckStorePrice: (String) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BadgePill(article.category.uppercase(), Color(0xFF10B981), Color.Black)
            Text(article.publishedDate, fontSize = 12.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(article.title, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)

        if (article.extractedGameQuery.length >= 3) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { onCheckStorePrice(article.extractedGameQuery) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(6.dp))
                Text("🔍 Check Live ₹ Price: '${article.extractedGameQuery}'", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
        }

        if (article.imageUrl.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            AsyncImage(
                model = article.imageUrl,
                contentDescription = article.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(185.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E2636))
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(text = article.fullBody, fontSize = 14.sp, lineHeight = 21.sp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.articleUrl))) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Read Full Story on Xbox Wire", color = Color.Black, fontWeight = FontWeight.Black)
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
fun ShimmerLoadingFeed() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(animation = tween(1100, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Restart),
        label = "shimmer_x"
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF131823), Color(0xFF243044), Color(0xFF131823)),
        start = Offset(translateAnim - 300f, 0f),
        end = Offset(translateAnim, 300f)
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(4) {
            Box(modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(18.dp)).background(shimmerBrush))
        }
    }
}

@Composable
fun GameGridCard(
    game: XboxGameDeal,
    isSelectedForCompare: Boolean,
    onToggleFav: () -> Unit,
    onToggleCompare: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131823))
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(175.dp)) {
                AsyncImage(
                    model = game.coverImageUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1E2636))
                )
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC090C12)))))
                Row(modifier = Modifier.padding(8.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (game.discountPercent > 0) BadgePill("-${game.discountPercent}%", Color(0xFF10B981), Color.Black)
                    if (game.isLeavingGamePassSoon) BadgePill("⚠️ LEAVING", Color(0xFFEF4444), Color.White)
                    else if (game.isNewLaunch) BadgePill("NEW", Color(0xFFF59E0B), Color.Black)
                }
                Row(modifier = Modifier.padding(6.dp).align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onToggleCompare,
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(if (isSelectedForCompare) Color(0xFFF59E0B) else Color(0x99000000))
                    ) {
                        Text("VS", fontSize = 9.sp, fontWeight = FontWeight.Black, color = if (isSelectedForCompare) Color.Black else Color.White)
                    }
                    IconButton(
                        onClick = onToggleFav,
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0x99000000))
                    ) {
                        Icon(
                            imageVector = if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favourite",
                            tint = if (game.isFavourite) Color(0xFFEF4444) else Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                if (game.starRating > 0.0) {
                    Box(
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xDD131823)).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("${game.recommendationEmoji} ${game.starRating}★", fontSize = 10.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(game.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    val priceLabel = formatStorePriceLabel(game.currentPriceInr)
                    Text(
                        text = priceLabel,
                        fontSize = if (game.currentPriceInr < 0) 13.sp else 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (game.currentPriceInr < 0) Color(0xFFF59E0B) else Color(0xFF10B981)
                    )
                    if (game.discountPercent > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("₹${game.mrpInr}", fontSize = 11.sp, color = Color.Gray, textDecoration = TextDecoration.LineThrough)
                    }
                }
            }
        }
    }
}

@Composable
fun GameCompactListCard(
    game: XboxGameDeal,
    isSelectedForCompare: Boolean,
    onToggleFav: () -> Unit,
    onToggleCompare: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131823))
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = game.coverImageUrl,
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E2636))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(game.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ratingStr = if (game.starRating > 0.0) " • ${game.starRating}★" else ""
                Text("${game.genre}$ratingStr" + if (game.saleCountdownText.isNotBlank()) " • ${game.saleCountdownText}" else "", fontSize = 11.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Text("${game.recommendationEmoji} ${game.dealRating}", fontSize = 11.sp, color = Color(0xFFF59E0B), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    IconButton(onClick = onToggleCompare, modifier = Modifier.size(26.dp)) {
                        Text("VS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = if (isSelectedForCompare) Color(0xFFF59E0B) else Color.Gray)
                    }
                    IconButton(onClick = onToggleFav, modifier = Modifier.size(26.dp)) {
                        Icon(
                            imageVector = if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favourite",
                            tint = if (game.isFavourite) Color(0xFFEF4444) else Color.Gray,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Text(
                    text = formatStorePriceLabel(game.currentPriceInr),
                    fontSize = if (game.currentPriceInr < 0) 12.sp else 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (game.currentPriceInr < 0) Color(0xFFF59E0B) else Color(0xFF10B981)
                )
            }
        }
    }
}

@Composable
fun GameDetailedCard(
    game: XboxGameDeal,
    isSelectedForCompare: Boolean,
    onToggleFav: () -> Unit,
    onToggleCompare: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131823))
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(165.dp)) {
                AsyncImage(
                    model = game.coverImageUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1E2636))
                )
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x22000000), Color(0xEE131823)))))
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (game.discountPercent > 0) BadgePill("-${game.discountPercent}% OFF", Color(0xFF10B981), Color.Black)
                        if (game.isUnreleasedOrNoPrice) BadgePill("📅 COMING SOON", Color(0xFFF59E0B), Color.Black)
                        else if (game.isLeavingGamePassSoon) BadgePill("⚠️ LEAVING SOON", Color(0xFFEF4444), Color.White)
                        else if (game.isNewLaunch) BadgePill("🆕 NEW", Color(0xFFF59E0B), Color.Black)
                        if (game.isGamePass) BadgePill("GAME PASS", Color(0xFF107C10), Color.White)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = onToggleCompare,
                            modifier = Modifier.size(34.dp).clip(CircleShape).background(if (isSelectedForCompare) Color(0xFFF59E0B) else Color(0x99000000))
                        ) {
                            Text("VS", fontSize = 11.sp, fontWeight = FontWeight.Black, color = if (isSelectedForCompare) Color.Black else Color.White)
                        }
                        IconButton(
                            onClick = onToggleFav,
                            modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0x99000000))
                        ) {
                            Icon(
                                imageVector = if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Save Favourite",
                                tint = if (game.isFavourite) Color(0xFFEF4444) else Color.White
                            )
                        }
                    }
                }
                Column(modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    Text(game.title, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Color.White)
                    val scoreMeta = if (game.starRating > 0.0) " • ${game.starRating}★ (${game.ratingCount})" else ""
                    Text("${game.publisher} • ${game.genre}$scoreMeta", fontSize = 12.sp, color = Color.LightGray)
                }
            }

            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        val priceLabel = when {
                            game.currentPriceInr < 0 -> "COMING SOON (No INR Price Yet)"
                            game.currentPriceInr == 0 -> "FREE TO PLAY"
                            else -> "₹${game.currentPriceInr}"
                        }
                        Text(
                            text = priceLabel,
                            fontSize = if (game.currentPriceInr < 0) 15.sp else 22.sp,
                            fontWeight = FontWeight.Black,
                            color = if (game.currentPriceInr < 0) Color(0xFFF59E0B) else Color(0xFF10B981)
                        )
                        if (game.discountPercent > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("₹${game.mrpInr}", fontSize = 14.sp, color = Color.Gray, textDecoration = TextDecoration.LineThrough)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (game.saleCountdownText.isNotBlank()) {
                            Text(game.saleCountdownText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                        } else if (game.lowestEverInr > 0) {
                            Text("Recorded Low: ₹${game.lowestEverInr}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        }
                        Text(game.saleCadenceNote, fontSize = 10.sp, color = Color.Gray)
                    }
                }

                if (game.screenshotUrls.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(game.screenshotUrls) { shotUrl ->
                            AsyncImage(
                                model = shotUrl,
                                contentDescription = "Screenshot",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(130.dp).height(74.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E2636))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1D2432)).padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${game.recommendationEmoji} ${game.dealRating}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (game.isAtAllTimeLow) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }
            }
        }
    }
}

@Composable
fun BadgePill(text: String, bgColor: Color, textColor: Color) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(bgColor).padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = textColor, fontWeight = FontWeight.Black, fontSize = 10.sp)
    }
}

@Composable
fun GameDetailAnalysisSheet(
    game: XboxGameDeal,
    onToggleFav: () -> Unit,
    onToggleOwned: (Int?) -> Unit,
    onSaveAlert: (Int) -> Unit,
    onRemoveAlert: () -> Unit
) {
    val context = LocalContext.current
    var playTrailer by remember { mutableStateOf(false) }
    var selectedTimeframe by remember { mutableStateOf("1Y") }
    var targetPrice by remember { mutableIntStateOf(game.alertTargetInr ?: game.lowestEverInr.coerceAtLeast(500)) }
    var lightboxIndex by remember { mutableStateOf<Int?>(null) }

    var selectedCashbackPct by remember { mutableIntStateOf(0) }
    val effectiveInr = if (game.currentPriceInr > 0) ((game.currentPriceInr * (100 - selectedCashbackPct)) / 100.0).roundToInt() else 0

    val activePoints = when (selectedTimeframe) {
        "1M" -> game.monthHistory
        "ALL" -> game.allTimeHistory
        else -> game.yearHistory
    }

    val tfLow = activePoints.filter { it.priceInr > 0 }.minOfOrNull { it.priceInr } ?: game.lowestEverInr
    val tfHigh = activePoints.filter { it.priceInr > 0 }.maxOfOrNull { it.priceInr } ?: game.mrpInr
    val tfAvg = if (activePoints.isNotEmpty()) activePoints.map { it.priceInr.coerceAtLeast(0) }.average().roundToInt() else game.currentPriceInr.coerceAtLeast(0)

    if (lightboxIndex != null && game.screenshotUrls.isNotEmpty()) {
        val currentIdx = lightboxIndex!!.coerceIn(0, game.screenshotUrls.lastIndex)
        Dialog(onDismissRequest = { lightboxIndex = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xF205070B)).padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Screenshot ${currentIdx + 1} of ${game.screenshotUrls.size}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    IconButton(onClick = { lightboxIndex = null }) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    AsyncImage(model = game.screenshotUrls[currentIdx], contentDescription = "Full", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(
                            onClick = { lightboxIndex = if (currentIdx > 0) currentIdx - 1 else game.screenshotUrls.lastIndex },
                            modifier = Modifier.clip(CircleShape).background(Color(0xAA000000))
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev", tint = Color(0xFF10B981)) }
                        IconButton(
                            onClick = { lightboxIndex = if (currentIdx < game.screenshotUrls.lastIndex) currentIdx + 1 else 0 },
                            modifier = Modifier.clip(CircleShape).background(Color(0xAA000000))
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", tint = Color(0xFF10B981)) }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    itemsIndexed(game.screenshotUrls) { idx, thumbUrl ->
                        val isSel = idx == currentIdx
                        AsyncImage(
                            model = thumbUrl,
                            contentDescription = "Thumb",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(90.dp).height(54.dp).clip(RoundedCornerShape(8.dp))
                                .border(if (isSel) 2.5.dp else 0.dp, if (isSel) Color(0xFF10B981) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { lightboxIndex = idx }
                        )
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(game.title, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("${game.publisher} • ${game.genre} • ${game.releaseDate}", color = Color.Gray, fontSize = 12.sp)
            }
            IconButton(onClick = {
                val shareMsg = buildString {
                    appendLine("🎮 *${game.title}* on Microsoft Store India!")
                    appendLine("💰 Live Price: *${formatStorePriceLabel(game.currentPriceInr)}* (MRP ₹${game.mrpInr})")
                    appendLine("${game.recommendationEmoji} ${game.dealRating}")
                    append("🔗 ${game.storeUrl}")
                }
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareMsg)
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share via WhatsApp / Telegram"))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share Deal", tint = Color(0xFF10B981))
            }
            IconButton(onClick = onToggleFav) {
                Icon(
                    imageVector = if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favourite",
                    tint = if (game.isFavourite) Color(0xFFEF4444) else Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            if (playTrailer && game.trailerUrl.isNotBlank()) {
                InAppTrailerPlayer(videoUrl = game.trailerUrl)
            } else {
                AsyncImage(model = game.coverImageUrl, contentDescription = game.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (game.trailerUrl.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0x66000000)).clickable { playTrailer = true }, contentAlignment = Alignment.Center) {
                        Row(
                            modifier = Modifier.clip(CircleShape).background(Color(0xFF10B981)).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play Trailer", tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Watch Official Store Trailer", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        if (game.screenshotUrls.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Screenshots (Tap to view one by one)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(game.screenshotUrls) { index, shot ->
                    AsyncImage(
                        model = shot,
                        contentDescription = "Screenshot ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(175.dp).height(100.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E2636)).clickable { lightboxIndex = index }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("📈 Recorded Microsoft API Price History", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1M" to "Month", "1Y" to "Year", "ALL" to "All-Time").forEach { (code, label) ->
                    FilterChip(selected = selectedTimeframe == code, onClick = { selectedTimeframe = code }, label = { Text(label, fontSize = 11.sp) })
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (game.isUnreleasedOrNoPrice) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "📅 Unreleased / Pre-Order Not Priced Yet: Microsoft Store India has not published an INR Purchase SKU for ${game.title} yet.",
                    modifier = Modifier.padding(14.dp),
                    fontSize = 12.sp,
                    color = Color(0xFFF59E0B)
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriceStatBox("RECORDED LOW", "₹$tfLow", Color(0xFF10B981), Modifier.weight(1f))
                PriceStatBox("AVERAGE", "₹$tfAvg", Color(0xFFF59E0B), Modifier.weight(1f))
                PriceStatBox("MSRP HIGH", "₹$tfHigh", Color.LightGray, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
            InteractivePriceHistoryChart(points = activePoints, lowestPrice = tfLow, maxPrice = tfHigh)
        }

        if (game.currentPriceInr > 0) {
            Spacer(modifier = Modifier.height(14.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("💳 India Gift Card / Bank Cashback Calculator", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Store ₹", 5 to "5% Gift Card", 8 to "8% Paytm/UniPin", 10 to "10% Bank Offer").forEach { (pct, label) ->
                            FilterChip(selected = selectedCashbackPct == pct, onClick = { selectedCashbackPct = pct }, label = { Text(label, fontSize = 11.sp) })
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Net Effective Cost: ₹$effectiveInr (Total Savings vs MRP: ₹${(game.mrpInr - effectiveInr).coerceAtLeast(0)})",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        color = Color(0xFF10B981)
                    )
                }
            }
        }

        if (game.editions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📦 Official Microsoft Store IN Editions & SKUs", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color.White)
                    game.editions.forEach { ed ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ed.skuTitle, fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = if (ed.discountPercent > 0) "₹${ed.priceInr} (-${ed.discountPercent}%)" else "₹${ed.priceInr}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0E131D)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🔔 Target Price Drop Alert", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton(onClick = { targetPrice = (targetPrice - 100).coerceAtLeast(100) }) { Icon(Icons.Default.Remove, contentDescription = "Decrease") }
                    Text("₹$targetPrice", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color(0xFFF59E0B))
                    OutlinedIconButton(onClick = { targetPrice = (targetPrice + 100).coerceAtMost(maxOf(game.mrpInr, 5000)) }) { Icon(Icons.Default.Add, contentDescription = "Increase") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSaveAlert(targetPrice) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Notify @ ₹$targetPrice", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    if (game.alertTargetInr != null) {
                        OutlinedIconButton(onClick = onRemoveAlert) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red) }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                if (game.ownedPaidInr != null) onToggleOwned(null) else onToggleOwned(effectiveInr)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = if (game.ownedPaidInr != null) Color(0xFF10B981) else Color.Gray)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (game.ownedPaidInr != null) "💼 Owned in Library (Paid ₹${game.ownedPaidInr}) — Tap to Remove" else "💼 Mark as Owned in Library (@ ₹$effectiveInr)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(game.storeUrl))) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            val btnLabel = when {
                game.currentPriceInr < 0 -> "View Coming Soon Page on Xbox Store IN"
                game.currentPriceInr == 0 -> "Get Free on Xbox Store India"
                else -> "Buy on Xbox Store India (₹${game.currentPriceInr})"
            }
            Text(btnLabel, color = Color.Black, fontWeight = FontWeight.Black)
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
fun InAppTrailerPlayer(videoUrl: String) {
    val context = LocalContext.current
    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun PriceStatBox(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF0E131D)).padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, fontSize = 13.sp, color = valueColor, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
fun InteractivePriceHistoryChart(points: List<PricePoint>, lowestPrice: Int, maxPrice: Int) {
    var selectedIdx by remember(points) { mutableIntStateOf((points.size - 1).coerceAtLeast(0)) }
    val activePt = points.getOrNull(selectedIdx)

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E131D)).padding(12.dp)
    ) {
        if (activePt != null) {
            Text("Recorded Snapshot: ${activePt.label} → ₹${activePt.priceInr}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
            Spacer(modifier = Modifier.height(8.dp))
        }
        Canvas(
            modifier = Modifier.fillMaxWidth().height(130.dp).pointerInput(points) {
                detectTapGestures { tapOffset ->
                    if (points.size > 1) {
                        val step = size.width.toFloat() / (points.size - 1)
                        selectedIdx = (tapOffset.x / step).roundToInt().coerceIn(0, points.lastIndex)
                    }
                }
            }
        ) {
            if (points.isEmpty()) return@Canvas
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            val range = (maxPrice - lowestPrice).coerceAtLeast(100)

            drawLine(
                color = Color(0xFF10B981).copy(alpha = 0.45f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )

            val path = Path()
            points.forEachIndexed { idx, pt ->
                val x = idx * stepX
                val y = ((1f - ((pt.priceInr - lowestPrice).toFloat() / range)) * size.height).coerceIn(8f, size.height - 8f)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                val isSelected = idx == selectedIdx
                drawCircle(
                    color = if (isSelected) Color(0xFFF59E0B) else Color(0xFF10B981),
                    radius = if (isSelected) 11f else 7f,
                    center = Offset(x, y)
                )
            }
            drawPath(path = path, color = Color(0xFF10B981), style = Stroke(width = 4f))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            points.forEach { Text(it.label, fontSize = 10.sp, color = Color.Gray) }
        }
    }
}
