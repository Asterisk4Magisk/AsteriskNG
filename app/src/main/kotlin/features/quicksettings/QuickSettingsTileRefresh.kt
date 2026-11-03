// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import features.logs.AndroidAppLogger

private const val LogTag = "QuickSettingsTileRefresh"

/** Asks SystemUI to bind the tile, which then reads the current proxy status itself. */
internal fun requestQuickSettingsTileRefresh(context: Context) {
    runCatching {
        TileService.requestListeningState(
            context,
            ComponentName(context, ProxyQuickSettingsTileService::class.java),
        )
    }.onFailure { error ->
        AndroidAppLogger.warn(LogTag, "Failed to request quick settings tile refresh", error)
    }
}
