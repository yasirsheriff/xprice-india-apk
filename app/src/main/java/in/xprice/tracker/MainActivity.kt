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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import `in`.xprice.tracker.data.PriceHistoryStorage
import `in`.xprice.tracker.data.PricePoint
import `in`.xprice.tracker.data.XboxGameDeal
import `in`.xprice.tracker.data.XboxStoreRepository
import `in`.xprice.tracker.worker.PriceAlertWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scheduleBackgroundPriceCheck()

        setContent {
            val darkPalette = darkColorScheme(
                primary = Color(0xFF22C55E),
                secondary = Color(0xFFF59E0B),
                background = Color(0xFF101014),
                surface = Color(0xFF1C1C22)
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

    var games by remember { mutableStateOf<List<XboxGameDeal>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedDealFilter by remember { mutableStateOf("All Deals") }
    var selectedGenre by remember { mutableStateOf("All Genres") }
    var selectedRatingFilter by remember { mutableStateOf("Any ★") }
    var selectedSort by remember { mutableStateOf("Best Discount") }
    var selectedGame by remember { mutableStateOf<XboxGameDeal?>(null) }

    // Request Android 13/15 Push Notification Permission
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun loadCatalog(query: String) {
        isLoading = true
        scope.launch {
            val result = if (query.isBlank()) {
                repository.fetchLiveIndiaDeals()
            } else {
                repository.searchLiveXboxIndiaCatalog(query)
            }
            result.onSuccess {
                games = it
                isLoading = false
            }
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            loadCatalog("")
        } else {
            delay(450)
            loadCatalog(searchQuery)
        }
    }

    val filteredAndSortedGames = remember(
        games, selectedDealFilter, selectedGenre, selectedRatingFilter, selectedSort
    ) {
        games.filter { game ->
            val matchesDeal = when (selectedDealFilter) {
                "🔥 All-Time Low" -> game.isAtAllTimeLow
                "🔔 My Alerts" -> game.alertTargetInr != null
                "Under ₹1,500" -> game.currentPriceInr <= 1500
                "Under ₹2,500" -> game.currentPriceInr <= 2500
                "Game Pass" -> game.isGamePass
                else -> true
            }
            val matchesGenre = selectedGenre == "All Genres" ||
                game.genre.equals(selectedGenre, ignoreCase = true)

            val matchesRating = when (selectedRatingFilter) {
                "4.5★ & Up" -> game.starRating >= 4.5
                "4.0★ & Up" -> game.starRating >= 4.0
                else -> true
            }
            matchesDeal && matchesGenre && matchesRating
        }.let { list ->
            when (selectedSort) {
                "Lowest ₹" -> list.sortedBy { it.currentPriceInr }
                "All-Time Low ₹" -> list.sortedBy { it.lowestEverInr }
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
                        Text("XPrice ", fontWeight = FontWeight.ExtraBold, color = Color(0xFF22C55E))
                        Text("INDIA ₹ • TRACKER & ALERTS", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { loadCatalog(searchQuery) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Live Prices", tint = Color(0xFF22C55E))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF101014))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search Xbox India Store (e.g., Halo, Forza, RPG)...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Row 1: All-Time Low, Alerts, Price Cap & Game Pass Filter
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All Deals", "🔥 All-Time Low", "🔔 My Alerts", "Under ₹1,500", "Under ₹2,500", "Game Pass").forEach { filter ->
                    FilterChip(
                        selected = selectedDealFilter == filter,
                        onClick = { selectedDealFilter = filter },
                        label = { Text(filter, fontSize = 12.sp) }
                    )
                }
            }

            // Row 2: Genre Filter
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All Genres", "Action & Adventure", "Role Playing", "Racing & Flying", "Shooter", "Sports").forEach { genre ->
                    FilterChip(
                        selected = selectedGenre == genre,
                        onClick = { selectedGenre = genre },
                        label = { Text(genre, fontSize = 12.sp) }
                    )
                }
            }

            // Row 3: Rating & Sort Options
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("Any ★", "4.0★ & Up", "4.5★ & Up").forEach { ratingChip ->
                    FilterChip(
                        selected = selectedRatingFilter == ratingChip,
                        onClick = { selectedRatingFilter = ratingChip },
                        label = { Text(ratingChip, fontSize = 12.sp) }
                    )
                }
                Text("• Sort:", fontSize = 12.sp, color = Color.Gray)
                listOf("Best Discount", "Lowest ₹", "All-Time Low ₹", "Top Rated ★").forEach { sortChip ->
                    FilterChip(
                        selected = selectedSort == sortChip,
                        onClick = { selectedSort = sortChip },
                        label = { Text(sortChip, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF22C55E))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Checking live INR prices & price history...", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            } else if (filteredAndSortedGames.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No games match your active filter.", color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            selectedDealFilter = "All Deals"
                            selectedGenre = "All Genres"
                            selectedRatingFilter = "Any ★"
                        }) {
                            Text("Reset All Filters", color = Color(0xFF22C55E))
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredAndSortedGames, key = { it.productId }) { game ->
                        GameDealCard(game = game, onClick = { selectedGame = game })
                    }
                }
            }
        }

        selectedGame?.let { game ->
            ModalBottomSheet(
                onDismissRequest = { selectedGame = null },
                containerColor = Color(0xFF1C1C22)
            ) {
                PriceHistoryAndAlertSheet(
                    game = game,
                    onSaveAlert = { targetInr ->
                        storage.setPriceAlert(game.productId, game.title, targetInr)
                        Toast.makeText(context, "Alert set for ₹$targetInr!", Toast.LENGTH_SHORT).show()
                        // If already at or below target, fire an immediate test/live alert notification
                        if (game.currentPriceInr <= targetInr) {
                            PriceAlertWorker.sendPriceDropNotification(
                                context = context,
                                productId = game.productId,
                                title = game.title,
                                currentInr = game.currentPriceInr,
                                targetInr = targetInr,
                                storeUrl = game.storeUrl
                            )
                        }
                        selectedGame = null
                        loadCatalog(searchQuery)
                    },
                    onRemoveAlert = {
                        storage.removePriceAlert(game.productId)
                        Toast.makeText(context, "Price alert removed", Toast.LENGTH_SHORT).show()
                        selectedGame = null
                        loadCatalog(searchQuery)
                    }
                )
            }
        }
    }
}

@Composable
fun GameDealCard(game: XboxGameDeal, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C22))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(game.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2A2A35))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(game.genre, fontSize = 11.sp, color = Color.LightGray)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                        Text(" ${game.starRating}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF59E0B))
                        if (game.isAtAllTimeLow) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("• 🔥 RECORD LOW", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF22C55E))
                        }
                    }
                }
                if (game.discountPercent > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF22C55E))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("-${game.discountPercent}%", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("₹${game.currentPriceInr}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF22C55E))
                    if (game.discountPercent > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("₹${game.mrpInr}", fontSize = 14.sp, color = Color.Gray, textDecoration = TextDecoration.LineThrough)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("All-Time Low: ₹${game.lowestEverInr}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF22C55E))
                    if (game.alertTargetInr != null) {
                        Text("🔔 Alert: ≤ ₹${game.alertTargetInr}", fontSize = 11.sp, color = Color(0xFFF59E0B))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TrendingDown, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(game.dealRating, fontSize = 12.sp, color = Color(0xFFF59E0B))
            }
        }
    }
}

@Composable
fun PriceHistoryAndAlertSheet(
    game: XboxGameDeal,
    onSaveAlert: (Int) -> Unit,
    onRemoveAlert: () -> Unit
) {
    val context = LocalContext.current
    var targetPrice by remember {
        mutableIntStateOf(game.alertTargetInr ?: game.lowestEverInr)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(game.title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("${game.genre} • ${game.starRating}★ • Store ID: ${game.productId}", color = Color.Gray, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(14.dp))

        // 3-Column Price Analytics Summary (Lowest / Average / MRP High)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PriceStatBox("ALL-TIME LOW", "₹${game.lowestEverInr}", Color(0xFF22C55E), Modifier.weight(1f))
            PriceStatBox("6M AVERAGE", "₹${game.averagePriceInr}", Color(0xFFF59E0B), Modifier.weight(1f))
            PriceStatBox("HIGHEST MRP", "₹${game.highestEverInr}", Color.LightGray, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Price History Chart with Dashed All-Time Low Floor Line
        PriceHistoryChart(
            points = game.priceHistory,
            lowestPrice = game.lowestEverInr,
            maxPrice = game.highestEverInr
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Interactive Price Drop Alert Configurator
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141419)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🔔 Automatic Price Drop Alert (INR)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Get a push notification when price drops to or below:", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedIconButton(
                        onClick = { targetPrice = (targetPrice - 100).coerceAtLeast(100) }
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease ₹100")
                    }

                    Text(
                        text = "₹$targetPrice",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFF59E0B)
                    )

                    OutlinedIconButton(
                        onClick = { targetPrice = (targetPrice + 100).coerceAtMost(game.mrpInr) }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase ₹100")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSaveAlert(targetPrice) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Set Alert @ ₹$targetPrice", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    if (game.alertTargetInr != null) {
                        OutlinedIconButton(onClick = onRemoveAlert) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove Alert", tint = Color.Red)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Direct Buy Button
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(game.storeUrl))
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Buy on Xbox Store India (₹${game.currentPriceInr})",
                color = Color.Black,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun PriceStatBox(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF141419))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontSize = 16.sp, color = valueColor, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun PriceHistoryChart(points: List<PricePoint>, lowestPrice: Int, maxPrice: Int) {
    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Color(0xFF141419), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            if (points.isEmpty()) return@Canvas
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            val range = (maxPrice - lowestPrice).coerceAtLeast(100)

            // Draw dashed baseline for All-Time Low Floor at bottom of chart
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
                val y = ((1f - ((pt.priceInr - lowestPrice).toFloat() / range)) * size.height).coerceIn(0f, size.height)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(color = Color(0xFF22C55E), radius = 7f, center = Offset(x, y))
            }
            drawPath(path = path, color = Color(0xFF22C55E), style = Stroke(width = 4f))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            points.forEach { Text("${it.month} (₹${it.priceInr})", fontSize = 10.sp, color = Color.Gray) }
        }
    }
}
