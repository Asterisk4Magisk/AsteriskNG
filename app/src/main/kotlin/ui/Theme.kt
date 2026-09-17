// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui

import android.app.Activity
import android.content.res.Resources
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.R
import app.modes.ColorModeDark
import app.modes.ColorModeLight
import app.modes.ColorModeSystem
import app.modes.ColorModeThemeDark
import app.modes.ColorModeThemeLight
import app.modes.ColorModeThemeSystem
import app.modes.normalizeColorMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

val LocalColorMode = compositionLocalOf<Int> { ColorModeSystem }
private val LocalResolvedDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    colorMode: Int = ColorModeSystem,
    keyColor: Color? = null,
    systemDark: Boolean,
    content: @Composable () -> Unit,
) {
    SynchronizeSplashTheme(colorMode)
    val resolvedDark = when (normalizeColorMode(colorMode)) {
        ColorModeLight -> false
        ColorModeDark -> true
        else -> systemDark
    }
    val controller = remember(colorMode, keyColor, resolvedDark) {
        when (colorMode) {
            ColorModeLight -> ThemeController(ColorSchemeMode.Light)
            ColorModeDark -> ThemeController(ColorSchemeMode.Dark)
            ColorModeThemeSystem -> ThemeController(
                if (resolvedDark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
                keyColor = keyColor,
                colorSpec = AndroidDynamicColorSpec,
                paletteStyle = AndroidDynamicPaletteStyle,
            )

            ColorModeThemeLight -> ThemeController(
                ColorSchemeMode.MonetLight,
                keyColor = keyColor,
                colorSpec = AndroidDynamicColorSpec,
                paletteStyle = AndroidDynamicPaletteStyle,
            )

            ColorModeThemeDark -> ThemeController(
                ColorSchemeMode.MonetDark,
                keyColor = keyColor,
                colorSpec = AndroidDynamicColorSpec,
                paletteStyle = AndroidDynamicPaletteStyle,
            )

            else -> ThemeController(if (resolvedDark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
        }
    }
    CompositionLocalProvider(
        LocalColorMode provides colorMode,
        LocalResolvedDarkTheme provides resolvedDark,
    ) {
        MiuixTheme(controller) {
            SystemBarAppearance(
                statusBarDark = resolvedDark,
                navigationBarDark = systemDark,
            )
            content()
        }
    }
}

@Composable
fun isInDarkTheme(): Boolean = LocalResolvedDarkTheme.current

@Composable
private fun SynchronizeSplashTheme(colorMode: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    if (view.isInEditMode) return
    LaunchedEffect(view, colorMode) {
        val activity = view.context as? Activity ?: return@LaunchedEffect
        val themeId = when (normalizeColorMode(colorMode)) {
            ColorModeLight -> R.style.AppTheme_Starting_Light
            ColorModeDark -> R.style.AppTheme_Starting_Dark
            else -> Resources.ID_NULL
        }
        activity.splashScreen.setSplashScreenTheme(themeId)
    }
}

@Composable
private fun SystemBarAppearance(
    statusBarDark: Boolean,
    navigationBarDark: Boolean,
) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).run {
            isAppearanceLightStatusBars = !statusBarDark
            isAppearanceLightNavigationBars = !navigationBarDark
        }
    }
}

val KeyColors: List<Color> = listOf(
    Color(0xFF3482FF),
    Color(0xFF36D167),
    Color(0xFF7C4DFF),
    Color(0xFFFFB21D),
    Color(0xFFFF5722),
    Color(0xFFFEDFE1),
    Color(0xFF00BCD4),
    Color(0xFF6F4E37),
)

fun keyColorFor(index: Int): Color? = if (index <= 0) null else KeyColors.getOrNull(index - 1)

private val AndroidDynamicColorSpec = ThemeColorSpec.Spec2025
private val AndroidDynamicPaletteStyle = ThemePaletteStyle.TonalSpot
