// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.modes

const val ColorModeSystem = 0
const val ColorModeLight = 1
const val ColorModeDark = 2
const val ColorModeThemeSystem = 3
const val ColorModeThemeLight = 4
const val ColorModeThemeDark = 5

fun normalizeColorMode(value: Int): Int = when (value) {
    ColorModeThemeSystem -> ColorModeSystem
    ColorModeThemeLight -> ColorModeLight
    ColorModeThemeDark -> ColorModeDark
    ColorModeSystem, ColorModeLight, ColorModeDark -> value
    else -> ColorModeSystem
}

const val LanguageModeSystem = 0
const val LanguageModeEnglish = 1
const val LanguageModeSimplifiedChinese = 2
const val LanguageModeRussian = 3
