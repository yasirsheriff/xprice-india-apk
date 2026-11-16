package `in`.xprice.tracker.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import `in`.xprice.tracker.data.DealVerdict
import `in`.xprice.tracker.data.XboxGameDeal
import `in`.xprice.tracker.data.formatInr
import `in`.xprice.tracker.ui.theme.Lw

@Composable
fun Badge(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = fg,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/** Discount / Game Pass / New badges shown over a cover image. */
@Composable
fun GameBadges(game: XboxGameDeal, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (game.discountPercent > 0) Badge("-${game.discountPercent}%", Lw.Green, Lw.OnAccent)
        when {
            game.isUnreleasedOrNoPrice -> Badge("SOON", Lw.Amber, Lw.OnAccent)
            game.isLeavingGamePassSoon -> Badge("LEAVING", Lw.Red, Color.White)
            game.isNewLaunch && !compact -> Badge("NEW", Lw.Amber, Lw.OnAccent)
        }
        if (game.isGamePass && !compact) Badge("GAME PASS", Lw.XboxGreen, Color.White)
    }
}

fun verdictColor(v: DealVerdict): Color = when (v) {
    DealVerdict.ALL_TIME_LOW, DealVerdict.BIG_SALE, DealVerdict.FREE -> Lw.Green
    DealVerdict.ON_SALE, DealVerdict.GAME_PASS -> Lw.Blue
    DealVerdict.LEAVING_SOON -> Lw.Red
    DealVerdict.COMING_SOON -> Lw.Amber
    DealVerdict.FULL_PRICE -> Lw.TextMuted
}

@Composable
fun VerdictChip(verdict: DealVerdict, modifier: Modifier = Modifier) {
    val c = verdictColor(verdict)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(5.dp))
        Text(verdict.shortLabel, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Current price with struck-through MRP. */
@Composable
fun PriceTag(game: XboxGameDeal, size: TextUnit = 18.sp, showMrp: Boolean = true) {
    Row(verticalAlignment = Alignment.Bottom) {
        val (label, color) = when {
            game.currentPriceInr < 0 -> "Coming soon" to Lw.Amber
            game.currentPriceInr == 0 -> "Free" to Lw.Green
            else -> formatInr(game.currentPriceInr) to Lw.TextPrimary
        }
        Text(
            label,
            color = color,
            fontSize = if (game.currentPriceInr < 0) size * 0.8f else size,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        if (showMrp && game.discountPercent > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                formatInr(game.mrpInr),
                color = Lw.TextMuted,
                fontSize = size * 0.65f,
                textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.padding(bottom = 2.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
fun Cover(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Lw.SurfaceHigh)
    )
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Lw.TextPrimary, caption: String? = null) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Lw.SurfaceSunken)
            .border(1.dp, Lw.Outline.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, color = Lw.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caption != null) {
            Text(caption, color = Lw.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Lw.TextPrimary, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(Lw.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = Lw.TextSecondary, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Lw.TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Lw.TextSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionLabel, color = Lw.Green) }
        }
    }
}

@Composable
fun ShimmerBox(modifier: Modifier = Modifier, corner: Dp = 16.dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmer_x"
    )
    val brush = Brush.linearGradient(
        colors = listOf(Lw.Surface, Lw.SurfaceHigh, Lw.Surface),
        start = Offset(x, 0f),
        end = Offset(x + 400f, 200f)
    )
    Box(modifier.clip(RoundedCornerShape(corner)).background(brush))
}

@Composable
fun ShimmerFeed() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
        repeat(4) { ShimmerBox(Modifier.fillMaxWidth().height(200.dp)) }
    }
}
