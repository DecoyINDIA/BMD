package com.blackmarlin.downloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B61), onPrimary = Color.White,
    primaryContainer = Color(0xFFC5F1E8), onPrimaryContainer = Color(0xFF003D36),
    secondary = Color(0xFF4B6260), secondaryContainer = Color(0xFFDCEAE6),
    background = Color(0xFFF7F8FA), onBackground = Color(0xFF102A2B),
    surface = Color.White, onSurface = Color(0xFF102A2B),
    surfaceVariant = Color(0xFFE9EFED), onSurfaceVariant = Color(0xFF4A605D),
    outline = Color(0xFF70827E), outlineVariant = Color(0xFFD9E2DE),
    error = Color(0xFFAE2929), errorContainer = Color(0xFFFFDAD6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF79D9C7), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF075047), onPrimaryContainer = Color(0xFFB1F3E5),
    secondary = Color(0xFFB3CCC5), secondaryContainer = Color(0xFF304B47),
    background = Color(0xFF081B20), onBackground = Color(0xFFE0F0EB),
    surface = Color(0xFF10272B), onSurface = Color(0xFFE0F0EB),
    surfaceVariant = Color(0xFF1B3638), onSurfaceVariant = Color(0xFFB5CAC5),
    outline = Color(0xFF8BA49E), outlineVariant = Color(0xFF37524E),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF6E1D1D),
)

@Composable
fun BmdTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val baseline = Typography()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = baseline.copy(
            headlineLarge = baseline.headlineLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
            headlineMedium = baseline.headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = baseline.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = baseline.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = baseline.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = baseline.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = baseline.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}
