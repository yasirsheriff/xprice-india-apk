package `in`.xprice.tracker.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `in`.xprice.tracker.data.OwnedVaultEntry
import `in`.xprice.tracker.data.XboxLiveAuthManager
import `in`.xprice.tracker.data.XboxPlayerProfile
import `in`.xprice.tracker.data.formatInr
import `in`.xprice.tracker.ui.SyncState
import `in`.xprice.tracker.ui.components.*
import `in`.xprice.tracker.ui.theme.Lw
import java.util.Locale

private enum class LibrarySort(val label: String) { HOURS("Most played"), RECENT("Recent"), VALUE("Value"), NAME("A–Z") }

@Composable
fun LibraryScreen(
    profile: XboxPlayerProfile,
    library: List<OwnedVaultEntry>,
    syncState: SyncState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit,
    onExportCsv: () -> String,
    onExportJson: () -> String
) {
    val context = LocalContext.current
    var sortBy by remember { mutableStateOf(LibrarySort.HOURS) }
    var confirmSignOut by remember { mutableStateOf(false) }

    val totalHours = library.sumOf { it.hoursPlayed }
    val paidEntries = library.filter { it.paidInr != null }
    val spent = paidEntries.sumOf { it.paidInr ?: 0 }
    val saved = paidEntries.sumOf { (it.mrpInr - (it.paidInr ?: 0)).coerceAtLeast(0) }
    val libraryValue = library.sumOf { it.currentInr }
    val avgCompletion = if (library.isNotEmpty()) library.map { it.achievementsPct }.average().toInt() else 0
    val sorted = remember(library, sortBy) {
        when (sortBy) {
            LibrarySort.HOURS -> library.sortedByDescending { it.hoursPlayed }
            LibrarySort.RECENT -> library.sortedByDescending { it.lastPlayed }
            LibrarySort.VALUE -> library.sortedByDescending { it.currentInr }
            LibrarySort.NAME -> library.sortedBy { it.title.lowercase() }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Xbox?") },
            text = { Text("Games imported from Xbox will be removed from your library. Games you added yourself are kept.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out", color = Lw.Red) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
            containerColor = Lw.Surface
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            ProfileHeader(profile, syncState, onSignIn, onSignOut = { confirmSignOut = true }, onDismissError)
        }

        item {
            SectionTitle("Overview")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Games", "${library.size}", Modifier.weight(1f))
                StatTile("Played", String.format(Locale.US, "%,.0f h", totalHours), Modifier.weight(1f), valueColor = Lw.Green)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Gamerscore", if (profile.isConnected) String.format(Locale.US, "%,d", profile.gamerscore) else "–", Modifier.weight(1f), valueColor = Lw.Amber)
                StatTile("Avg. completion", "$avgCompletion%", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Library value", formatInr(libraryValue), Modifier.weight(1f), caption = "at today's prices")
                StatTile(
                    "Saved vs MRP", formatInr(saved), Modifier.weight(1f), valueColor = Lw.Green,
                    caption = if (paidEntries.isEmpty()) "mark games as bought" else "spent ${formatInr(spent)}"
                )
            }
        }

        item {
            SectionTitle("Your games") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LibrarySort.entries.forEach { s ->
                        Text(
                            s.label,
                            color = if (sortBy == s) Lw.Green else Lw.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = if (sortBy == s) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickableNoRipple { sortBy = s }.padding(4.dp)
                        )
                    }
                }
            }
        }

        if (library.isEmpty()) {
            item {
                EmptyState(
                    Icons.Default.SportsEsports,
                    "Your library is empty",
                    "Sign in with Xbox to import your games and playtime, or tap \"I bought it\" on any game."
                )
            }
        } else {
            items(sorted, key = { it.productId }) { LibraryRow(it) }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, "Lootwise backup (CSV)"); putExtra(Intent.EXTRA_TEXT, onExportCsv())
                        }, "Export CSV"))
                    },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                ) { Icon(Icons.Default.TableChart, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Export CSV") }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"; putExtra(Intent.EXTRA_SUBJECT, "Lootwise backup (JSON)"); putExtra(Intent.EXTRA_TEXT, onExportJson())
                        }, "Export JSON"))
                    },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                ) { Icon(Icons.Default.DataObject, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Export JSON") }
            }
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    )

@Composable
private fun ProfileHeader(
    profile: XboxPlayerProfile,
    syncState: SyncState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Lw.Surface)
            .border(1.dp, Lw.Outline.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Lw.XboxGreen), contentAlignment = Alignment.Center) {
                if (profile.isConnected && profile.avatarUrl.isNotBlank()) {
                    Cover(profile.avatarUrl, "Avatar", Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.SportsEsports, null, tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (profile.isConnected) profile.gamertag else "Connect your Xbox account",
                    color = Lw.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp
                )
                Text(
                    if (profile.isConnected) "Last synced ${relativeTime(profile.lastSyncMs)}"
                    else "Import your games, playtime and achievements.",
                    color = Lw.TextSecondary, fontSize = 12.sp
                )
            }
            if (profile.isConnected) {
                IconButton(onClick = onSignOut) { Icon(Icons.Default.Logout, "Sign out", tint = Lw.TextMuted) }
            }
        }

        when (syncState) {
            is SyncState.Running -> {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Lw.Green, trackColor = Lw.SurfaceHigh)
                Spacer(Modifier.height(6.dp))
                Text(syncState.message, color = Lw.TextSecondary, fontSize = 12.sp)
            }
            is SyncState.Failed -> {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Lw.Red.copy(alpha = 0.12f)).padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Lw.Red, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(syncState.message, color = Lw.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, "Dismiss", tint = Lw.TextMuted, modifier = Modifier.size(16.dp).clickableNoRipple(onDismissError))
                }
            }
            SyncState.Idle -> Unit
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSignIn,
            enabled = syncState !is SyncState.Running,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lw.XboxGreen, contentColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Icon(if (profile.isConnected) Icons.Default.Sync else Icons.Default.Login, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (profile.isConnected) "Sync again" else "Sign in with Microsoft", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LibraryRow(entry: OwnedVaultEntry) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Lw.Surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(entry.imageUrl, entry.title, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, color = Lw.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = buildList {
                if (entry.hoursPlayed > 0) add(String.format(Locale.US, "%.1f h", entry.hoursPlayed))
                if (entry.paidInr != null) add("paid ${formatInr(entry.paidInr)}")
                else if (entry.currentInr > 0) add("worth ${formatInr(entry.currentInr)}")
                if (entry.paidInr != null && entry.hoursPlayed >= 1) add("${formatInr((entry.paidInr / entry.hoursPlayed).toInt())}/h")
            }
            Text(parts.joinToString(" · ").ifBlank { "Not played yet" }, color = Lw.TextSecondary, fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { entry.achievementsPct / 100f },
                    modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
                    color = Lw.Green, trackColor = Lw.SurfaceHigh
                )
                Spacer(Modifier.width(8.dp))
                Text("${entry.achievementsPct}%", color = Lw.TextMuted, fontSize = 11.sp)
            }
        }
    }
}

private fun relativeTime(ms: Long): String {
    if (ms <= 0) return "never"
    val mins = (System.currentTimeMillis() - ms) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 1440 -> "${mins / 60} h ago"
        else -> "${mins / 1440} d ago"
    }
}

// =========================================================================
// Microsoft sign-in WebView
// =========================================================================
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XboxSignInDialog(
    onDismiss: () -> Unit,
    onTokenCaptured: (String) -> Unit,
    onError: (String) -> Unit
) {
    var progress by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Lw.Bg)) {
            Row(
                Modifier.fillMaxWidth().background(Lw.Surface).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Lw.TextPrimary) }
                Column(Modifier.weight(1f)) {
                    Text("Sign in with Microsoft", color = Lw.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = Lw.Green, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("login.live.com", color = Lw.TextSecondary, fontSize = 11.sp)
                    }
                }
            }
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Lw.Green, trackColor = Lw.Surface)
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        var handled = false

                        fun inspect(url: String?): Boolean {
                            if (handled || url == null) return false
                            val (token, error) = XboxLiveAuthManager.parseRedirect(url)
                            if (token == null && error == null) return false
                            handled = true
                            stopLoading()
                            // Clear session so the next sign-in can pick a different account.
                            CookieManager.getInstance().removeAllCookies(null)
                            if (token != null) onTokenCaptured(token)
                            else onError(if (error == "access_denied") "Sign-in was cancelled." else "Microsoft sign-in error: $error")
                            return true
                        }

                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                                inspect(request?.url?.toString())

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (!inspect(url)) super.onPageStarted(view, url, favicon)
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                inspect(url)
                                super.doUpdateVisitedHistory(view, url, isReload)
                            }
                        }
                        loadUrl(XboxLiveAuthManager.MS_OAUTH_AUTHORIZE_URL)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() }
            )
        }
    }
}
