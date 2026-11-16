package `in`.xprice.tracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Single source of truth for app colours. */
object Lw {
    val Bg = Color(0xFF0B0E14)
    val Surface = Color(0xFF141922)
    val SurfaceHigh = Color(0xFF1C2330)
    val SurfaceSunken = Color(0xFF0F131A)
    val Outline = Color(0xFF263041)

    val Green = Color(0xFF2EE59D)      // primary / price / good deal
    val GreenDim = Color(0xFF1A7F5A)
    val Amber = Color(0xFFFFB547)      // accents, countdowns, coming soon
    val Red = Color(0xFFFF5C6C)        // leaving soon, destructive
    val Blue = Color(0xFF6CA8FF)       // info
    val XboxGreen = Color(0xFF107C10)  // Game Pass badge

    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xFFA3ADBF)
    val TextMuted = Color(0xFF6B768A)
    val OnAccent = Color(0xFF06140E)
}

private val LootwiseColors = darkColorScheme(
    primary = Lw.Green,
    onPrimary = Lw.OnAccent,
    primaryContainer = Lw.GreenDim,
    onPrimaryContainer = Lw.TextPrimary,
    secondary = Lw.Amber,
    onSecondary = Lw.OnAccent,
    secondaryContainer = Lw.SurfaceHigh,
    onSecondaryContainer = Lw.TextPrimary,
    tertiary = Lw.Blue,
    background = Lw.Bg,
    onBackground = Lw.TextPrimary,
    surface = Lw.Surface,
    onSurface = Lw.TextPrimary,
    surfaceVariant = Lw.SurfaceHigh,
    onSurfaceVariant = Lw.TextSecondary,
    surfaceContainer = Lw.Surface,
    surfaceContainerHigh = Lw.SurfaceHigh,
    surfaceContainerHighest = Lw.SurfaceHigh,
    surfaceContainerLow = Lw.SurfaceSunken,
    outline = Lw.Outline,
    outlineVariant = Lw.Outline,
    error = Lw.Red
)

private val LootwiseType = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
)

@Composable
fun LootwiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LootwiseColors, typography = LootwiseType, content = content)
}
