// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import data.AndroidAppStateStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    val proxyServerListLayout: Int,
    val proxyServerListSort: Int,
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
    val appState = state.collectAsState()
    return remember {
        derivedStateOf { appState.value.toAppChromeState() }
    }
}

@Composable
fun AndroidAppStateStore.collectProxyServerListState(): State<ProxyServerListState> {
    val appState = state.collectAsState()
    return remember {
        derivedStateOf { appState.value.toProxyServerListState() }
    }
}

@Composable
fun AndroidAppStateStore.collectProxyServerLatency(
    serverId: Int,
    initialLatency: String = "",
): State<String> {
    val latencyFlow = remember(this, serverId) {
        state
            .map { appState ->
                appState.proxyServers.firstOrNull { server -> server.id == serverId }?.latency.orEmpty()
            }
            .distinctUntilChanged()
    }
    return latencyFlow.collectAsState(initial = initialLatency)
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
        proxyServerListLayout = proxyServerListLayout,
        proxyServerListSort = proxyServerListSort,
        proxyRunning = proxyRunning,
    )
}
