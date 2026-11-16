package `in`.xprice.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import `in`.xprice.tracker.data.*
import `in`.xprice.tracker.worker.PriceAlertWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scheduleBackgroundPriceCheck()

        setContent {
            val darkPalette = darkColorScheme(
                primary = Color(0xFF22C55E),
                secondary = Color(0xFFF59E0B),
                background = Color(0xFF0D0E12),
                surface = Color(0xFF171920)
            )
            MaterialTheme(colorScheme = darkPalette) {
                XPriceMainScreen()
            }
        }
    }

    private fun scheduleBackgroundPriceCheck() {
        val workRequest = PeriodicWorkRequestBuilder<PriceAlertWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "xprice_india_background_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XPriceMainScreen() {
    val context = LocalContext.current
    val repository = remember { XboxStoreRepository(context) }
    val storage = remember { PriceHistoryStorage(context) }
    val scope = rememberCoroutineScope()

    // 0 = Store Deals Feed, 1 = Dedicated Xbox Game Pass India Feed
    var activeFeedTab by remember { mutableIntStateOf(0) }

    var storeCatalog by remember { mutableStateOf(repository.getInstantOfflineCache()) }
    var gamePassCatalog by remember { mutableStateOf(repository.getInstantOfflineGamePassCache()) }
    var searchResults by remember { mutableStateOf<List<XboxGameDeal>>(emptyList()) }
    var isSyncing by remember { mutableStateOf(true) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedDealFilter by remember { mutableStateOf("All Deals") }
    var selectedGenre by remember { mutableStateOf("All Genres") }
    var selectedRatingFilter by remember { mutableStateOf("Any ★") }
    var selectedSort by remember { mutableStateOf("Best Discount") }
    var viewMode by remember { mutableStateOf(ViewLayoutMode.DETAILED) }
    var selectedGame by remember { mutableStateOf<XboxGameDeal?>(null) }

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

    fun syncActiveCatalog() {
        isSyncing = true
        scope.launch {
            if (activeFeedTab == 0) {
                repository.fetchLiveIndiaDeals().onSuccess {
                    storeCatalog = it
                    isSyncing = false
                }
            } else {
                repository.fetchGamePassIndiaCatalog().onSuccess {
                    gamePassCatalog = it
                    isSyncing = false
                }
            }
        }
    }

    // Load live feed whenever user switches between Store Deals and Game Pass India tabs
    LaunchedEffect(activeFeedTab) {
        syncActiveCatalog()
    }

    // Instant Type-Ahead Search
    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length < 2) {
            searchResults = emptyList()
            isSyncing = false
            return@LaunchedEffect
        }
        isSyncing = true
        delay(250)
        repository.searchLiveXboxIndiaCatalog(q).onSuccess {
            searchResults = it
            isSyncing = false
        }
    }

    fun toggleGameFavourite(productId: String) {
        val nowFav = storage.toggleFavourite(productId)
        storeCatalog = storeCatalog.map { if (it.productId == productId) it.copy(isFavourite = nowFav) else it }
        gamePassCatalog = gamePassCatalog.map { if (it.productId == productId) it.copy(isFavourite = nowFav) else it }
        searchResults = searchResults.map { if (it.productId == productId) it.copy(isFavourite = nowFav) else it }
        selectedGame = selectedGame?.let { if (it.productId == productId) it.copy(isFavourite = nowFav) else it }
        Toast.makeText(
            context,
            if (nowFav) "❤️ Saved to Favourites" else "Removed from Favourites",
            Toast.LENGTH_SHORT
        ).show()
    }

    val currentTabBaseList = if (activeFeedTab == 0) storeCatalog else gamePassCatalog

    val activeSourceGames = remember(currentTabBaseList, searchResults, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            currentTabBaseList
        } else {
            val localInstant = currentTabBaseList.filter {
                it.title.contains(q, ignoreCase = true) || it.publisher.contains(q, ignoreCase = true)
            }
            (searchResults + localInstant).distinctBy { it.productId }
        }
    }

    val filteredAndSortedGames = remember(
        activeSourceGames, selectedDealFilter, selectedGenre, selectedRatingFilter, selectedSort
    ) {
        activeSourceGames.filter { game ->
            val matchesDeal = when (selectedDealFilter) {
                "❤️ Favourites" -> game.isFavourite
                "🔥 All-Time Low" -> game.isAtAllTimeLow
                "🔔 My Alerts" -> game.alertTargetInr != null
                "Under ₹1,500" -> game.currentPriceInr <= 1500
                "On Sale Only" -> game.discountPercent > 0
                else -> true
            }
            val matchesGenre = selectedGenre == "All Genres" || game.genre.equals(selectedGenre, true)
            val matchesRating = when (selectedRatingFilter) {
                "4.5★ & Up" -> game.starRating >= 4.5
                "4.0★ & Up" -> game.starRating >= 4.0
                else -> true
            }
            matchesDeal && matchesGenre && matchesRating
        }.let { list ->
            when (selectedSort) {
                "Lowest ₹" -> list.sortedBy { it.currentPriceInr }
                "Top Rated ★" -> list.sortedByDescending { it.starRating }
                else -> list.sortedByDescending { it.discountPercent }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("XPrice ", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Color(0xFF22C55E))
                        Text("INDIA ₹ (${currentTabBaseList.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewMode = ViewLayoutMode.GRID }) {
                        Icon(
                            Icons.Default.GridView,
                            contentDescription = "Grid View",
                            tint = if (viewMode == ViewLayoutMode.GRID) Color(0xFF22C55E) else Color.Gray
                        )
                    }
                    IconButton(onClick = { viewMode = ViewLayoutMode.LIST }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = "Compact List View",
                            tint = if (viewMode == ViewLayoutMode.LIST) Color(0xFF22C55E) else Color.Gray
                        )
                    }
                    IconButton(onClick = { viewMode = ViewLayoutMode.DETAILED }) {
                        Icon(
                            Icons.Default.ViewAgenda,
                            contentDescription = "Detailed List View",
                            tint = if (viewMode == ViewLayoutMode.DETAILED) Color(0xFF22C55E) else Color.Gray
                        )
                    }
                    IconButton(onClick = { syncActiveCatalog() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync Live Prices", tint = Color(0xFF22C55E))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0E12))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp)
        ) {
            // Primary Feed Switcher: Store Deals vs Dedicated Game Pass India Catalog
            TabRow(
                selectedTabIndex = activeFeedTab,
                containerColor = Color(0xFF171920),
                contentColor = Color(0xFF22C55E),
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = activeFeedTab == 0,
                    onClick = { activeFeedTab = 0 },
                    text = {
                        Text(
                            text = "🔥 Store Deals (${storeCatalog.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                )
                Tab(
                    selected = activeFeedTab == 1,
                    onClick = { activeFeedTab = 1 },
                    text = {
                        Text(
                            text = "🎮 Game Pass (${gamePassCatalog.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = if (activeFeedTab == 0) "Search all Xbox India games (Halo, Forza, FIFA)..."
                        else "Filter or search Game Pass India titles...",
                        fontSize = 14.sp
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF22C55E)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )

            if (isSyncing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Color(0xFF22C55E),
                    trackColor = Color.Transparent
                )
            } else {
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Live Type-Ahead Autocomplete Suggestions Strip
            if (searchQuery.trim().length >= 2 && activeSourceGames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Matches:", fontSize = 11.sp, color = Color(0xFF22C55E), fontWeight = FontWeight.Bold)
                    activeSourceGames.take(8).forEach { suggestion ->
                        SuggestionChip(
                            onClick = { selectedGame = suggestion },
                            label = {
                                Text(
                                    text = "${suggestion.title} • ₹${suggestion.currentPriceInr}",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Filter Row 1
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All Deals", "On Sale Only", "❤️ Favourites", "🔥 All-Time Low", "🔔 My Alerts", "Under ₹1,500").forEach { filter ->
                    FilterChip(
                        selected = selectedDealFilter == filter,
                        onClick = { selectedDealFilter = filter },
                        label = { Text(filter, fontSize = 12.sp) }
                    )
                }
            }

            // Filter Row 2
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("All Genres", "Action & Adventure", "Role Playing", "Racing & Flying", "Shooter").forEach { genre ->
                    FilterChip(
                        selected = selectedGenre == genre,
                        onClick = { selectedGenre = genre },
                        label = { Text(genre, fontSize = 11.sp) }
                    )
                }
                listOf("Any ★", "4.5★ & Up").forEach { r ->
                    FilterChip(
                        selected = selectedRatingFilter == r,
                        onClick = { selectedRatingFilter = r },
                        label = { Text(r, fontSize = 11.sp) }
                    )
                }
                listOf("Best Discount", "Lowest ₹", "Top Rated ★").forEach { s ->
                    FilterChip(
                        selected = selectedSort == s,
                        onClick = { selectedSort = s },
                        label = { Text("Sort: $s", fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredAndSortedGames.isEmpty() && !isSyncing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No games matched your current filters.", color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            searchQuery = ""
                            selectedDealFilter = "All Deals"
                            selectedGenre = "All Genres"
                            selectedRatingFilter = "Any ★"
                        }) {
                            Text("Reset Search & Filters", color = Color(0xFF22C55E))
                        }
                    }
                }
            } else {
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
                                    onToggleFav = { toggleGameFavourite(game.productId) },
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
                                    onToggleFav = { toggleGameFavourite(game.productId) },
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
                                    onToggleFav = { toggleGameFavourite(game.productId) },
                                    onClick = { selectedGame = game }
                                )
                            }
                        }
                    }
                }
            }
        }

        selectedGame?.let { game ->
            ModalBottomSheet(
                onDismissRequest = { selectedGame = null },
                containerColor = Color(0xFF171920)
            ) {
                GameDetailAnalysisSheet(
                    game = game,
                    onToggleFav = { toggleGameFavourite(game.productId) },
                    onSaveAlert = { targetInr ->
                        storage.setPriceAlert(game.productId, game.title, targetInr)
                        Toast.makeText(context, "🔔 Alert set for ₹$targetInr!", Toast.LENGTH_SHORT).show()
                        if (game.currentPriceInr <= targetInr) {
                            PriceAlertWorker.sendPriceDropNotification(
                                context, game.productId, game.title, game.currentPriceInr, targetInr, game.storeUrl
                            )
                        }
                        selectedGame = null
                        syncActiveCatalog()
                    },
                    onRemoveAlert = {
                        storage.removePriceAlert(game.productId)
                        Toast.makeText(context, "Alert removed", Toast.LENGTH_SHORT).show()
                        selectedGame = null
                        syncActiveCatalog()
                    }
                )
            }
        }
    }
}

@Composable
fun GameGridCard(game: XboxGameDeal, onToggleFav: () -> Unit, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171920))
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(175.dp)) {
                AsyncImage(
                    model = game.coverImageUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF232631))
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0D0E12)))
                    )
                )
                Row(
                    modifier = Modifier.padding(8.dp).align(Alignment.TopStart),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (game.discountPercent > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF22C55E))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("-${game.discountPercent}%", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 10.sp)
                        }
                    }
                    if (game.isGamePass) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF107C10))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("GAME PASS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp)
                        }
                    }
                }
                IconButton(
                    onClick = onToggleFav,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(32.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(Color(0x99000000))
                ) {
                    Icon(
                        imageVector = if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favourite",
                        tint = if (game.isFavourite) Color(0xFFEF4444) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xDD171920))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${game.recommendationEmoji} ${if (game.isAtAllTimeLow) "Record Low" else "${game.starRating}★"}",
                        fontSize = 11.sp,
                        color = Color(0xFFF59E0B),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(game.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("₹${game.currentPriceInr}", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF22C55E))
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
fun GameCompactListCard(game: XboxGameDeal, onToggleFav: () -> Unit, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171920))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = game.coverImageUrl,
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF232631))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(game.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${game.genre} • ${game.starRating}★" + if (game.isGamePass) " • Game Pass" else "",
                    fontSize = 11.sp,
                    color = if (game.isGamePass) Color(0xFF22C55E) else Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("${game.recommendationEmoji} ${game.dealRating}", fontSize = 11.sp, color = Color(0xFFF59E0B), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onToggleFav, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favourite",
                        tint = if (game.isFavourite) Color(0xFFEF4444) else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text("₹${game.currentPriceInr}", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF22C55E))
                if (game.discountPercent > 0) {
                    Text("-${game.discountPercent}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF22C55E))
                }
            }
        }
    }
}

@Composable
fun GameDetailedCard(game: XboxGameDeal, onToggleFav: () -> Unit, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171920))
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(165.dp)) {
                AsyncImage(
                    model = game.coverImageUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF232631))
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0x22000000), Color(0xE6171920)))
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (game.discountPercent > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF22C55E))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("-${game.discountPercent}% OFF", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 12.sp)
                            }
                        }
                        if (game.isGamePass) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF107C10))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("GAME PASS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                            }
                        }
                    }

                    IconButton(
                        onClick = onToggleFav,
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x99000000))
                    ) {
                        Icon(
                            imageVector = if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Save Favourite",
                            tint = if (game.isFavourite) Color(0xFFEF4444) else Color.White
                        )
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
                ) {
                    Text(game.title, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Color.White)
                    Text("${game.publisher} • ${game.genre} • ${game.starRating}★", fontSize = 12.sp, color = Color.LightGray)
                }
            }

            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("₹${game.currentPriceInr}", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF22C55E))
                        if (game.discountPercent > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("₹${game.mrpInr}", fontSize = 14.sp, color = Color.Gray, textDecoration = TextDecoration.LineThrough)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("All-Time Low: ₹${game.lowestEverInr}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF22C55E))
                        Text("Tap for Trailer & Charts", fontSize = 11.sp, color = Color.Gray)
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
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(74.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF232631))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF222530))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${game.recommendationEmoji} ${game.dealRating}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (game.isAtAllTimeLow) Color(0xFF22C55E) else Color(0xFFF59E0B)
                    )
                }
            }
        }
    }
}

@Composable
fun GameDetailAnalysisSheet(
    game: XboxGameDeal,
    onToggleFav: () -> Unit,
    onSaveAlert: (Int) -> Unit,
    onRemoveAlert: () -> Unit
) {
    val context = LocalContext.current
    var playTrailer by remember { mutableStateOf(false) }
    var selectedTimeframe by remember { mutableStateOf("1Y") }
    var targetPrice by remember { mutableIntStateOf(game.alertTargetInr ?: game.lowestEverInr) }

    val activePoints = when (selectedTimeframe) {
        "1M" -> game.monthHistory
        "ALL" -> game.allTimeHistory
        else -> game.yearHistory
    }

    val tfLow = activePoints.minOfOrNull { it.priceInr } ?: game.lowestEverInr
    val tfHigh = activePoints.maxOfOrNull { it.priceInr } ?: game.mrpInr
    val tfAvg = if (activePoints.isNotEmpty()) activePoints.map { it.priceInr }.average().roundToInt() else game.currentPriceInr

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(game.title, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(
                    text = "${game.genre} • ${game.starRating}★" + if (game.isGamePass) " • Game Pass India" else " • ID: ${game.productId}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onToggleFav) {
                Icon(
                    imageVector = if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favourite",
                    tint = if (game.isFavourite) Color(0xFFEF4444) else Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(195.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
        ) {
            if (playTrailer) {
                InAppTrailerPlayer(videoUrl = game.trailerUrl)
            } else {
                AsyncImage(
                    model = game.coverImageUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable { playTrailer = true },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play Trailer", tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Watch Game Trailer", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }
                }
            }
        }

        if (game.screenshotUrls.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Sample Screenshots", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(game.screenshotUrls) { shot ->
                    AsyncImage(
                        model = shot,
                        contentDescription = "Game Screenshot",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(180.dp)
                            .height(102.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF232631))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF222530)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(game.recommendationEmoji, fontSize = 28.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("PURCHASE RECOMMENDATION", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(game.dealRating, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Price History (INR)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1M" to "Month", "1Y" to "Year", "ALL" to "All-Time").forEach { (code, label) ->
                    FilterChip(
                        selected = selectedTimeframe == code,
                        onClick = { selectedTimeframe = code },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PriceStatBox("LOWEST", "₹$tfLow", Color(0xFF22C55E), Modifier.weight(1f))
            PriceStatBox("AVERAGE", "₹$tfAvg", Color(0xFFF59E0B), Modifier.weight(1f))
            PriceStatBox("HIGHEST", "₹$tfHigh", Color.LightGray, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(10.dp))

        InteractivePriceHistoryChart(
            points = activePoints,
            lowestPrice = tfLow,
            maxPrice = tfHigh
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101217)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🔔 Target Price Drop Alert", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedIconButton(onClick = { targetPrice = (targetPrice - 100).coerceAtLeast(100) }) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease")
                    }
                    Text("₹$targetPrice", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color(0xFFF59E0B))
                    OutlinedIconButton(onClick = { targetPrice = (targetPrice + 100).coerceAtMost(game.mrpInr) }) {
                        Icon(Icons.Default.Add, contentDescription = "Increase")
                    }
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
                        OutlinedIconButton(onClick = onRemoveAlert) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Alert", tint = Color.Red)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(game.storeUrl)))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Buy on Xbox Store India (₹${game.currentPriceInr})", color = Color.Black, fontWeight = FontWeight.Black)
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

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun PriceStatBox(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF101217))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, fontSize = 15.sp, color = valueColor, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun InteractivePriceHistoryChart(points: List<PricePoint>, lowestPrice: Int, maxPrice: Int) {
    var selectedIdx by remember(points) { mutableIntStateOf((points.size - 1).coerceAtLeast(0)) }
    val activePt = points.getOrNull(selectedIdx)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101217))
            .padding(12.dp)
    ) {
        if (activePt != null) {
            Text(
                text = "Selected: ${activePt.label} → ₹${activePt.priceInr}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF22C55E)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .pointerInput(points) {
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
                color = Color(0xFF22C55E).copy(alpha = 0.45f),
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
                    color = if (isSelected) Color(0xFFF59E0B) else Color(0xFF22C55E),
                    radius = if (isSelected) 11f else 7f,
                    center = Offset(x, y)
                )
            }
            drawPath(path = path, color = Color(0xFF22C55E), style = Stroke(width = 4f))
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            points.forEach { Text(it.label, fontSize = 10.sp, color = Color.Gray) }
        }
    }
}
