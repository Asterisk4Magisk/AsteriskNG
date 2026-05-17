package engine.xray

import app.AppState
import app.effectiveFakeDnsEnabled
import app.ProxyServerState
import features.logs.AndroidAppLogger
import features.proxy.server.model.ProxyServer
import org.json.JSONArray
import org.json.JSONObject

internal data class XrayConfigRequest(
    val appState: AppState,
    val selectedServer: ProxyServerState,
    val inbounds: List<JSONObject>,
    val coreLogPaths: XrayCoreLogPaths,
    val remoteDnsServers: List<String> = appState.remoteDns,
    val domesticDnsServers: List<String> = appState.domesticDns,
    val dnsHosts: List<String> = appState.dnsHosts,
    val dnsHijackInboundTags: List<String> = listOf(XrayTags.TUN_INBOUND),
)

internal data class XrayProxyOutboundServer(
    val tag: String,
    val server: ProxyServer<*>,
    val dialerProxyTag: String? = null,
    val allowFragment: Boolean = true,
)

internal object XrayConfigFactory {
    fun buildXrayConfig(request: XrayConfigRequest): String {
        val outboundPlan = request.appState.buildXrayOutboundPlan(request.selectedServer)
        val routeRemoteDns = request.appState
            .xrayRemoteDnsServers(request.remoteDnsServers, request.domesticDnsServers)
            .isNotEmpty()
        val routeDomesticDns = request.appState
            .xrayDomesticDnsServers(request.domesticDnsServers)
            .isNotEmpty()
        val balancers = buildXrayBalancers(outboundPlan.balancers)

        val config = JSONObject()
            .put("log", request.buildXrayLogConfig())
            .put(
                "dns",
                buildXrayDnsConfig(
                    appState = request.appState,
                    remoteDnsServers = request.remoteDnsServers,
                    domesticDnsServers = request.domesticDnsServers,
                    dnsHosts = request.dnsHosts,
                ),
            )
            .put("inbounds", request.inbounds.toJsonObjectArray())
            .put("outbounds", buildXrayOutbounds(request.appState, outboundPlan.proxyOutbounds))
            .put(
                "routing",
                buildXrayRouting(
                    appState = request.appState,
                    routeTargets = outboundPlan.routeTargets,
                    balancers = balancers,
                    routeRemoteDns = routeRemoteDns,
                    routeDomesticDns = routeDomesticDns,
                    dnsHijackInboundTags = request.dnsHijackInboundTags,
                ),
            )
            .apply {
                if (request.appState.effectiveFakeDnsEnabled) {
                    put("fakedns", buildXrayFakeDnsConfig(request.appState))
                }
                buildXrayObservatory(outboundPlan.observatorySelectors)?.let { put("observatory", it) }
                buildXrayBurstObservatory(outboundPlan.burstObservatorySelectors)?.let { put("burstObservatory", it) }
            }
        logGeneratedXrayConfig(config)
        return config.toString()
    }
}

internal object XraySpeedTestConfigFactory {
    fun buildXraySpeedTestConfig(request: XrayConfigRequest): String {
        val speedTestState = request.appState.copy(enableMux = false)
        val outboundPlan = speedTestState.buildXrayOutboundPlan(request.selectedServer)
        val config = JSONObject()
            .put("log", request.copy(appState = speedTestState).buildXrayLogConfig())
            .put("inbounds", JSONArray())
            .put(
                "outbounds",
                buildXrayOutbounds(
                    appState = speedTestState,
                    proxyOutbounds = outboundPlan.proxyOutbounds,
                ),
            )
            .apply {
                buildXrayObservatory(outboundPlan.observatorySelectors)?.let { put("observatory", it) }
                buildXrayBurstObservatory(outboundPlan.burstObservatorySelectors)?.let { put("burstObservatory", it) }
            }
        return config.toString()
    }
}

private const val LogTag = "XrayConfig"
private const val LogChunkSize = 3500

private fun logGeneratedXrayConfig(config: JSONObject) {
    val json = config.toString(2)
    val chunks = json.chunked(LogChunkSize)
    chunks.forEachIndexed { index, chunk ->
        val progress = if (chunks.size == 1) "" else " (${index + 1}/${chunks.size})"
        AndroidAppLogger.info(LogTag, "Generated xray config JSON$progress:\n$chunk")
    }
}
