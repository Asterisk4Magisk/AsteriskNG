// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import app.modes.RunModeTproxy
import data.AndroidAppStateStore
import engine.proxy.AndroidProxyEngine

@Composable
internal fun ProxyStatusSynchronizer(
    stateStore: AndroidAppStateStore,
    proxyEngine: AndroidProxyEngine,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    LaunchedEffect(stateStore, proxyEngine) {
        val currentState = stateStore.state.value
        if (currentState.runMode != RunModeTproxy && !currentState.proxyRunning) {
            return@LaunchedEffect
        }
        val status = runCatching { proxyEngine.status(currentState.runMode) }.getOrNull() ?: return@LaunchedEffect
        updateAppState { state ->
            if (state.proxyRunning == status.running) {
                state
            } else {
                state.copy(proxyRunning = status.running)
            }
        }
    }
}
