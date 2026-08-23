package io.agents.arya.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class AryaPalette(
    val accent: Color,
    val accentSoft: Color,
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val hairline: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val success: Color,
    val warning: Color,
    val orbInner: Color,
    val orbMid: Color,
    val orbOuter: Color,
)

val LocalAryaPalette = staticCompositionLocalOf {
    AryaPalette(
        accent = Color(0xFF007AFF),
        accentSoft = Color(0x1A007AFF),
        canvas = Color(0xFFF2F2F7),
        surface = Color.White,
        surfaceElevated = Color.White,
        hairline = Color(0x14000000),
        text = Color(0xFF1C1C1E),
        textSecondary = Color(0xFF8E8E93),
        textTertiary = Color(0xFFAEAEB2),
        success = Color(0xFF34C759),
        warning = Color(0xFFFF9500),
        orbInner = Color(0xFF5AC8FA),
        orbMid = Color(0xFF007AFF),
        orbOuter = Color(0xFFAF52DE),
    )
}

private val LightPalette = AryaPalette(
    accent = Color(0xFF007AFF),
    accentSoft = Color(0x1A007AFF),
    canvas = Color(0xFFF2F2F7),
    surface = Color.White,
    surfaceElevated = Color(0xFFFFFFFF),
    hairline = Color(0x1A3C3C43),
    text = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFFAEAEB2),
    success = Color(0xFF34C759),
    warning = Color(0xFFFF9500),
    orbInner = Color(0xFF64D2FF),
    orbMid = Color(0xFF0A84FF),
    orbOuter = Color(0xFFBF5AF2),
)

private val DarkPalette = AryaPalette(
    accent = Color(0xFF0A84FF),
    accentSoft = Color(0x330A84FF),
    canvas = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceElevated = Color(0xFF2C2C2E),
    hairline = Color(0x33FFFFFF),
    text = Color(0xFFF2F2F7),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFF636366),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    orbInner = Color(0xFF64D2FF),
    orbMid = Color(0xFF0A84FF),
    orbOuter = Color(0xFFBF5AF2),
)

@Composable
fun AryaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.canvas,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.hairline,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.canvas,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.hairline,
        )
    }
    CompositionLocalProvider(LocalAryaPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object AryaDimens {
    val page = 20.dp
    val card = 20.dp
    val orb = 92.dp
    val orbHero = 168.dp
}

val AryaTitleWeight = FontWeight.SemiBold
