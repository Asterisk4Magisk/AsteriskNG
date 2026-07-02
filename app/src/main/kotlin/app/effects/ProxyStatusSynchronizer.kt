// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.AppState
import app.collectAppState
import app.modes.isRootRunMode
import data.AndroidAppStateStore
import engine.proxy.AndroidProxyEngine
import engine.stats.ProxyTrafficStatsService

@Composable
internal fun ProxyStatusSynchronizer(
    stateStore: AndroidAppStateStore,
    proxyEngine: AndroidProxyEngine,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    val appState by stateStore.collectAppState()
    val appContext = LocalContext.current.applicationContext
    var statusSynchronized by remember(stateStore, proxyEngine) { mutableStateOf(false) }

    LaunchedEffect(stateStore, proxyEngine) {
        val currentState = stateStore.state.value
        val shouldCheckRuntime = currentState.runMode.isRootRunMode() || currentState.proxyRunning
        if (shouldCheckRuntime) {
            val status = runCatching { proxyEngine.status(currentState.runMode, currentState) }.getOrNull()
            if (status != null) {
                updateAppState { state ->
                    if (state.proxyRunning == status.running) {
                        state
                    } else {
                        state.copy(proxyRunning = status.running)
                    }
                }
                statusSynchronized = true
            }
        } else {
            statusSynchronized = true
        }
    }

    LaunchedEffect(appContext, appState.enableTrafficStatsNotification) {
        if (!appState.enableTrafficStatsNotification) {
            ProxyTrafficStatsService.reconcile(appContext, null)
        }
    }

    LaunchedEffect(appContext, statusSynchronized, appState.proxyRunning) {
        if (statusSynchronized && !appState.proxyRunning) {
            ProxyTrafficStatsService.reconcile(appContext, null)
        }
    }
}
