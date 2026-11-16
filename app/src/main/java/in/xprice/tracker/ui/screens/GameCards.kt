package `in`.xprice.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.xprice.tracker.data.XboxGameDeal
import `in`.xprice.tracker.data.formatInr
import `in`.xprice.tracker.ui.components.*
import `in`.xprice.tracker.ui.theme.Lw

private val CardShape = RoundedCornerShape(18.dp)

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    bg: Color,
    size: Dp = 34.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun RatingText(game: XboxGameDeal) {
    if (game.starRating <= 0.0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Star, contentDescription = null, tint = Lw.Amber, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(2.dp))
        Text("${game.starRating}", color = Lw.TextSecondary, fontSize = 11.sp)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Lw.Surface)
            .border(1.dp, if (isSelectedForCompare) Lw.Amber else Lw.Outline.copy(alpha = 0.5f), CardShape)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 8f)) {
            Cover(game.coverImageUrl, game.title, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Lw.Surface))))
            Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { GameBadges(game) }
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundIconButton(
                    Icons.AutoMirrored.Filled.CompareArrows, "Compare",
                    tint = if (isSelectedForCompare) Lw.OnAccent else Color.White,
                    bg = if (isSelectedForCompare) Lw.Amber else Color(0x99000000),
                    onClick = onToggleCompare
                )
                RoundIconButton(
                    if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favourite",
                    tint = if (game.isFavourite) Lw.Red else Color.White,
                    bg = Color(0x99000000),
                    onClick = onToggleFav
                )
            }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            Text(game.title, color = Lw.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${game.publisher} · ${game.genre}",
                    color = Lw.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (game.starRating > 0) {
                    Spacer(Modifier.width(8.dp))
                    RatingText(game)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PriceTag(game, size = 20.sp)
                    val sub = game.saleCountdownText().ifBlank {
                        if (game.lowestEverInr > 0 && game.lowestEverInr < game.currentPriceInr) "Lowest tracked ${formatInr(game.lowestEverInr)}" else ""
                    }
                    if (sub.isNotBlank()) Text(sub, color = Lw.Amber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    VerdictChip(game.verdict)
                    if (game.alertTargetInr != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsActive, null, tint = Lw.Amber, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Alert ${formatInr(game.alertTargetInr)}", color = Lw.TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Lw.Surface)
            .border(1.dp, if (isSelectedForCompare) Lw.Amber else Lw.Outline.copy(alpha = 0.5f), CardShape)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(game.coverImageUrl, game.title, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { GameBadges(game, compact = true) }
            Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundIconButton(
                    if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favourite",
                    tint = if (game.isFavourite) Lw.Red else Color.White, bg = Color(0x99000000), size = 30.dp,
                    onClick = onToggleFav
                )
                RoundIconButton(
                    Icons.AutoMirrored.Filled.CompareArrows, "Compare",
                    tint = if (isSelectedForCompare) Lw.OnAccent else Color.White,
                    bg = if (isSelectedForCompare) Lw.Amber else Color(0x99000000), size = 30.dp,
                    onClick = onToggleCompare
                )
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(game.title, color = Lw.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
            Spacer(Modifier.height(6.dp))
            PriceTag(game, size = 16.sp)
        }
    }
}

@Composable
fun GameListRow(
    game: XboxGameDeal,
    isSelectedForCompare: Boolean,
    onToggleFav: () -> Unit,
    onToggleCompare: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Lw.Surface)
            .border(1.dp, if (isSelectedForCompare) Lw.Amber else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(game.coverImageUrl, game.title, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(game.title, color = Lw.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(game.genre, color = Lw.TextSecondary, fontSize = 11.sp, maxLines = 1)
                if (game.starRating > 0) {
                    Text(" · ", color = Lw.TextMuted, fontSize = 11.sp)
                    RatingText(game)
                }
            }
            Spacer(Modifier.height(4.dp))
            VerdictChip(game.verdict)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RoundIconButton(
                    Icons.AutoMirrored.Filled.CompareArrows, "Compare",
                    tint = if (isSelectedForCompare) Lw.OnAccent else Lw.TextSecondary,
                    bg = if (isSelectedForCompare) Lw.Amber else Lw.SurfaceHigh, size = 28.dp,
                    onClick = onToggleCompare
                )
                RoundIconButton(
                    if (game.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favourite",
                    tint = if (game.isFavourite) Lw.Red else Lw.TextSecondary, bg = Lw.SurfaceHigh, size = 28.dp,
                    onClick = onToggleFav
                )
            }
            Spacer(Modifier.height(6.dp))
            PriceTag(game, size = 15.sp, showMrp = false)
            if (game.discountPercent > 0) Text("-${game.discountPercent}%", color = Lw.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
