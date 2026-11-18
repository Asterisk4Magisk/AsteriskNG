// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import app.AppState

/** The part of the app state the tile shows; the tile needs a refresh only when it changes. */
internal data class QuickSettingsTileState(
    val runMode: Int,
    val proxyRunning: Boolean,
    val languageMode: Int,
    val activeNodeName: String?,
)

internal fun AppState.quickSettingsTileState(): QuickSettingsTileState =
    QuickSettingsTileState(runMode, proxyRunning, languageMode, quickSettingsActiveNodeName())

internal fun AppState.quickSettingsActiveNodeName(): String? =
    proxyServers.firstOrNull { server -> server.id == selectedProxyServerId }
        ?.server
        ?.getInfo()
        ?.remarks
        ?.takeIf(String::isNotBlank)
