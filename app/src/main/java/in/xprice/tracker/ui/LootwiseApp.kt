package `in`.xprice.tracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.xprice.tracker.data.CatalogKey
import `in`.xprice.tracker.data.PriceHistoryStorage
import `in`.xprice.tracker.data.ViewLayoutMode
import `in`.xprice.tracker.data.XboxGameDeal
import `in`.xprice.tracker.data.XboxWireArticle
import `in`.xprice.tracker.data.XboxLiveAuthManager
import `in`.xprice.tracker.ui.components.EmptyState
import `in`.xprice.tracker.ui.components.ShimmerFeed
import `in`.xprice.tracker.ui.screens.*
import `in`.xprice.tracker.ui.theme.Lw

private data class NavItem(val dest: Destination, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val NAV_ITEMS = listOf(
    NavItem(Destination.EXPLORE, "Explore", Icons.Outlined.Explore, Icons.Filled.Explore),
    NavItem(Destination.DEALS, "Deals", Icons.Outlined.LocalOffer, Icons.Filled.LocalOffer),
    NavItem(Destination.GAME_PASS, "Game Pass", Icons.Outlined.SportsEsports, Icons.Filled.SportsEsports),
    NavItem(Destination.NEWS, "News", Icons.Outlined.Newspaper, Icons.Filled.Newspaper),
    NavItem(Destination.LIBRARY, "Library", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LootwiseApp(vm: MainViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var showFilters by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showSignIn by remember { mutableStateOf(false) }
    var showCompare by remember { mutableStateOf(false) }
    var selectedArticle by remember { mutableStateOf<XboxWireArticle?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = Lw.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar(vm, onAbout = { showAbout = true }) },
        bottomBar = {
            Column {
                CompareBar(vm.compareSelection, onCompare = { showCompare = true }, onClear = vm::clearCompare)
                NavigationBar(containerColor = Lw.Surface, tonalElevation = 0.dp) {
                    NAV_ITEMS.forEach { item ->
                        val selected = vm.destination == item.dest
                        NavigationBarItem(
                            selected = selected,
                            onClick = { vm.navigate(item.dest) },
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = item.label) },
                            label = { Text(item.label, fontSize = 11.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Lw.OnAccent,
                                indicatorColor = Lw.Green,
                                selectedTextColor = Lw.Green,
                                unselectedIconColor = Lw.TextSecondary,
                                unselectedTextColor = Lw.TextSecondary
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when (vm.destination) {
                Destination.LIBRARY -> LibraryScreen(
                    profile = vm.profile,
                    library = vm.library,
                    syncState = vm.syncState,
                    onSignIn = { if (vm.beginXboxSignIn()) showSignIn = true },
                    onSignOut = vm::signOutXbox,
                    onDismissError = vm::dismissSyncError,
                    onExportCsv = { vm.storage.exportWatchlistAsCsv(vm.allKnownGames()) },
                    onExportJson = { vm.storage.exportWatchlistAsJson() }
                )
                Destination.NEWS -> {
                    SearchField(vm.searchQuery, "Search Xbox Wire", vm::updateSearch, vm::clearSearch)
                    Spacer(Modifier.height(8.dp))
                    PullToRefreshBox(isRefreshing = vm.newsLoading, onRefresh = vm::refreshNews, modifier = Modifier.weight(1f)) {
                        NewsScreen(
                            articles = filterNews(vm.news, vm.searchQuery, vm.newsCategory),
                            loading = vm.newsLoading,
                            category = vm.newsCategory,
                            onCategory = { vm.newsCategory = it },
                            onOpen = { selectedArticle = it }
                        )
                    }
                }
                else -> CatalogContent(vm, onFilters = { showFilters = true }, onSort = { showSort = true })
            }
        }
    }

    // ---------------- Overlays ----------------
    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }, containerColor = Lw.Surface) {
            FilterSheetContent(vm.filters, vm.availableGenres()) { vm.filters = it; showFilters = false }
        }
    }
    if (showSort) {
        ModalBottomSheet(onDismissRequest = { showSort = false }, containerColor = Lw.Surface) {
            SortSheetContent(vm.sort) { vm.sort = it; showSort = false }
        }
    }
    vm.selectedGameId?.let { id ->
        val game = vm.findGame(id)
        if (game == null) {
            LaunchedEffect(id) { vm.selectedGameId = null }
        } else {
            ModalBottomSheet(
                onDismissRequest = { vm.selectedGameId = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Lw.Surface
            ) {
                GameDetailSheet(
                    game = game,
                    onToggleFav = { vm.toggleFavourite(game.productId) },
                    onSetOwned = { vm.setOwned(game, it) },
                    onSaveAlert = { vm.setAlert(game, it) },
                    onRemoveAlert = { vm.removeAlert(game.productId) }
                )
            }
        }
    }
    selectedArticle?.let { article ->
        ModalBottomSheet(onDismissRequest = { selectedArticle = null }, containerColor = Lw.Surface) {
            NewsReaderSheet(article) { query ->
                selectedArticle = null
                vm.navigate(Destination.EXPLORE)
                vm.updateSearch(query)
            }
        }
    }
    if (showCompare && vm.compareSelection.size == 2) {
        CompareDialog(vm.compareSelection[0], vm.compareSelection[1]) { showCompare = false }
    }
    val session = vm.oauthSession
    if (showSignIn && session != null) {
        XboxSignInDialog(
            authorizeUrl = XboxLiveAuthManager.authorizeUrl(session),
            parseRedirect = { XboxLiveAuthManager.parseRedirect(it, session) },
            onDismiss = { showSignIn = false; vm.cancelXboxSignIn() },
            onCodeCaptured = { showSignIn = false; vm.onXboxCodeCaptured(it) },
            onError = { showSignIn = false; vm.onXboxSignInError(it) }
        )
    }
    if (showAbout) AboutDialog { showAbout = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(vm: MainViewModel, onAbout: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Lw.Green),
                    contentAlignment = Alignment.Center
                ) { Text("₹", color = Lw.OnAccent, fontWeight = FontWeight.Black, fontSize = 16.sp) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(PriceHistoryStorage.APP_NAME, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Lw.TextPrimary)
                    Text("Xbox prices for India", fontSize = 11.sp, color = Lw.TextSecondary)
                }
            }
        },
        actions = {
            if (vm.activeCatalogKey != null) {
                val (icon, next) = when (vm.viewMode) {
                    ViewLayoutMode.DETAILED -> Icons.Default.GridView to ViewLayoutMode.GRID
                    ViewLayoutMode.GRID -> Icons.AutoMirrored.Filled.ViewList to ViewLayoutMode.LIST
                    ViewLayoutMode.LIST -> Icons.Default.ViewAgenda to ViewLayoutMode.DETAILED
                }
                IconButton(onClick = { vm.viewMode = next }) { Icon(icon, "Switch to ${next.label} view", tint = Lw.TextSecondary) }
            }
            IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, "About", tint = Lw.TextSecondary) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Lw.Bg)
    )
}

@Composable
private fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit, onClear: () -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = Lw.TextMuted, fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Lw.TextSecondary) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Clear", tint = Lw.TextSecondary) }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Lw.Surface,
            unfocusedContainerColor = Lw.Surface,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            cursorColor = Lw.Green
        ),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.CatalogContent(vm: MainViewModel, onFilters: () -> Unit, onSort: () -> Unit) {
    val key = vm.activeCatalogKey ?: return
    val games = vm.visibleGames()
    val loading = vm.isLoading(key) || vm.searchLoading

    SearchField(vm.searchQuery, "Search Microsoft Store India", vm::updateSearch, vm::clearSearch)
    Spacer(Modifier.height(10.dp))

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.destination == Destination.EXPLORE) {
            listOf(CatalogKey.ALL to "Popular", CatalogKey.NEW to "New").forEach { (k, label) ->
                FilterChip(
                    selected = vm.exploreFeed == k,
                    onClick = { vm.selectExploreFeed(k) },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lw.Green, selectedLabelColor = Lw.OnAccent)
                )
            }
        }
        val fc = vm.filters.activeCount
        FilterChip(
            selected = fc > 0,
            onClick = onFilters,
            label = { Text(if (fc > 0) "Filters · $fc" else "Filters", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp)) }
        )
        FilterChip(
            selected = vm.sort != SortOption.POPULAR,
            onClick = onSort,
            label = { Text(vm.sort.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(16.dp)) },
            modifier = Modifier.weight(1f, fill = false)
        )
    }

    Text(
        buildString {
            append("${games.size} games")
            val ts = vm.lastUpdated(key)
            if (ts > 0) append(" · updated ${formatAgo(ts)}")
        },
        color = Lw.TextMuted, fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 4.dp)
    )

    PullToRefreshBox(isRefreshing = loading && games.isNotEmpty(), onRefresh = vm::refreshCurrent, modifier = Modifier.weight(1f)) {
        when {
            loading && games.isEmpty() -> ShimmerFeed()
            games.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    if (vm.loadError != null && vm.catalogs[key].isNullOrEmpty()) {
                        EmptyState(Icons.Default.CloudOff, "Couldn't load games", vm.loadError ?: "", "Try again") { vm.refreshCurrent() }
                    } else {
                        EmptyState(
                            Icons.Default.SearchOff, "Nothing matches",
                            "Try a different search or clear your filters.",
                            if (vm.filters.activeCount > 0) "Clear filters" else null
                        ) { vm.filters = FilterState() }
                    }
                }
            }
            else -> GameList(vm, games)
        }
    }
}

@Composable
private fun GameList(vm: MainViewModel, games: List<XboxGameDeal>) {
    val compareIds = vm.compareSelection.map { it.productId }.toSet()
    val bottom = PaddingValues(bottom = 24.dp)
    when (vm.viewMode) {
        ViewLayoutMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = bottom,
            modifier = Modifier.fillMaxSize()
        ) {
            items(games, key = { it.productId }) { g ->
                GameGridCard(g, g.productId in compareIds, { vm.toggleFavourite(g.productId) }, { vm.toggleCompare(g) }) { vm.openGame(g.productId) }
            }
        }
        ViewLayoutMode.LIST -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = bottom, modifier = Modifier.fillMaxSize()) {
            items(games, key = { it.productId }) { g ->
                GameListRow(g, g.productId in compareIds, { vm.toggleFavourite(g.productId) }, { vm.toggleCompare(g) }) { vm.openGame(g.productId) }
            }
        }
        ViewLayoutMode.DETAILED -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = bottom, modifier = Modifier.fillMaxSize()) {
            items(games, key = { it.productId }) { g ->
                GameDetailedCard(g, g.productId in compareIds, { vm.toggleFavourite(g.productId) }, { vm.toggleCompare(g) }) { vm.openGame(g.productId) }
            }
        }
    }
}

@Composable
private fun CompareBar(selection: List<XboxGameDeal>, onCompare: () -> Unit, onClear: () -> Unit) {
    AnimatedVisibility(visible = selection.isNotEmpty(), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Lw.SurfaceHigh)
                .border(1.dp, Lw.Amber.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selection.joinToString("  vs  ") { it.title } + if (selection.size == 1) "  vs  …" else "",
                color = Lw.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Clear comparison", tint = Lw.TextSecondary) }
            Button(
                onClick = onCompare,
                enabled = selection.size == 2,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lw.Amber, contentColor = Lw.OnAccent)
            ) { Text("Compare", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lw.Surface,
        icon = {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Lw.Green), contentAlignment = Alignment.Center) {
                Text("₹", color = Lw.OnAccent, fontWeight = FontWeight.Black, fontSize = 24.sp)
            }
        },
        title = { Text(PriceHistoryStorage.APP_NAME, color = Lw.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Smart Xbox price tracker for India", color = Lw.TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Built by Yasir Sheriff", color = Lw.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("Salem, Tamil Nadu", color = Lw.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Live prices from Microsoft Store India. Price history is recorded on your device. Not affiliated with Microsoft or Xbox.",
                    color = Lw.TextMuted, fontSize = 11.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Lw.Green) } }
    )
}

private fun formatAgo(ms: Long): String {
    val mins = (System.currentTimeMillis() - ms) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}
