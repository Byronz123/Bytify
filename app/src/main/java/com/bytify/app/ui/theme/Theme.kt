@file:OptIn(ExperimentalTextApi::class)

package com.bytify.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bytify.app.R

object BytifyColors {
    val Green = Color(0xFF1ED760)
    val GreenDeep = Color(0xFF0B9A45)
    val OnGreen = Color(0xFF00210B)
    val Error = Color(0xFFFF6B7A)
}

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
        headlineSmall = base.headlineSmall.j(700, spacing = -0.3f),
        titleLarge = base.titleLarge.j(700, spacing = -0.2f),
        titleMedium = base.titleMedium.j(700),
        titleSmall = base.titleSmall.j(600),
        bodyLarge = base.bodyLarge.j(500),
        bodyMedium = base.bodyMedium.j(500),
        bodySmall = base.bodySmall.j(500),
        labelLarge = base.labelLarge.j(700),
        labelMedium = base.labelMedium.j(600),
        labelSmall = base.labelSmall.j(600, spacing = 0.8f),
    )
}

private fun bytifyScheme(pureBlack: Boolean) = darkColorScheme(
    primary = BytifyColors.Green,
    onPrimary = BytifyColors.OnGreen,
    primaryContainer = Color(0xFF113D24),
    onPrimaryContainer = Color(0xFFB4F5CB),
    secondary = Color(0xFF9CD9B1),
    onSecondary = Color(0xFF00210B),
    secondaryContainer = Color(0xFF1E3326),
    onSecondaryContainer = Color(0xFFCDEBD7),
    tertiary = Color(0xFFF2C46B),
    background = if (pureBlack) Color.Black else Color(0xFF0C100E),
    onBackground = Color(0xFFE5EBE7),
    surface = if (pureBlack) Color.Black else Color(0xFF0C100E),
    onSurface = Color(0xFFE5EBE7),
    surfaceVariant = Color(0xFF1C231F),
    onSurfaceVariant = Color(0xFFA2ADA6),
    surfaceContainerLowest = if (pureBlack) Color.Black else Color(0xFF080B09),
    surfaceContainerLow = if (pureBlack) Color(0xFF0A0D0B) else Color(0xFF121714),
    surfaceContainer = if (pureBlack) Color(0xFF101411) else Color(0xFF161C18),
    surfaceContainerHigh = if (pureBlack) Color(0xFF161B18) else Color(0xFF1D241F),
    surfaceContainerHighest = if (pureBlack) Color(0xFF1D231F) else Color(0xFF242C27),
    outline = Color(0xFF39433D),
    outlineVariant = Color(0xFF252D28),
    error = BytifyColors.Error,
)

@Composable
fun BytifyTheme(pureBlack: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = bytifyScheme(pureBlack),
        typography = BytifyTypography,
        content = content,
    )
}
