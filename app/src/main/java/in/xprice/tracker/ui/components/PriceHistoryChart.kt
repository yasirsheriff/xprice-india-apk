package `in`.xprice.tracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.xprice.tracker.data.PricePoint
import `in`.xprice.tracker.data.formatInr
import `in`.xprice.tracker.data.formatShort
import `in`.xprice.tracker.data.formatTiny
import `in`.xprice.tracker.ui.theme.Lw
import java.time.LocalDate
import kotlin.math.roundToInt

enum class ChartRange(val label: String, val days: Long?) {
    M1("1M", 30), M3("3M", 90), Y1("1Y", 365), ALL("All", null)
}

/** A step series covering [startDay, endDay]; each point holds from its day until the next. */
private data class Series(val startDay: Long, val endDay: Long, val steps: List<PricePoint>) {
    fun priceAt(day: Long): Int = steps.lastOrNull { it.epochDay <= day }?.priceInr ?: steps.first().priceInr
}

private fun buildSeries(history: List<PricePoint>, currentPrice: Int, range: ChartRange): Series? {
    val clean = history.filter { it.priceInr > 0 }.sortedBy { it.epochDay }
    if (clean.isEmpty()) return null
    val today = LocalDate.now().toEpochDay()
    val end = maxOf(today, clean.last().epochDay)
    val start = range.days?.let { maxOf(end - it, clean.first().epochDay) } ?: clean.first().epochDay

    val carryIn = clean.lastOrNull { it.epochDay <= start }?.priceInr ?: clean.first().priceInr
    val steps = mutableListOf(PricePoint(start, carryIn))
    clean.filter { it.epochDay > start }.forEach { p ->
        if (p.priceInr != steps.last().priceInr) steps.add(p)
    }
    if (currentPrice > 0 && currentPrice != steps.last().priceInr) steps.add(PricePoint(today, currentPrice))
    return Series(start, end, steps)
}

@Composable
fun PriceHistoryChart(
    history: List<PricePoint>,
    mrp: Int,
    currentPrice: Int,
    range: ChartRange,
    modifier: Modifier = Modifier
) {
    val series = remember(history, currentPrice, range) { buildSeries(history, currentPrice, range) }
    var selectedDay by remember(series) { mutableStateOf<Long?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Lw.SurfaceSunken)
            .padding(14.dp)
    ) {
        if (series == null) {
            Text("No price recorded yet", color = Lw.TextSecondary, fontSize = 13.sp)
            return@Column
        }

        val prices = series.steps.map { it.priceInr }
        val lo = prices.min()
        val hiRaw = maxOf(prices.max(), if (mrp > 0) mrp else 0)
        val spread = (hiRaw - lo).coerceAtLeast((lo * 0.1f).roundToInt().coerceAtLeast(100))
        val yMin = (lo - spread * 0.15f).coerceAtLeast(0f)
        val yMax = hiRaw + spread * 0.15f

        // Header: selected point, or the current price.
        val headerDay = selectedDay ?: series.endDay
        val headerPrice = series.priceAt(headerDay)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatInr(headerPrice), color = Lw.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                if (selectedDay == null) "today" else LocalDate.ofEpochDay(headerDay).formatShort(),
                color = Lw.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth().height(150.dp)) {
            Column(
                modifier = Modifier.fillMaxHeight().padding(end = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                listOf(yMax, (yMax + yMin) / 2f, yMin).forEach {
                    Text(formatInr(((it / 10f).roundToInt() * 10)), color = Lw.TextMuted, fontSize = 10.sp)
                }
            }
            val span = (series.endDay - series.startDay).coerceAtLeast(1L)
            fun dayFromX(x: Float, width: Float): Long =
                series.startDay + ((x / width).coerceIn(0f, 1f) * span).roundToInt()

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(series) {
                        detectTapGestures { selectedDay = dayFromX(it.x, size.width.toFloat()) }
                    }
                    .pointerInput(series) {
                        detectHorizontalDragGestures(
                            onDragStart = { selectedDay = dayFromX(it.x, size.width.toFloat()) },
                            onHorizontalDrag = { change, _ -> selectedDay = dayFromX(change.position.x, size.width.toFloat()) }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height
                fun x(day: Long) = ((day - series.startDay).toFloat() / span) * w
                fun y(price: Number) = h - ((price.toFloat() - yMin) / (yMax - yMin)) * h

                // Grid
                listOf(0f, 0.5f, 1f).forEach { f ->
                    drawLine(Lw.Outline.copy(alpha = 0.5f), Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1f)
                }
                // MRP reference
                if (mrp > 0) {
                    drawLine(
                        Lw.TextMuted, Offset(0f, y(mrp)), Offset(w, y(mrp)),
                        strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    )
                }

                // Step line
                val line = Path()
                series.steps.forEachIndexed { i, p ->
                    val px = x(p.epochDay)
                    if (i == 0) line.moveTo(px, y(p.priceInr))
                    else {
                        line.lineTo(px, y(series.steps[i - 1].priceInr))
                        line.lineTo(px, y(p.priceInr))
                    }
                }
                line.lineTo(w, y(series.steps.last().priceInr))

                val fill = Path().apply {
                    addPath(line)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(Lw.Green.copy(alpha = 0.28f), Lw.Green.copy(alpha = 0f))))
                drawPath(line, Lw.Green, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                // Price-change markers
                series.steps.forEach { p -> drawCircle(Lw.Green, 6f, Offset(x(p.epochDay), y(p.priceInr))) }

                selectedDay?.let { d ->
                    val sx = x(d)
                    val sy = y(series.priceAt(d))
                    drawLine(Lw.TextSecondary, Offset(sx, 0f), Offset(sx, h), strokeWidth = 1.5f)
                    drawCircle(Lw.Bg, 11f, Offset(sx, sy))
                    drawCircle(Lw.Amber, 8f, Offset(sx, sy))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 44.dp)) {
            Text(LocalDate.ofEpochDay(series.startDay).formatTiny(), color = Lw.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            if (mrp > 0) Text("- - MRP ${formatInr(mrp)}", color = Lw.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Today", color = Lw.TextMuted, fontSize = 10.sp)
        }
    }
}
