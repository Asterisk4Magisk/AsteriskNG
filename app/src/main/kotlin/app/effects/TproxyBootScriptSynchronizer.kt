// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import data.AndroidAppStateStore
import features.logs.AndroidAppLogger
import features.proxy.server.model.ProxyServer
import features.routing.model.RouteRule
import features.settings.usecase.TproxyBootScriptResult
import features.settings.usecase.TproxyBootScriptUseCase
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun TproxyBootScriptSynchronizer(
    stateStore: AndroidAppStateStore,
    tproxyBootScriptUseCase: TproxyBootScriptUseCase,
) {
    LaunchedEffect(stateStore, tproxyBootScriptUseCase) {
        stateStore.state
            .map { state -> state.toTproxyBootScriptRefresh() }
            .distinctUntilChanged { previous, next -> previous.signature == next.signature }
            .conflate()
            .collect { refresh ->
                val state = refresh.appState
                if (!state.enableTproxyBootScript) {
                    return@collect
                }
                when (val result = tproxyBootScriptUseCase.refresh(state)) {
                    TproxyBootScriptResult.Success -> Unit
                    TproxyBootScriptResult.MissingServer -> AndroidAppLogger.warn(
                        LogTag,
                        "Skipped TPROXY boot script refresh because no proxy server is selected",
                    )
                    TproxyBootScriptResult.RootUnavailable -> AndroidAppLogger.warn(
                        LogTag,
                        "Skipped TPROXY boot script refresh because root access is unavailable",
                    )
                    is TproxyBootScriptResult.Failed -> AndroidAppLogger.warn(
                        LogTag,
                        "Failed to refresh TPROXY boot script",
                        result.error,
                    )
                }
            }
    }
}

private data class TproxyBootScriptRefresh(
    val appState: AppState,
    val signature: TproxyBootScriptSignature,
)

private data class TproxyBootScriptSignature(
    val enabled: Boolean,
    val selectedProxyServerId: Int,
    val proxyServers: List<TproxyBootScriptProxyServerState>,
    val enableResolveProxyServerDomain: Boolean,
    val routeDomainStrategy: Int,
    val routeRules: List<RouteRule>,
    val coreLogLevel: Int,
    val enableAccessLog: Boolean,
    val enableSniffing: Boolean,
    val enableSniffingRouteOnly: Boolean,
    val enableMux: Boolean,
    val muxConcurrency: String,
    val muxXudpConcurrency: String,
    val muxXudpProxyUdp443: Int,
    val enableFragment: Boolean,
    val fragmentPackets: String,
    val fragmentLength: String,
    val fragmentInterval: String,
    val enableIpv6: Boolean,
    val enableIpv6Prefer: Boolean,
    val enableFakeDns: Boolean,
    val remoteDns: List<String>,
    val domesticDns: List<String>,
    val dnsHosts: List<String>,
    val transparentProxyPort: String,
    val enableSocks5Proxy: Boolean,
    val socks5ProxyPort: String,
    val enableHttpProxy: Boolean,
    val httpProxyPort: String,
    val externalInterfaces: List<String>,
    val ignoredInterfaces: List<String>,
    val privateAddressCidrs: List<String>,
    val proxyAppListMode: Int,
    val proxyAppListSelectedApps: List<String>,
)

private data class TproxyBootScriptProxyServerState(
    val id: Int,
    val groupId: Int,
    val server: ProxyServer<*>,
)

private fun AppState.toTproxyBootScriptRefresh(): TproxyBootScriptRefresh {
    return TproxyBootScriptRefresh(
        appState = this,
        signature = TproxyBootScriptSignature(
            enabled = enableTproxyBootScript,
            selectedProxyServerId = selectedProxyServerId,
            proxyServers = proxyServers.map { proxyServer ->
                TproxyBootScriptProxyServerState(
                    id = proxyServer.id,
                    groupId = proxyServer.groupId,
                    server = proxyServer.server,
                )
            },
            enableResolveProxyServerDomain = enableResolveProxyServerDomain,
            routeDomainStrategy = routeDomainStrategy,
            routeRules = routeRules,
            coreLogLevel = coreLogLevel,
            enableAccessLog = enableAccessLog,
            enableSniffing = enableSniffing,
            enableSniffingRouteOnly = enableSniffingRouteOnly,
            enableMux = enableMux,
            muxConcurrency = muxConcurrency,
            muxXudpConcurrency = muxXudpConcurrency,
            muxXudpProxyUdp443 = muxXudpProxyUdp443,
            enableFragment = enableFragment,
            fragmentPackets = fragmentPackets,
            fragmentLength = fragmentLength,
            fragmentInterval = fragmentInterval,
            enableIpv6 = enableIpv6,
            enableIpv6Prefer = enableIpv6Prefer,
            enableFakeDns = enableFakeDns,
            remoteDns = remoteDns,
            domesticDns = domesticDns,
            dnsHosts = dnsHosts,
            transparentProxyPort = transparentProxyPort,
            enableSocks5Proxy = enableSocks5Proxy,
            socks5ProxyPort = socks5ProxyPort,
            enableHttpProxy = enableHttpProxy,
            httpProxyPort = httpProxyPort,
            externalInterfaces = externalInterfaces,
            ignoredInterfaces = ignoredInterfaces,
            privateAddressCidrs = privateAddressCidrs,
            proxyAppListMode = proxyAppListMode,
            proxyAppListSelectedApps = proxyAppListSelectedApps,
        ),
    )
}

private const val LogTag = "TproxyBootScript"
