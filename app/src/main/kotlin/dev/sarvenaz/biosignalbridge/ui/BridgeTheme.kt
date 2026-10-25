package dev.sarvenaz.biosignalbridge.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.sarvenaz.biosignalbridge.R

internal val Midnight = Color(0xFF18233F)
internal val Ink = Color(0xFF202B45)
internal val Iris = Color(0xFF5646C8)
internal val Ice = Color(0xFFB6C8FF)
internal val Porcelain = Color(0xFFF4F5FA)
internal val Muted = Color(0xFF606A80)
internal val Line = Color(0xFFDFE3ED)
internal val Copper = Color(0xFF965443)

private val BridgeColors = lightColorScheme(
    primary = Iris,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E7FC),
    onPrimaryContainer = Midnight,
    secondary = Midnight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E7FC),
    onSecondaryContainer = Midnight,
    tertiary = Iris,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEFEDFC),
    onTertiaryContainer = Midnight,
    surface = Color.White,
    onSurface = Ink,
    surfaceTint = Iris,
    surfaceVariant = Color(0xFFEAEDF5),
    onSurfaceVariant = Muted,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDE1EE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FC),
    surfaceContainer = Color(0xFFEFF0F7),
    surfaceContainerHigh = Color(0xFFE8EBF5),
    surfaceContainerHighest = Color(0xFFE1E5F1),
    background = Porcelain,
    onBackground = Ink,
    inverseSurface = Midnight,
    inverseOnSurface = Color.White,
    inversePrimary = Ice,
    outline = Color(0xFF8189A0),
    outlineVariant = Line,
    error = Color(0xFFA62F38),
    onError = Color.White,
    errorContainer = Color(0xFFF9E6E3),
    onErrorContainer = Color(0xFF72232A),
    scrim = Color(0xFF0D1328),
)

private val BridgeFont = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

private val BridgeTypography = Typography(
    headlineLarge = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = BridgeFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
)

@Composable
fun BioSignalBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BridgeColors, typography = BridgeTypography, content = content)
}
