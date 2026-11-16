package `in`.xprice.tracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import `in`.xprice.tracker.MainActivity
import `in`.xprice.tracker.data.CatalogKey
import `in`.xprice.tracker.data.PriceHistoryStorage
import `in`.xprice.tracker.data.formatInr

// Class names kept from earlier versions so widgets already on home screens keep working.
class RupeeVaultWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val storage = PriceHistoryStorage(context)
        val favIds = storage.getFavouriteIds()
        val alerts = storage.getAllAlerts()
        val allCached = listOf(CatalogKey.ALL, CatalogKey.DEALS, CatalogKey.GAME_PASS)
            .flatMap { storage.loadCachedDeals(it.cacheKey) }
            .distinctBy { it.productId }

        val widgetGames = allCached.filter { favIds.contains(it.productId) || alerts.containsKey(it.productId) }
            .ifEmpty { allCached.filter { it.discountPercent > 0 }.ifEmpty { allCached } }
            .take(3)

        provideContent {
            WidgetContent(widgetGames.map { Triple(it.title, it.currentPriceInr, it.discountPercent) })
        }
    }

    @Composable
    private fun WidgetContent(items: List<Triple<String, Int, Int>>) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF0B0F17))
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Text(
                text = "Lootwise · Xbox India prices",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF10B981)),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            )
            Spacer(modifier = GlanceModifier.height(6.dp))
            if (items.isEmpty()) {
                Text(
                    text = "Open Lootwise to load prices",
                    style = TextStyle(color = ColorProvider(Color.LightGray), fontSize = 12.sp)
                )
            } else {
                items.forEach { (title, priceInr, discountPct) ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp)
                        )
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        val tag = when {
                            priceInr < 0 -> "SOON"
                            priceInr == 0 -> "FREE"
                            discountPct > 0 -> "${formatInr(priceInr)} (-$discountPct%)"
                            else -> formatInr(priceInr)
                        }
                        Text(
                            text = tag,
                            style = TextStyle(
                                color = ColorProvider(if (discountPct > 0) Color(0xFF10B981) else Color(0xFFF59E0B)),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

class RupeeVaultWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RupeeVaultWidget()
}
