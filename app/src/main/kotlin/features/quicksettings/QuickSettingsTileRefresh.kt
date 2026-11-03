// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import app.AppState
import features.logs.AndroidAppLogger
import java.util.concurrent.atomic.AtomicReference

private const val LogTag = "QuickSettingsTileRefresh"

/** The part of the app state the tile shows; the tile needs a refresh only when it changes. */
internal data class QuickSettingsTileState(
    val runMode: Int,
    val proxyRunning: Boolean,
    val languageMode: Int,
)

internal fun AppState.quickSettingsTileState(): QuickSettingsTileState =
    QuickSettingsTileState(runMode = runMode, proxyRunning = proxyRunning, languageMode = languageMode)

private val tileShownState = AtomicReference<QuickSettingsTileState?>(null)

/**
 * Marks the state that the tile is about to write to the store and then show itself, so the
 * state observer does not rebind the tile only to read the same status again. Call it before
 * the write: the observer runs on another thread and may see the write at once.
 */
internal fun markQuickSettingsTileShowing(state: QuickSettingsTileState) {
    tileShownState.set(state)
}

/**
 * Called by the state observer for every distinct tile state. Every call consumes the mark,
 * so a mark the observer never saw (the write was conflated or changed nothing) cannot skip a
 * later state.
 */
internal fun refreshQuickSettingsTileUnlessShowing(context: Context, state: QuickSettingsTileState) {
    if (tileShownState.getAndSet(null) == state) return
    requestQuickSettingsTileRefresh(context)
}

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
