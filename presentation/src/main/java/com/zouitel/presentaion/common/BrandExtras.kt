package com.zouitel.presentaion.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Brand tokens that Material's ColorScheme has no slot for. */
@Immutable
data class BrandExtras(
    /** Blue to green. Safe behind white text or icons. */
    val gradient: Brush,
    /** The logo's full-strength gradient. Decorative only. */
    val gradientVivid: Brush,
    /** Online / confirmed / delivered. */
    val success: Color,
    /** Orange accent for highlights such as offers, ratings and badges. */
    val accent: Color,
)

private val gradient = Brush.linearGradient(listOf(GradientStart, GradientEnd))
private val gradientVivid = Brush.linearGradient(listOf(VividBlue, VividTeal, VividGreen))

private val LightExtras = BrandExtras(
    gradient = gradient,
    gradientVivid = gradientVivid,
    success = Color(0xFF16B864),
    accent = BrandOrange,
)

private val DarkExtras = BrandExtras(
    gradient = gradient,
    gradientVivid = gradientVivid,
    success = Color(0xFF5BDB93),
    accent = Color(0xFFFFB35C),
)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF04215E),
    secondary = Color(0xFF0B8548),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F4E0),
    onSecondaryContainer = Color(0xFF00391C),
    tertiary = BrandOrange,
    onTertiary = Color(0xFF2B1600),
    tertiaryContainer = Color(0xFFFFE8CC),
    onTertiaryContainer = Color(0xFF3D2000),
    background = Color(0xFFF5F8FF),
    onBackground = Color(0xFF0E1A33),
    surface = Color.White,
    onSurface = Color(0xFF0E1A33),
    surfaceVariant = Color(0xFFE6ECF7),
    onSurfaceVariant = Color(0xFF44516B),
    outline = Color(0xFF7685A3),
    outlineVariant = Color(0xFFCDD6E8),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DBEFF),
    onPrimary = Color(0xFF00287A),
    primaryContainer = Color(0xFF0F3EA0),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF5BDB93),
    onSecondary = Color(0xFF00391C),
    secondaryContainer = Color(0xFF00542B),
    onSecondaryContainer = Color(0xFFD2F4E0),
    tertiary = Color(0xFFFFB35C),
    onTertiary = Color(0xFF3D2000),
    tertiaryContainer = Color(0xFF5A3000),
    onTertiaryContainer = Color(0xFFFFE8CC),
    background = Color(0xFF0A1020),
    onBackground = Color(0xFFE4E9F5),
    surface = Color(0xFF111A2E),
    onSurface = Color(0xFFE4E9F5),
    surfaceVariant = Color(0xFF1E2942),
    onSurfaceVariant = Color(0xFFB3BED6),
    outline = Color(0xFF7F8CA8),
    outlineVariant = Color(0xFF2C3855),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// The wordmark is heavy and geometric. Weights do that work for now; a rounded
// geometric family (Nunito, Poppins) in FontFamily would match it even better.
private val base = Typography()
private val AppTypography = Typography(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
)

// Soft and round, like the ring and the pin.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val LocalBrandExtras = staticCompositionLocalOf<BrandExtras> {
    error("AppTheme { } is missing above this composable")
}

object AppTheme {
    val extras: BrandExtras
        @Composable
        @ReadOnlyComposable
        get() = LocalBrandExtras.current
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalBrandExtras provides if (darkTheme) DarkExtras else LightExtras,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}