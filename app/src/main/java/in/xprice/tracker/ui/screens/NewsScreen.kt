package `in`.xprice.tracker.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.xprice.tracker.data.XboxWireArticle
import `in`.xprice.tracker.ui.components.*
import `in`.xprice.tracker.ui.theme.Lw

val NEWS_CATEGORIES = listOf("All", "Game Pass", "New releases", "Free Play Days")

fun filterNews(articles: List<XboxWireArticle>, query: String, category: String): List<XboxWireArticle> =
    articles.filter { a ->
        val matchesQuery = query.isBlank() || a.title.contains(query, true) || a.summary.contains(query, true)
        val matchesCat = when (category) {
            "Game Pass" -> a.title.contains("Game Pass", true) || a.category.contains("Game Pass", true)
            "New releases" -> a.title.contains("Available", true) || a.title.contains("Next Week", true) || a.category.contains("Release", true)
            "Free Play Days" -> a.title.contains("Free Play", true)
            else -> true
        }
        matchesQuery && matchesCat
    }

@Composable
fun NewsScreen(
    articles: List<XboxWireArticle>,
    loading: Boolean,
    category: String,
    onCategory: (String) -> Unit,
    onOpen: (XboxWireArticle) -> Unit
) {
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(NEWS_CATEGORIES) { c ->
                FilterChip(selected = category == c, onClick = { onCategory(c) }, label = { Text(c, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(6.dp))
        when {
            loading && articles.isEmpty() -> ShimmerFeed()
            articles.isEmpty() -> EmptyState(Icons.Default.Newspaper, "No stories", "Pull to refresh or try another category.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(articles, key = { it.id }) { NewsCard(it) { onOpen(it) } }
            }
        }
    }
}

@Composable
private fun NewsCard(article: XboxWireArticle, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Lw.Surface).clickable(onClick = onClick)
    ) {
        if (article.imageUrl.isNotBlank()) {
            Cover(article.imageUrl, article.title, Modifier.fillMaxWidth().aspectRatio(16f / 8f))
        }
        Column(Modifier.padding(14.dp)) {
            Row {
                Text(article.category.uppercase(), color = Lw.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text("  ·  ${article.publishedDate}", color = Lw.TextMuted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(article.title, color = Lw.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
            if (article.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(article.summary, color = Lw.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun NewsReaderSheet(article: XboxWireArticle, onCheckPrice: (String) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)
    ) {
        Text("${article.category.uppercase()}  ·  ${article.publishedDate}", color = Lw.Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(article.title, style = MaterialTheme.typography.headlineSmall, color = Lw.TextPrimary)
        if (article.imageUrl.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Cover(article.imageUrl, article.title, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)))
        }
        if (article.extractedGameQuery.length >= 3) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { onCheckPrice(article.extractedGameQuery) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Check price: ${article.extractedGameQuery}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(article.fullBody, color = Lw.TextSecondary, fontSize = 15.sp, lineHeight = 23.sp)
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.articleUrl))) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lw.Green, contentColor = Lw.OnAccent)
        ) {
            Text("Read on Xbox Wire", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
        }
    }
}
