// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.AppState
import app.collectAppState
import app.modes.isRootRunMode
import data.AndroidAppStateStore
import engine.proxy.AndroidProxyEngine
import engine.proxy.ProxyEngineStatus
import engine.stats.ProxyTrafficStatsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy

@Composable
internal fun ProxyStatusSynchronizer(
    stateStore: AndroidAppStateStore,
    proxyEngine: AndroidProxyEngine,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    val appState by stateStore.collectAppState()
    val appContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var foregroundSyncGeneration by remember(stateStore, proxyEngine) { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner, stateStore, proxyEngine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foregroundSyncGeneration += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(stateStore, proxyEngine, foregroundSyncGeneration) {
        observeProxyStatus(
            states = stateStore.state,
            readStatus = { snapshot ->
                runCatching { proxyEngine.status(snapshot.runMode, snapshot) }.getOrNull()
            },
            updateAppState = updateAppState,
        )
    }

    LaunchedEffect(appContext, appState.enableTrafficStatsNotification) {
        if (!appState.enableTrafficStatsNotification) {
            ProxyTrafficStatsService.reconcile(appContext, null)
        }
    }

    LaunchedEffect(appContext, appState.proxyRunning) {
        if (!appState.proxyRunning) {
            ProxyTrafficStatsService.reconcile(appContext, null)
        }
    }
}

internal suspend fun observeProxyStatus(
    states: Flow<AppState>,
    readStatus: suspend (AppState) -> ProxyEngineStatus?,
    updateAppState: (((AppState) -> AppState) -> Unit),
) {
    states
        .distinctUntilChangedBy { state -> state.runMode to state.proxyRunning }
        .collect { snapshot ->
            synchronizeProxyStatus(
                currentState = { snapshot },
                readStatus = readStatus,
                updateAppState = updateAppState,
            )
        }
}

internal suspend fun synchronizeProxyStatus(
    currentState: () -> AppState,
    readStatus: suspend (AppState) -> ProxyEngineStatus?,
    updateAppState: (((AppState) -> AppState) -> Unit),
): Boolean {
    val snapshot = currentState()
    val shouldCheckRuntime = snapshot.runMode.isRootRunMode() || snapshot.proxyRunning
    if (!shouldCheckRuntime) return true

    val status = readStatus(snapshot) ?: return false
    updateAppState { state ->
        if (state.runMode != snapshot.runMode || state.proxyRunning != snapshot.proxyRunning) {
            return@updateAppState state
        }
        val synchronizedRunMode = status.runMode
            ?.takeIf { status.running && it.isRootRunMode() }
            ?: state.runMode
        if (state.proxyRunning == status.running && state.runMode == synchronizedRunMode) {
            state
        } else {
            state.copy(
                runMode = synchronizedRunMode,
                proxyRunning = status.running,
            )
        }
    }
    return true
}
