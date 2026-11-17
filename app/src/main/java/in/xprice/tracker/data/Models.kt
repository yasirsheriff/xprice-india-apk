package `in`.xprice.tracker.data

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One recorded price observation. [epochDay] = days since 1970-01-01 (local date). */
data class PricePoint(val epochDay: Long, val priceInr: Int) {
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
}

data class GameEditionOption(
    val skuTitle: String,
    val priceInr: Int,
    val mrpInr: Int,
    val discountPercent: Int
)

enum class ViewLayoutMode(val label: String) {
    DETAILED("Cards"),
    GRID("Grid"),
    LIST("List")
}

enum class DealVerdict(val shortLabel: String) {
    COMING_SOON("Coming soon"),
    FREE("Free to play"),
    LEAVING_SOON("Leaving Game Pass"),
    ALL_TIME_LOW("Lowest tracked price"),
    BIG_SALE("Big sale"),
    ON_SALE("On sale"),
    GAME_PASS("In Game Pass"),
    FULL_PRICE("Full price")
}

data class XboxGameDeal(
    // --- From Microsoft Store (DisplayCatalog) ---
    val productId: String,
    val title: String,
    val publisher: String,
    val description: String,
    val genre: String,
    val starRating: Double,
    val ratingCount: Int,
    val releaseDate: String,
    val isNewLaunch: Boolean,
    val isUnreleasedOrNoPrice: Boolean,
    val isLeavingGamePassSoon: Boolean,
    /** Epoch millis when the current sale price ends, or 0 when unknown / not on sale. */
    val saleEndsAtMs: Long,
    val editions: List<GameEditionOption>,
    val supportedPlatforms: List<String>,
    val coverImageUrl: String,
    val screenshotUrls: List<String>,
    val trailerUrl: String,
    /** -1 = not priced yet, 0 = free, >0 = live INR price. */
    val currentPriceInr: Int,
    val mrpInr: Int,
    val discountPercent: Int,
    val isGamePass: Boolean,
    val storeUrl: String,
    /** Dated points derived from Microsoft's HistoricalBestAvailabilities (not cached). */
    val saleWindowPoints: List<PricePoint> = emptyList(),

    // --- Local user data & tracked history (filled by PriceHistoryStorage.enrich) ---
    val isFavourite: Boolean = false,
    val ownedPaidInr: Int? = null,
    val alertTargetInr: Int? = null,
    val history: List<PricePoint> = emptyList(),
    /** Sparse genuine prices read from archived xbox.com pages (web.archive.org), oldest first. */
    val archive: List<PricePoint> = emptyList(),
    val lowestEverInr: Int = currentPriceInr.coerceAtLeast(0),
    val highestEverInr: Int = maxOf(mrpInr, currentPriceInr),
    val isAtAllTimeLow: Boolean = false,
    val verdict: DealVerdict = DealVerdict.FULL_PRICE
) {
    val trackingSince: LocalDate? get() = history.firstOrNull()?.date
    val archiveSince: LocalDate? get() = archive.firstOrNull()?.date

    fun saleCountdownText(now: Long = System.currentTimeMillis()): String {
        if (saleEndsAtMs <= now) return ""
        val totalHours = (saleEndsAtMs - now) / 3_600_000L
        val days = totalHours / 24
        val hours = totalHours % 24
        return if (days > 0) "Ends in ${days}d ${hours}h" else "Ends in ${hours.coerceAtLeast(1)}h"
    }

    fun verdictDescription(): String = when (verdict) {
        DealVerdict.COMING_SOON -> "Not priced on Microsoft Store India yet. Set an alert to know when it lands."
        DealVerdict.FREE -> "Free to play on Microsoft Store India."
        DealVerdict.LEAVING_SOON -> "Leaving Game Pass soon. Members usually get up to 20% off to keep it."
        DealVerdict.ALL_TIME_LOW -> "Lowest price we've tracked: ${formatInr(currentPriceInr)} (-$discountPercent%). Good time to buy."
        DealVerdict.BIG_SALE -> "Big sale: save ${formatInr(mrpInr - currentPriceInr)} (-$discountPercent%)."
        DealVerdict.ON_SALE -> "On sale: save ${formatInr(mrpInr - currentPriceInr)} (-$discountPercent%)."
        DealVerdict.GAME_PASS -> "Included with Game Pass, or buy it for ${formatInr(currentPriceInr)}."
        DealVerdict.FULL_PRICE -> "Full price. Set a price alert and we'll notify you on the next sale."
    }
}

private val inrFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))

/** ₹1,23,456 style formatting. */
fun formatInr(amount: Int): String = "₹" + inrFormat.format(amount.toLong())

fun formatStorePriceLabel(priceInr: Int): String = when {
    priceInr < 0 -> "Coming soon"
    priceInr == 0 -> "Free"
    else -> formatInr(priceInr)
}

private val shortDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val tinyDateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

fun LocalDate.formatShort(): String = format(shortDateFmt)
fun LocalDate.formatTiny(): String = format(tinyDateFmt)

fun parseIsoToEpochMs(iso: String): Long = try {
    if (iso.length < 19) 0L else Instant.parse(iso).toEpochMilli()
} catch (_: Exception) {
    0L
}
