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

internal data class XrayConfigRequest(
    val appState: AppState,
    val selectedServer: ProxyServerState,
    val inbounds: List<JsonObject>,
    val coreLogPaths: XrayCoreLogPaths,
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
    fun buildXrayConfig(context: Context, request: XrayConfigRequest): String {
        val customServer = request.selectedServer.server as? Custom
        val config = if (customServer != null) {
            buildCustomXrayConfig(request, customServer)
        } else {
            buildGeneratedXrayConfig(request).toJsonObject()
        }
        if (request.appState.usesFlowMetadata()) {
            return config.withFlowMetadata(context, request.appState).toString()
        }
        return XrayConfigPrettyJson.encodeToString(config).withSingleTrailingLf()
    }
}

internal object XraySpeedTestConfigFactory {
    fun buildXraySpeedTestConfig(request: XrayConfigRequest): String {
        val customServer = request.selectedServer.server as? Custom
        if (customServer != null) {
            val config = buildCustomXrayConfig(request, customServer)
            return XrayConfigPrettyJson.encodeToString(config).withSingleTrailingLf()
        }

        val speedTestState = request.appState.copy(enableMux = false)
        val outboundPlan = speedTestState.buildXrayOutboundPlan(request.selectedServer)
        return GeneratedXrayConfig(
            log = request.copy(appState = speedTestState).buildXrayLogConfig(),
            inbounds = emptyList<JsonObject>().toJsonObjectArray(),
            outbounds = buildXrayOutbounds(
                appState = speedTestState,
                proxyOutbounds = outboundPlan.proxyOutbounds,
                primaryOutboundTag = XrayTags.PROXY,
            ),
            observatory = buildXrayObservatory(outboundPlan.observatorySelectors),
            burstObservatory = buildXrayBurstObservatory(outboundPlan.burstObservatorySelectors),
            statsApiConfig = request.statsApiConfig,
        ).encodeToJsonString()
    }
}

private fun buildGeneratedXrayConfig(request: XrayConfigRequest): GeneratedXrayConfig {
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
        routeProxyDns = dnsPlan.routingOptions.routeProxyDns,
        routeDirectDns = dnsPlan.routingOptions.routeDirectDns,
        dnsHijackInboundTags = request.dnsHijackInboundTags,
    )

    return GeneratedXrayConfig(
        log = request.buildXrayLogConfig(),
        dns = buildXrayDnsConfig(dnsPlan),
        inbounds = request.inbounds.toJsonObjectArray(),
        outbounds = buildXrayOutbounds(
            appState = request.appState,
            proxyOutbounds = outboundPlan.proxyOutbounds,
            primaryOutboundTag = routingPlan.primaryOutboundTag,
        ),
        routing = buildXrayRouting(routingPlan),
        fakeDns = dnsPlan.fakeDns,
        observatory = buildXrayObservatory(outboundPlan.observatorySelectors),
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
