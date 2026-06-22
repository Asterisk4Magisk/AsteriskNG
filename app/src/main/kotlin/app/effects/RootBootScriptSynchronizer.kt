// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import app.modes.RunModeTproxy
import app.modes.RunModeTun2Socks
import data.AndroidAppStateStore
import engine.proxy.withResolvedDynamicLocalProxyPort
import features.logs.AndroidAppLogger
import features.proxy.server.model.ProxyServer
import features.routing.model.RouteRule
import features.settings.usecase.RootBootScriptResult
import features.settings.usecase.RootBootScriptUseCase
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun RootBootScriptSynchronizer(
    stateStore: AndroidAppStateStore,
    rootBootScriptUseCase: RootBootScriptUseCase,
) {
    LaunchedEffect(stateStore, rootBootScriptUseCase) {
        stateStore.state
            .map { state -> state.toRootBootScriptRefresh() }
            .distinctUntilChanged { previous, next -> previous.signature == next.signature }
            .conflate()
            .collect { refresh ->
                val state = refresh.appState.withResolvedDynamicLocalProxyPort()
                if (state != refresh.appState) {
                    stateStore.update { currentState ->
                        if (currentState == refresh.appState) state else currentState
                    }
                    return@collect
                }
                if (!state.enableRootBootScript || (state.runMode != RunModeTproxy && state.runMode != RunModeTun2Socks)) {
                    return@collect
                }
                when (val result = rootBootScriptUseCase.refresh(state)) {
                    RootBootScriptResult.Success -> Unit
                    RootBootScriptResult.MissingServer -> AndroidAppLogger.warn(
                        LogTag,
                        "Skipped ROOT boot script refresh because no proxy server is selected",
                    )
                    RootBootScriptResult.RootUnavailable -> AndroidAppLogger.warn(
                        LogTag,
                        "Skipped ROOT boot script refresh because root access is unavailable",
                    )
                    is RootBootScriptResult.Failed -> AndroidAppLogger.warn(
                        LogTag,
                        "Failed to refresh ROOT boot script",
                        result.error,
                    )
                }
            }
    }
}

private data class RootBootScriptRefresh(
    val appState: AppState,
    val signature: RootBootScriptSignature,
)

private data class RootBootScriptSignature(
    val enabled: Boolean,
    val runMode: Int,
    val selectedProxyServerId: Int,
    val proxyServers: List<RootBootScriptProxyServerState>,
    val enableResolveProxyServerDomain: Boolean,
    val routeDomainStrategy: Int,
    val defaultRouteOutboundTag: String,
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
    val proxyDns: List<String>,
    val directDns: List<String>,
    val directDnsDomains: List<String>,
    val enableDirectDnsForProxyServerDomains: Boolean,
    val dnsHosts: List<String>,
    val localProxyPort: String,
    val enableDynamicLocalProxyPort: Boolean,
    val localProxyListenAllInterfaces: Boolean,
    val localProxyUsername: String,
    val localProxyPassword: String,
    val transparentProxyPort: String,
    val enableRootIpv6Disabler: Boolean,
    val enableRootEbpfRules: Boolean,
    val enableRootEbpfDirectCidrBypass: Boolean,
    val socks5ProxyPort: String,
    val enableHttpProxy: Boolean,
    val httpProxyPort: String,
    val externalInterfaces: List<String>,
    val ignoredInterfaces: List<String>,
    val privateAddressCidrs: List<String>,
    val proxyAppListMode: Int,
    val proxyAppListSelectedApps: List<String>,
)

private data class RootBootScriptProxyServerState(
    val id: Int,
    val groupId: Int,
    val server: ProxyServer<*>,
)

private fun AppState.toRootBootScriptRefresh(): RootBootScriptRefresh {
    return RootBootScriptRefresh(
        appState = this,
        signature = RootBootScriptSignature(
            enabled = enableRootBootScript,
            runMode = runMode,
            selectedProxyServerId = selectedProxyServerId,
            proxyServers = proxyServers.map { proxyServer ->
                RootBootScriptProxyServerState(
                    id = proxyServer.id,
                    groupId = proxyServer.groupId,
                    server = proxyServer.server,
                )
            },
            enableResolveProxyServerDomain = enableResolveProxyServerDomain,
            routeDomainStrategy = routeDomainStrategy,
            defaultRouteOutboundTag = defaultRouteOutboundTag,
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
            proxyDns = proxyDns,
            directDns = directDns,
            directDnsDomains = directDnsDomains,
            enableDirectDnsForProxyServerDomains = enableDirectDnsForProxyServerDomains,
            dnsHosts = dnsHosts,
            localProxyPort = localProxyPort,
            enableDynamicLocalProxyPort = enableDynamicLocalProxyPort,
            localProxyListenAllInterfaces = localProxyListenAllInterfaces,
            localProxyUsername = localProxyUsername,
            localProxyPassword = localProxyPassword,
            transparentProxyPort = transparentProxyPort,
            enableRootIpv6Disabler = enableRootIpv6Disabler,
            enableRootEbpfRules = enableRootEbpfRules,
            enableRootEbpfDirectCidrBypass = enableRootEbpfDirectCidrBypass,
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

private const val LogTag = "RootBootScript"
