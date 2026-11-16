package `in`.xprice.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.xprice.tracker.data.XboxGameDeal
import `in`.xprice.tracker.data.formatInr
import `in`.xprice.tracker.data.formatStorePriceLabel
import `in`.xprice.tracker.ui.DealFilter
import `in`.xprice.tracker.ui.FilterState
import `in`.xprice.tracker.ui.SortOption
import `in`.xprice.tracker.ui.components.Cover
import `in`.xprice.tracker.ui.theme.Lw
import kotlin.math.roundToInt

@Composable
private fun SheetLabel(text: String) {
    Text(text, color = Lw.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun FilterSheetContent(initial: FilterState, genres: List<String>, onApply: (FilterState) -> Unit) {
    var deal by remember { mutableStateOf(initial.deal) }
    var genre by remember { mutableStateOf(initial.genre) }
    var minRating by remember { mutableDoubleStateOf(initial.minRating) }
    var maxPrice by remember { mutableFloatStateOf(initial.maxPrice.toFloat()) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Filters", style = MaterialTheme.typography.titleLarge, color = Lw.TextPrimary)

        SheetLabel("Show")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DealFilter.entries) { f ->
                FilterChip(selected = deal == f, onClick = { deal = f }, label = { Text(f.label, fontSize = 12.sp) })
            }
        }

        SheetLabel("Genre")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = genre == null, onClick = { genre = null }, label = { Text("All", fontSize = 12.sp) }) }
            items(genres) { g -> FilterChip(selected = genre == g, onClick = { genre = g }, label = { Text(g, fontSize = 12.sp) }) }
        }

        SheetLabel("Rating")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.0 to "Any", 4.0 to "4★ and up", 4.5 to "4.5★ and up").forEach { (v, label) ->
                FilterChip(selected = minRating == v, onClick = { minRating = v }, label = { Text(label, fontSize = 12.sp) })
            }
        }

        val capLabel = if (maxPrice >= FilterState.MAX_PRICE_CAP) "Any price" else "Up to ${formatInr(maxPrice.roundToInt())}"
        SheetLabel("Price · $capLabel")
        Slider(
            value = maxPrice,
            onValueChange = { maxPrice = ((it / 250f).roundToInt() * 250f) },
            valueRange = 250f..FilterState.MAX_PRICE_CAP.toFloat()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onApply(FilterState()) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text("Reset")
            }
            Button(
                onClick = { onApply(FilterState(deal, genre, minRating, maxPrice.roundToInt())) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lw.Green, contentColor = Lw.OnAccent)
            ) { Text("Show results", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun SortSheetContent(current: SortOption, onSelect: (SortOption) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 28.dp)) {
        Text("Sort by", style = MaterialTheme.typography.titleLarge, color = Lw.TextPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
        SortOption.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (option == current) Lw.SurfaceHigh else Lw.Surface)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = option == current, onClick = { onSelect(option) })
                Text(option.label, color = Lw.TextPrimary, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun CompareDialog(a: XboxGameDeal, b: XboxGameDeal, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lw.Surface,
        title = { Text("Compare", color = Lw.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(a, b).forEach { g ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(g.coverImageUrl, g.title, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                            Spacer(Modifier.height(6.dp))
                            Text(g.title, color = Lw.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center)
                        }
                    }
                }
                HorizontalDivider(color = Lw.Outline)
                val pa = if (a.currentPriceInr < 0) Int.MAX_VALUE else a.currentPriceInr
                val pb = if (b.currentPriceInr < 0) Int.MAX_VALUE else b.currentPriceInr
                CompareRow("Price", formatStorePriceLabel(a.currentPriceInr), formatStorePriceLabel(b.currentPriceInr), betterA = pa < pb, betterB = pb < pa)
                CompareRow("MRP", formatInr(a.mrpInr), formatInr(b.mrpInr))
                CompareRow("Discount", "${a.discountPercent}%", "${b.discountPercent}%", betterA = a.discountPercent > b.discountPercent, betterB = b.discountPercent > a.discountPercent)
                CompareRow("Lowest tracked", formatInr(a.lowestEverInr), formatInr(b.lowestEverInr))
                CompareRow("Rating", if (a.starRating > 0) "${a.starRating}★" else "–", if (b.starRating > 0) "${b.starRating}★" else "–", betterA = a.starRating > b.starRating, betterB = b.starRating > a.starRating)
                CompareRow("Game Pass", if (a.isGamePass) "Yes" else "No", if (b.isGamePass) "Yes" else "No")
                CompareRow("Genre", a.genre, b.genre)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Lw.Green) } }
    )
}

@Composable
private fun CompareRow(label: String, va: String, vb: String, betterA: Boolean = false, betterB: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(va, color = if (betterA) Lw.Green else Lw.TextPrimary, fontSize = 13.sp, fontWeight = if (betterA) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(label, color = Lw.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(vb, color = if (betterB) Lw.Green else Lw.TextPrimary, fontSize = 13.sp, fontWeight = if (betterB) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
    }
}
