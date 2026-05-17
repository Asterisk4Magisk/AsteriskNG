// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import data.AndroidAppStateStore

data class AppChromeState(
    val colorMode: Int,
    val languageMode: Int,
    val seedIndex: Int,
)

data class ProxyServerListState(
    val subscriptionGroups: List<SubscriptionGroupState>,
    val enableAllProxyGroup: Boolean,
    val proxyServers: List<ProxyServerState>,
    val nextProxyServerId: Int,
    val selectedProxyServerId: Int,
    val proxyRunning: Boolean,
)

val LocalAppStateStore = staticCompositionLocalOf<AndroidAppStateStore> {
    error("No AndroidAppStateStore provided!")
}

val LocalUpdateAppState = staticCompositionLocalOf<((AppState) -> AppState) -> Unit> {
    error("No AppState updater provided!")
}

val LocalAppChromeState = compositionLocalOf<AppChromeState> {
    error("No AppChromeState provided!")
}

@Composable
fun AndroidAppStateStore.collectAppState(): State<AppState> {
    return state.collectAsState()
}

@Composable
fun AndroidAppStateStore.collectAppChromeState(): State<AppChromeState> {
    val flow = remember(this) {
        state.map { appState -> appState.toAppChromeState() }.distinctUntilChanged()
    }
    return flow.collectAsState(initial = state.value.toAppChromeState())
}

@Composable
fun AndroidAppStateStore.collectProxyServerListState(): State<ProxyServerListState> {
    val flow = remember(this) {
        state.map { appState -> appState.toProxyServerListState() }.distinctUntilChanged()
    }
    return flow.collectAsState(initial = state.value.toProxyServerListState())
}

private fun AppState.toAppChromeState(): AppChromeState {
    return AppChromeState(
        colorMode = colorMode,
        languageMode = languageMode,
        seedIndex = seedIndex,
    )
}

private fun AppState.toProxyServerListState(): ProxyServerListState {
    return ProxyServerListState(
        subscriptionGroups = subscriptionGroups,
        enableAllProxyGroup = enableAllProxyGroup,
        proxyServers = proxyServers,
        nextProxyServerId = nextProxyServerId,
        selectedProxyServerId = selectedProxyServerId,
        proxyRunning = proxyRunning,
    )
}
