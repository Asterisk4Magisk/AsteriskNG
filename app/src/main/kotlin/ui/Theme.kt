// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import app.modes.ColorModeDark
import app.modes.ColorModeLight
import app.modes.ColorModeSystem
import app.modes.ColorModeThemeDark
import app.modes.ColorModeThemeLight
import app.modes.ColorModeThemeSystem
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

val LocalColorMode = compositionLocalOf<Int> { ColorModeSystem }

@Composable
fun AppTheme(
    colorMode: Int = ColorModeSystem,
    keyColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val controller = remember(colorMode, keyColor) {
        when (colorMode) {
            ColorModeLight -> ThemeController(ColorSchemeMode.Light)
            ColorModeDark -> ThemeController(ColorSchemeMode.Dark)
            ColorModeThemeSystem -> ThemeController(
                ColorSchemeMode.MonetSystem,
                keyColor = keyColor,
                colorSpec = ThemeColorSpec.Spec2021,
                paletteStyle = ThemePaletteStyle.Content,
            )

            ColorModeThemeLight -> ThemeController(
                ColorSchemeMode.MonetLight,
                keyColor = keyColor,
                colorSpec = ThemeColorSpec.Spec2021,
                paletteStyle = ThemePaletteStyle.Content,
            )

            ColorModeThemeDark -> ThemeController(
                ColorSchemeMode.MonetDark,
                keyColor = keyColor,
                colorSpec = ThemeColorSpec.Spec2021,
                paletteStyle = ThemePaletteStyle.Content,
            )

            else -> ThemeController(ColorSchemeMode.System)
        }
    }
    CompositionLocalProvider(LocalColorMode provides colorMode) {
        MiuixTheme(controller) {
            content()
        }
    }
}

@Composable
fun isInDarkTheme(): Boolean = when (LocalColorMode.current) {
    ColorModeLight,
    ColorModeThemeLight -> false
    ColorModeDark,
    ColorModeThemeDark -> true
    else -> isSystemInDarkTheme()
}

val KeyColors: List<Color> = listOf(
    Color(0xFF3482FF),
    Color(0xFF36D167),
    Color(0xFF7C4DFF),
    Color(0xFFFFB21D),
    Color(0xFFFF5722),
    Color(0xFFE91E63),
    Color(0xFF00BCD4),
)

fun keyColorFor(index: Int): Color? = if (index <= 0) null else KeyColors.getOrNull(index - 1)
