package me.rerere.fawntavern.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import me.rerere.fawntavern.data.settings.ThemeMode

// ── M3 配色方案（色值来自 material-theme 导出） ──

private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF5C5A59),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFE4E1DE),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF3D3A38),
    secondary = androidx.compose.ui.graphics.Color(0xFF5E5C5A),
    onSecondary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE5E2DF),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF3E3B38),
    tertiary = androidx.compose.ui.graphics.Color(0xFF565350),
    onTertiary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFE0DDDA),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF353230),
    error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
    onError = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    errorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF93000A),
    background = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onBackground = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE6E4E1),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF4A4846),
    outline = androidx.compose.ui.graphics.Color(0xFF7A7775),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFC7C4C1),
    inverseSurface = androidx.compose.ui.graphics.Color(0xFF303030),
    inverseOnSurface = androidx.compose.ui.graphics.Color(0xFFF0EFEE),
    inversePrimary = androidx.compose.ui.graphics.Color(0xFFC9C6C3),
    surfaceDim = androidx.compose.ui.graphics.Color(0xFFDCDAD7),
    surfaceBright = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF7F6F5),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF2F1F0),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFECEBEA),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE6E4E1),
)

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFB8B2AE),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF2D2A28),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF3D3A38),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE4E1DE),
    secondary = androidx.compose.ui.graphics.Color(0xFFB5B0AC),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF2E2B29),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF3E3B38),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFE5E2DF),
    tertiary = androidx.compose.ui.graphics.Color(0xFFADA8A5),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF262320),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF353230),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFE0DDDA),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
    onError = androidx.compose.ui.graphics.Color(0xFF690005),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF93000A),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
    background = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE0DEDB),
    surface = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE0DEDB),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF464442),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC7C4C1),
    outline = androidx.compose.ui.graphics.Color(0xFF8E8B89),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF464442),
    inverseSurface = androidx.compose.ui.graphics.Color(0xFFE0DEDB),
    inverseOnSurface = androidx.compose.ui.graphics.Color(0xFF303030),
    inversePrimary = androidx.compose.ui.graphics.Color(0xFF4A4846),
    surfaceDim = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
    surfaceBright = androidx.compose.ui.graphics.Color(0xFF3A3937),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF040404),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF121211),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF161615),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF20201F),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF2A2A28),
)

@Composable
fun FawnTavernTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    solidBackground: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = if (isDark) DarkColorScheme else LightColorScheme
    // 纯色背景时背景/表面用纯黑纯白（卡片表面保留原值，保证层次不糊在一起）
    val solid = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val colorScheme = base.copy(
        background = if (solidBackground) solid else base.background,
        onBackground = if (solidBackground) (if (isDark) Color(0xFFE0DEDB) else Color(0xFF1C1B1F)) else base.onBackground,
        surface = if (solidBackground) solid else base.surface,
        onSurface = if (solidBackground) (if (isDark) Color(0xFFE0DEDB) else Color(0xFF1C1B1F)) else base.onSurface,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
