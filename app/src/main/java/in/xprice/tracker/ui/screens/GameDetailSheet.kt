package `in`.xprice.tracker.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import `in`.xprice.tracker.data.*
import `in`.xprice.tracker.ui.components.*
import `in`.xprice.tracker.ui.theme.Lw
import java.net.URLEncoder
import kotlin.math.roundToInt

@Composable
fun GameDetailSheet(
    game: XboxGameDeal,
    onToggleFav: () -> Unit,
    onSetOwned: (Int?) -> Unit,
    onSaveAlert: (Int) -> Unit,
    onRemoveAlert: () -> Unit
) {
    val context = LocalContext.current
    var playTrailer by remember { mutableStateOf(false) }
    var lightboxIndex by remember { mutableStateOf<Int?>(null) }
    var range by remember { mutableStateOf(ChartRange.ALL) }
    var showDescription by remember { mutableStateOf(false) }

    lightboxIndex?.let { idx ->
        ScreenshotLightbox(game.screenshotUrls, idx, onIndexChange = { lightboxIndex = it }, onDismiss = { lightboxIndex = null })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        // ---- Header ----
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.headlineSmall, color = Lw.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(game.publisher, game.genre, game.releaseDate.takeIf { it != "TBA" }).filterNotNull().joinToString(" · "),
                    color = Lw.TextSecondary, fontSize = 12.sp
                )
            }
            IconButton(onClick = {
                val msg = buildString {
                    appendLine("${game.title} on Xbox Store India")
                    appendLine("Price: ${formatStorePriceLabel(game.currentPriceInr)}" + if (game.discountPercent > 0) " (MRP ${formatInr(game.mrpInr)}, -${game.discountPercent}%)" else "")
                    append(game.storeUrl)
                }
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg)
                }, "Share deal"))
            }) { Icon(Icons.Default.Share, "Share", tint = Lw.TextSecondary) }
            IconButton(onClick = onToggleFav) {
                Icon(
                    if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favourite",
                    tint = if (game.isFavourite) Lw.Red else Lw.TextSecondary
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Media ----
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
            if (playTrailer && game.trailerUrl.isNotBlank()) {
                TrailerPlayer(game.trailerUrl)
            } else {
                Cover(game.coverImageUrl, game.title, Modifier.fillMaxSize())
                if (game.trailerUrl.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC000000))
                            .clickable { playTrailer = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Trailer", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (game.screenshotUrls.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(game.screenshotUrls) { i, url ->
                    Cover(url, "Screenshot ${i + 1}", Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(10.dp)).clickable { lightboxIndex = i })
                }
            }
        }

        // ---- Price block ----
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PriceTag(game, size = 28.sp)
                game.saleCountdownText().takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Lw.Amber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (game.discountPercent > 0) Badge("-${game.discountPercent}%", Lw.Green, Lw.OnAccent)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(verdictColor(game.verdict).copy(alpha = 0.1f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VerdictChip(game.verdict)
            Spacer(Modifier.width(10.dp))
            Text(game.verdictDescription(), color = Lw.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }

        // ---- Price history ----
        Spacer(Modifier.height(22.dp))
        SectionTitle("Price history")
        Spacer(Modifier.height(8.dp))
        if (game.currentPriceInr < 0) {
            InfoCard("No INR price yet. Microsoft Store India hasn't published a price for this game. Set an alert and we'll start tracking it as soon as it's priced.")
        } else if (game.currentPriceInr == 0) {
            InfoCard("This game is free to play, so there's no price history.")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("Lowest", formatInr(game.lowestEverInr), Modifier.weight(1f), valueColor = Lw.Green)
                StatTile("Highest", formatInr(game.highestEverInr), Modifier.weight(1f))
                StatTile("MRP", if (game.mrpInr > 0) formatInr(game.mrpInr) else "–", Modifier.weight(1f), valueColor = Lw.TextSecondary)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartRange.entries.forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { range = r },
                        label = { Text(r.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lw.Green, selectedLabelColor = Lw.OnAccent)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            PriceHistoryChart(history = game.history, archive = game.archive, mrp = game.mrpInr, currentPrice = game.currentPriceInr, range = range)
            Spacer(Modifier.height(8.dp))

            val since = game.trackingSince
            val changes = game.history.map { it.priceInr }.zipWithNext().count { (a, b) -> a != b }
            Text(
                if (since != null) "Tracked since ${since.formatShort()} · $changes price change${if (changes == 1) "" else "s"}"
                else "Tracking starts today",
                color = Lw.TextSecondary, fontSize = 12.sp
            )
            game.archiveSince?.let { since ->
                Text(
                    "Plus ${game.archive.size} archived price${if (game.archive.size == 1) "" else "s"} since ${since.formatShort()} from saved copies of the Xbox Store page (web.archive.org)",
                    color = Lw.TextSecondary, fontSize = 12.sp
                )
            }
            if (changes == 0 && game.archive.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "No price changes recorded yet. History comes from Lootwise's daily check of every Xbox India game, " +
                        "plus Microsoft's own record of recent sales, so the chart fills in as prices change.",
                    color = Lw.TextMuted, fontSize = 11.sp, lineHeight = 15.sp
                )
            }
            TextButton(
                onClick = {
                    val q = URLEncoder.encode(game.title, "UTF-8")
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://xbdeals.net/in-store/search?search_query=$q")))
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("See long-term history on xbdeals.net", color = Lw.Green, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Lw.Green, modifier = Modifier.size(14.dp))
            }
        }

        // ---- Price alert ----
        Spacer(Modifier.height(18.dp))
        PriceAlertCard(game, onSaveAlert, onRemoveAlert)

        // ---- Effective price calculator ----
        if (game.currentPriceInr > 0) {
            Spacer(Modifier.height(12.dp))
            CashbackCard(game, onSetOwned)
        }

        // ---- Editions ----
        if (game.editions.size > 1) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Editions")
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Lw.SurfaceSunken).padding(vertical = 4.dp)
            ) {
                game.editions.forEach { ed ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ed.skuTitle, color = Lw.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (ed.discountPercent > 0) {
                            Text(formatInr(ed.mrpInr), color = Lw.TextMuted, fontSize = 11.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(formatInr(ed.priceInr), color = Lw.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ---- About ----
        if (game.description.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("About")
            Spacer(Modifier.height(6.dp))
            Text(
                game.description,
                color = Lw.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                maxLines = if (showDescription) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { showDescription = !showDescription }
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                game.supportedPlatforms.forEach { Badge(it, Lw.SurfaceHigh, Lw.TextSecondary) }
                if (game.starRating > 0) Badge("★ ${game.starRating} (${game.ratingCount})", Lw.SurfaceHigh, Lw.Amber)
            }
        }

        // ---- Buy ----
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(game.storeUrl))) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lw.Green, contentColor = Lw.OnAccent)
        ) {
            Icon(Icons.Default.ShoppingCart, null)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    game.currentPriceInr < 0 -> "View on Xbox Store"
                    game.currentPriceInr == 0 -> "Get it free on Xbox Store"
                    else -> "Buy for ${formatInr(game.currentPriceInr)}"
                },
                fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Text(
        text,
        color = Lw.TextSecondary, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Lw.SurfaceSunken).padding(14.dp)
    )
}

@Composable
private fun PriceAlertCard(game: XboxGameDeal, onSaveAlert: (Int) -> Unit, onRemoveAlert: () -> Unit) {
    val suggested = game.alertTargetInr
        ?: if (game.currentPriceInr > 0) ((game.currentPriceInr * 0.7) / 50).roundToInt() * 50 else 1000
    var target by remember(game.productId) { mutableIntStateOf(suggested.coerceAtLeast(50)) }
    val step = if (target >= 2000) 100 else 50

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lw.SurfaceSunken)
            .border(1.dp, if (game.alertTargetInr != null) Lw.Amber.copy(alpha = 0.5f) else Lw.Outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NotificationsActive, null, tint = Lw.Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (game.alertTargetInr != null) "Alert set at ${formatInr(game.alertTargetInr)}" else "Price drop alert",
                color = Lw.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
            if (game.alertTargetInr != null) {
                TextButton(onClick = onRemoveAlert) { Text("Remove", color = Lw.Red, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { target = (target - step).coerceAtLeast(50) }) { Icon(Icons.Default.Remove, "Lower") }
            Text(
                formatInr(target),
                color = Lw.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            FilledTonalIconButton(onClick = { target = (target + step).coerceAtMost(maxOf(game.mrpInr, 10000)) }) { Icon(Icons.Default.Add, "Raise") }
        }
        if (game.currentPriceInr > 0 && game.lowestEverInr > 0) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Lowest" to game.lowestEverInr,
                    "-25%" to ((game.currentPriceInr * 0.75) / 10).roundToInt() * 10,
                    "-50%" to ((game.currentPriceInr * 0.5) / 10).roundToInt() * 10
                ).forEach { (label, value) ->
                    AssistChip(onClick = { target = value }, label = { Text("$label ${formatInr(value)}", fontSize = 11.sp) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSaveAlert(target) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lw.Amber, contentColor = Lw.OnAccent)
        ) { Text(if (game.alertTargetInr != null) "Update alert" else "Notify me at ${formatInr(target)}", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun CashbackCard(game: XboxGameDeal, onSetOwned: (Int?) -> Unit) {
    var pct by remember { mutableIntStateOf(0) }
    val effective = (game.currentPriceInr * (100 - pct) / 100.0).roundToInt()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lw.SurfaceSunken).padding(14.dp)) {
        Text("What you'll actually pay", color = Lw.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Store price", 5 to "5% gift card", 8 to "8% UPI / wallet", 10 to "10% bank offer").forEach { (p, label) ->
                FilterChip(selected = pct == p, onClick = { pct = p }, label = { Text(label, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatInr(effective), color = Lw.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (game.mrpInr > effective) Text("You save ${formatInr(game.mrpInr - effective)} vs MRP", color = Lw.TextSecondary, fontSize = 11.sp)
            }
            if (game.ownedPaidInr != null) {
                OutlinedButton(onClick = { onSetOwned(null) }, shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Lw.Green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Owned · remove", fontSize = 12.sp, color = Lw.TextPrimary)
                }
            } else {
                OutlinedButton(onClick = { onSetOwned(effective) }, shape = RoundedCornerShape(10.dp)) {
                    Text("I bought it", fontSize = 12.sp, color = Lw.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun ScreenshotLightbox(urls: List<String>, index: Int, onIndexChange: (Int) -> Unit, onDismiss: () -> Unit) {
    if (urls.isEmpty()) return
    val i = index.coerceIn(0, urls.lastIndex)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xF2050709)).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1} / ${urls.size}", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                AsyncImage(urls[i], "Screenshot", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(
                        onClick = { onIndexChange(if (i > 0) i - 1 else urls.lastIndex) },
                        modifier = Modifier.clip(CircleShape).background(Color(0x99000000))
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous", tint = Color.White) }
                    IconButton(
                        onClick = { onIndexChange(if (i < urls.lastIndex) i + 1 else 0) },
                        modifier = Modifier.clip(CircleShape).background(Color(0x99000000))
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun TrailerPlayer(videoUrl: String) {
    val context = LocalContext.current
    val player = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
}
