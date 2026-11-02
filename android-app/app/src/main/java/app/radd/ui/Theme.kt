package app.radd.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.radd.R

/** Cairo (SIL OFL 1.1) – an Arabic-first UI face. */
val Cairo = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_semibold, FontWeight.Medium),
    Font(R.font.cairo_semibold, FontWeight.SemiBold),
    Font(R.font.cairo_bold, FontWeight.Bold),
)

val Amber = Color(0xFFE0A15A)

private val Light = lightColorScheme(
    primary = Color(0xFF0F5C4D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBDD),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8DF),
    onSecondaryContainer = Color(0xFF05201A),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF6F8F5),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF6F8F5),
    onSurface = Color(0xFF171D1B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF3EF),
    surfaceContainer = Color(0xFFE9EEE9),
    surfaceContainerHigh = Color(0xFFE3E9E3),
    surfaceVariant = Color(0xFFDCE5DE),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C3),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8ED5BE),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF0F5C4D),
    onPrimaryContainer = Color(0xFFCDEBDD),
    secondary = Color(0xFFB0CCC2),
    secondaryContainer = Color(0xFF324B43),
    onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF482900),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF0E1412),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0E1412),
    onSurface = Color(0xFFDEE4E0),
    surfaceContainerLowest = Color(0xFF090F0D),
    surfaceContainerLow = Color(0xFF161D1A),
    surfaceContainer = Color(0xFF1A211E),
    surfaceContainerHigh = Color(0xFF242B28),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
)

private fun Typography.withFont(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun RaddTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography().withFont(Cairo), content = content)
}
