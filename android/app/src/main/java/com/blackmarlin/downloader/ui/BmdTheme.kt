package com.blackmarlin.downloader.ui

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
import androidx.compose.ui.unit.sp
import com.blackmarlin.downloader.R

object BmdFonts {
    val Sans = FontFamily(Font(R.font.ibm_plex_sans_regular, FontWeight.Normal))
    val Display = FontFamily(Font(R.font.space_grotesk_bold, FontWeight.Bold))
    val Mono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal))
}

// BMD's brand, the desktop theme's colours (crates/app/assets/themes/emerald.toml), 60 / 30 / 10:
//   Kombu Green #354230 (main), Brushed Aluminium #C7CAC5 (second), Signal Orange #FF6A1A (accent).
// Dark: kombu surfaces, aluminium text and icons, orange actions. Light: aluminium surfaces, kombu
// text, icons and selections (the containers), a deeper signal orange (#B84300) for actions, since
// #FF6A1A can't reach 3:1 against a light background.
object BmdBrand {
    val KombuGreen = Color(0xFF354230)
    val BrushedAluminium = Color(0xFFC7CAC5)
    val SignalOrange = Color(0xFFFF6A1A)
    val SignalOrangeDeep = Color(0xFFB84300)
}

private val LightColors = lightColorScheme(
    primary = BmdBrand.SignalOrangeDeep, onPrimary = Color.White,
    primaryContainer = Color(0xFFB9C2B1), onPrimaryContainer = Color(0xFF1D251A),
    secondary = BmdBrand.KombuGreen, onSecondary = Color(0xFFEEF0EB),
    secondaryContainer = Color(0xFFCDD5C6), onSecondaryContainer = Color(0xFF1D251A),
    tertiary = BmdBrand.KombuGreen, onTertiary = Color(0xFFEEF0EB),
    background = Color(0xFFD3D6D1), onBackground = Color(0xFF29331F),
    surface = Color(0xFFE8EAE6), onSurface = Color(0xFF29331F),
    surfaceVariant = Color(0xFFDCE0D8), onSurfaceVariant = Color(0xFF4A5444),
    surfaceContainerLowest = Color(0xFFF1F2EF), surfaceContainerLow = Color(0xFFE8EAE6),
    surfaceContainer = Color(0xFFE0E3DD), surfaceContainerHigh = Color(0xFFD9DDD5),
    surfaceContainerHighest = Color(0xFFC4CABF),
    outline = Color(0xFF6E7868), outlineVariant = Color(0xFFC3C8BF),
    error = Color(0xFFA8002F), errorContainer = Color(0xFFFFD9DE), onErrorContainer = Color(0xFF4F0015),
)

private val DarkColors = darkColorScheme(
    primary = BmdBrand.SignalOrange, onPrimary = Color(0xFF1C1206),
    primaryContainer = Color(0xFF4F5E47), onPrimaryContainer = Color(0xFFF1F3EF),
    secondary = BmdBrand.BrushedAluminium, onSecondary = Color(0xFF1D251A),
    secondaryContainer = Color(0xFF4A5843), onSecondaryContainer = Color(0xFFEEF0EB),
    tertiary = BmdBrand.BrushedAluminium, onTertiary = Color(0xFF1D251A),
    background = Color(0xFF2C3728), onBackground = Color(0xFFDFE2DC),
    surface = BmdBrand.KombuGreen, onSurface = Color(0xFFDFE2DC),
    surfaceVariant = Color(0xFF414E3B), onSurfaceVariant = Color(0xFFB9BDB5),
    surfaceContainerLowest = Color(0xFF252F21), surfaceContainerLow = Color(0xFF2C3728),
    surfaceContainer = Color(0xFF313D2C), surfaceContainerHigh = Color(0xFF3B4836),
    surfaceContainerHighest = Color(0xFF4A5843),
    outline = Color(0xFF8C9686), outlineVariant = Color(0xFF46533F),
    error = Color(0xFFFF9AA6), errorContainer = Color(0xFF5C1424), onErrorContainer = Color(0xFFFFD9DE),
)

private val BaselineTypography = Typography()
private val BmdTypography = BaselineTypography.copy(
    displayLarge = BaselineTypography.displayLarge.copy(fontFamily = BmdFonts.Display),
    displayMedium = BaselineTypography.displayMedium.copy(fontFamily = BmdFonts.Display),
    displaySmall = BaselineTypography.displaySmall.copy(fontFamily = BmdFonts.Display),
    headlineLarge = BaselineTypography.headlineLarge.copy(
        fontFamily = BmdFonts.Display, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
    ),
    headlineMedium = BaselineTypography.headlineMedium.copy(
        fontFamily = BmdFonts.Display, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = BaselineTypography.headlineSmall.copy(fontFamily = BmdFonts.Display),
    titleLarge = BaselineTypography.titleLarge.copy(
        fontFamily = BmdFonts.Display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = BaselineTypography.titleMedium.copy(
        fontFamily = BmdFonts.Display, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = BaselineTypography.titleSmall.copy(fontFamily = BmdFonts.Display),
    bodyLarge = BaselineTypography.bodyLarge.copy(
        fontFamily = BmdFonts.Sans, fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodyMedium = BaselineTypography.bodyMedium.copy(
        fontFamily = BmdFonts.Sans, fontSize = 14.sp, lineHeight = 21.sp,
    ),
    bodySmall = BaselineTypography.bodySmall.copy(fontFamily = BmdFonts.Sans),
    labelLarge = BaselineTypography.labelLarge.copy(
        fontFamily = BmdFonts.Sans, fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = BaselineTypography.labelMedium.copy(fontFamily = BmdFonts.Sans),
    labelSmall = BaselineTypography.labelSmall.copy(fontFamily = BmdFonts.Sans),
)

@Composable
fun BmdTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = BmdTypography,
        content = content,
    )
}
