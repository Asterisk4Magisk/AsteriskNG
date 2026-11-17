// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import app.AppState

/** The part of the app state the tile shows; the tile needs a refresh only when it changes. */
internal data class QuickSettingsTileState(
    val runMode: Int,
    val proxyRunning: Boolean,
    val languageMode: Int,
)

internal fun AppState.quickSettingsTileState(): QuickSettingsTileState =
    QuickSettingsTileState(runMode = runMode, proxyRunning = proxyRunning, languageMode = languageMode)
