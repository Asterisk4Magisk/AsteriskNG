// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import android.content.Context
import app.AppState
import app.ProxyServerState
import engine.proxy.usesFlowMetadata
import features.proxy.server.model.Custom
import features.proxy.server.model.ProxyServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class XrayConfigRequest(
    val appState: AppState,
    val selectedServer: ProxyServerState,
    val inbounds: List<JsonObject>,
    val proxyDnsServers: List<String> = appState.proxyDns,
    val directDnsServers: List<String> = appState.directDns,
    val directDnsDomains: List<String> = appState.directDnsDomains,
    val dnsHosts: List<String> = appState.dnsHosts,
    val dnsHijackInboundTags: List<String> = listOf(XrayTags.VPN_TUN_INBOUND),
    val statsApiConfig: XrayStatsApiConfig? = null,
)

internal data class XrayProxyOutboundServer(
    val tag: String,
    val server: ProxyServer<*>,
    val dialerProxyTag: String? = null,
    val allowFragment: Boolean = true,
)

internal object XrayConfigFactory {
    fun buildXrayConfig(
        context: Context,
        request: XrayConfigRequest,
        coreLogPaths: XrayCoreLogPaths,
    ): String {
        val customServer = request.selectedServer.server as? Custom
        val config = if (customServer != null) {
            buildCustomXrayConfig(request, customServer)
        } else {
            buildGeneratedXrayConfig(request, coreLogPaths).toJsonObject()
        }
        if (request.appState.usesFlowMetadata()) {
            return config.withFlowMetadata(context, request.appState).toString()
        }
        return XrayConfigPrettyJson.encodeToString(config).withSingleTrailingLf()
    }
}

internal object XraySpeedTestConfigFactory {
    private val logConfig = buildJsonObject {
        put("logLevel", XrayLogDisabled)
    }

    fun buildXraySpeedTestConfig(request: XrayConfigRequest): String {
        val customServer = request.selectedServer.server as? Custom
        if (customServer != null) {
            val config = buildCustomXrayConfig(request, customServer)
            val logKeys = config.keys.filter { it.equals("log", ignoreCase = true) }.toSet()
            val speedTestConfig = config.updatedWithout(logKeys) {
                put("log", logConfig)
            }
            return XrayConfigPrettyJson.encodeToString(speedTestConfig).withSingleTrailingLf()
        }

        val speedTestState = request.appState.copy(
            enableMux = false,
            defaultRouteOutboundTag = XrayTags.PROXY,
            routeRules = emptyList(),
        )
        val outboundPlan = speedTestState.buildXrayOutboundPlan(request.selectedServer)
        return GeneratedXrayConfig(
            log = logConfig,
            inbounds = emptyList<JsonObject>().toJsonObjectArray(),
            outbounds = buildXrayOutbounds(
                appState = speedTestState,
                proxyOutbounds = outboundPlan.proxyOutbounds,
                primaryOutboundTag = XrayTags.PROXY,
            ),
            statsApiConfig = request.statsApiConfig,
        ).encodeToJsonString()
    }
}

private fun buildGeneratedXrayConfig(
    request: XrayConfigRequest,
    coreLogPaths: XrayCoreLogPaths,
): GeneratedXrayConfig {
    val outboundPlan = request.appState.buildXrayOutboundPlan(request.selectedServer)
    val startupProxyServerDomains = if (request.appState.enableDirectDnsForProxyServerDomains) {
        outboundPlan.proxyOutbounds.startupProxyServerDnsDomains()
    } else {
        emptyList()
    }
    val dnsPlan = request.buildXrayDnsPlan(startupProxyServerDomains)
    val routingPlan = request.appState.buildXrayRoutingPlan(
        routeTargets = outboundPlan.routeTargets,
        balancers = buildXrayBalancers(outboundPlan.balancers),
        failoverRoutes = outboundPlan.failoverRoutes,
        routeProxyDns = dnsPlan.routingOptions.routeProxyDns,
        routeDirectDns = dnsPlan.routingOptions.routeDirectDns,
        dnsHijackInboundTags = request.dnsHijackInboundTags,
    )

    return GeneratedXrayConfig(
        log = request.appState.buildXrayLogConfig(coreLogPaths),
        dns = buildXrayDnsConfig(dnsPlan),
        inbounds = request.inbounds.toJsonObjectArray(),
        outbounds = buildXrayOutbounds(
            appState = request.appState,
            proxyOutbounds = outboundPlan.proxyOutbounds,
            primaryOutboundTag = routingPlan.primaryOutboundTag,
            failoverRoutes = outboundPlan.failoverRoutes,
        ),
        routing = buildXrayRouting(routingPlan),
        fakeDns = dnsPlan.fakeDns,
        observatory = buildXrayObservatory(outboundPlan.observatorySelectors, outboundPlan.hasFailover),
        burstObservatory = buildXrayBurstObservatory(outboundPlan.burstObservatorySelectors),
        statsApiConfig = request.statsApiConfig,
    )
}

private fun buildCustomXrayConfig(
    request: XrayConfigRequest,
    server: Custom,
): JsonObject =
    CustomXrayConfigRewriter.rewrite(request, server)
        .withXrayStatsApiConfig(request.statsApiConfig)
