@file:OptIn(ExperimentalTextApi::class)

package com.bytify.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bytify.app.R

/** Palet Bytify: Navy · Warm Beige · Soft Gold. */
object BytifyColors {
    val Navy = Color(0xFF1F2A44)
    val NavyDeep = Color(0xFF141C2F)
    val NavySoft = Color(0xFF2E3B5C)
    val Beige = Color(0xFFE8DCC8)
    val Paper = Color(0xFFF3EBDD)
    val Cream = Color(0xFFFBF6EC)
    val Gold = Color(0xFFC6A75E)
    val GoldDeep = Color(0xFF9A7E3F)
    val GoldLight = Color(0xFFEBD9A6)
    val OnGold = Navy
    val Error = Color(0xFFB4473A)
}

/** Warna garis & bayangan "cel" (garis tebal gaya ilustrasi). */
data class CelColors(val line: Color, val shadow: Color, val isDark: Boolean)

val LocalCel = staticCompositionLocalOf { CelColors(BytifyColors.Navy, BytifyColors.Navy, isDark = false) }

private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val JakartaSans = FontFamily(
    jakarta(400),
    jakarta(500),
    jakarta(600),
    jakarta(700),
    jakarta(800),
)

private val BytifyTypography = Typography().let { base ->
    fun TextStyle.j(weight: Int, size: Int? = null, spacing: Float? = null) = copy(
        fontFamily = JakartaSans,
        fontWeight = FontWeight(weight),
        fontSize = size?.sp ?: fontSize,
        letterSpacing = spacing?.sp ?: letterSpacing,
    )
    Typography(
        displaySmall = base.displaySmall.j(800, spacing = -1f),
        headlineLarge = base.headlineLarge.j(800, size = 32, spacing = -0.8f),
        headlineMedium = base.headlineMedium.j(800, spacing = -0.5f),
        headlineSmall = base.headlineSmall.j(800, spacing = -0.3f),
        titleLarge = base.titleLarge.j(800, spacing = -0.2f),
        titleMedium = base.titleMedium.j(700),
        titleSmall = base.titleSmall.j(700),
        bodyLarge = base.bodyLarge.j(500),
        bodyMedium = base.bodyMedium.j(500),
        bodySmall = base.bodySmall.j(500),
        labelLarge = base.labelLarge.j(700),
        labelMedium = base.labelMedium.j(600),
        labelSmall = base.labelSmall.j(700, spacing = 0.8f),
    )
}

/** Mode terang: kertas beige, garis navy, isian gold. */
private val LightScheme = lightColorScheme(
    primary = BytifyColors.Navy,
    onPrimary = BytifyColors.Cream,
    primaryContainer = BytifyColors.Gold,
    onPrimaryContainer = BytifyColors.Navy,
    secondary = BytifyColors.Gold,
    onSecondary = BytifyColors.Navy,
    secondaryContainer = BytifyColors.GoldLight,
    onSecondaryContainer = BytifyColors.Navy,
    tertiary = BytifyColors.GoldDeep,
    background = BytifyColors.Paper,
    onBackground = BytifyColors.Navy,
    surface = BytifyColors.Paper,
    onSurface = BytifyColors.Navy,
    surfaceVariant = BytifyColors.Beige,
    onSurfaceVariant = Color(0xFF5A6278),
    surfaceContainerLowest = BytifyColors.Cream,
    surfaceContainerLow = BytifyColors.Cream,
    surfaceContainer = Color(0xFFF7F0E3),
    surfaceContainerHigh = Color(0xFFEFE5D3),
    surfaceContainerHighest = BytifyColors.Beige,
    outline = BytifyColors.Navy,
    outlineVariant = Color(0xFFD4C5A9),
    error = BytifyColors.Error,
)

/** Mode malam: latar navy, teks beige, aksen gold. */
private val DarkScheme = darkColorScheme(
    primary = BytifyColors.Gold,
    onPrimary = BytifyColors.Navy,
    primaryContainer = BytifyColors.Gold,
    onPrimaryContainer = BytifyColors.Navy,
    secondary = BytifyColors.Gold,
    onSecondary = BytifyColors.Navy,
    secondaryContainer = Color(0xFF3A3F45),
    onSecondaryContainer = BytifyColors.Beige,
    tertiary = BytifyColors.GoldLight,
    background = Color(0xFF172036),
    onBackground = BytifyColors.Beige,
    surface = Color(0xFF172036),
    onSurface = BytifyColors.Beige,
    surfaceVariant = BytifyColors.NavySoft,
    onSurfaceVariant = Color(0xFFB7AE9E),
    surfaceContainerLowest = BytifyColors.NavyDeep,
    surfaceContainerLow = Color(0xFF1C2640),
    surfaceContainer = BytifyColors.Navy,
    surfaceContainerHigh = Color(0xFF26324F),
    surfaceContainerHighest = BytifyColors.NavySoft,
    outline = Color(0xFF0B1020),
    outlineVariant = Color(0xFF34405E),
    error = Color(0xFFE38B7E),
)

@Composable
fun BytifyTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    val cel = if (darkTheme) CelColors(Color(0xFF0B1020), Color(0xFF0B1020), isDark = true)
    else CelColors(BytifyColors.Navy, BytifyColors.Navy, isDark = false)
    CompositionLocalProvider(LocalCel provides cel) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = BytifyTypography,
            content = content,
        )
    }
}
