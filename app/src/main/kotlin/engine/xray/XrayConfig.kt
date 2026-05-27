package engine.xray

import app.AppState
import app.effectiveLocalDnsEnabled
import app.ProxyServerState
import features.logs.AndroidAppLogger
import features.proxy.server.model.Custom
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.customXrayConfigProxyServerHosts
import org.json.JSONArray
import org.json.JSONObject

internal data class XrayConfigRequest(
    val appState: AppState,
    val selectedServer: ProxyServerState,
    val inbounds: List<JSONObject>,
    val coreLogPaths: XrayCoreLogPaths,
    val proxyDnsServers: List<String> = appState.proxyDns,
    val directDnsServers: List<String> = appState.directDns,
    val directDnsDomains: List<String> = appState.directDnsDomains,
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
        val customServer = request.selectedServer.server as? Custom
        if (customServer != null) {
            return buildCustomXrayConfig(request, customServer)
        }

        val outboundPlan = request.appState.buildXrayOutboundPlan(request.selectedServer)
        val startupProxyServerDomains = if (request.appState.enableDirectDnsForProxyServerDomains) {
            outboundPlan.proxyOutbounds.startupProxyServerDnsDomains()
        } else {
            emptyList()
        }
        val dnsRoutingOptions = request.appState.xrayDnsRoutingOptions(
            proxyDnsServers = request.proxyDnsServers,
            directDnsServers = request.directDnsServers,
            directDnsDomains = request.directDnsDomains,
            startupProxyServerDomains = startupProxyServerDomains,
        )
        val balancers = buildXrayBalancers(outboundPlan.balancers)

        val config = JSONObject()
            .put("log", request.buildXrayLogConfig())
            .put(
                "dns",
                buildXrayDnsConfig(
                    appState = request.appState,
                    proxyDnsServers = request.proxyDnsServers,
                    directDnsServers = request.directDnsServers,
                    directDnsDomains = request.directDnsDomains,
                    dnsHosts = request.dnsHosts,
                    startupProxyServerDomains = startupProxyServerDomains,
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
                    routeProxyDns = dnsRoutingOptions.routeProxyDns,
                    routeDirectDns = dnsRoutingOptions.routeDirectDns,
                    dnsHijackInboundTags = request.dnsHijackInboundTags,
                ),
            )
            .apply {
                putXrayFakeDnsConfig(request.appState)
                buildXrayObservatory(outboundPlan.observatorySelectors)?.let { put("observatory", it) }
                buildXrayBurstObservatory(outboundPlan.burstObservatorySelectors)?.let { put("burstObservatory", it) }
            }
        logGeneratedXrayConfig(config)
        return config.toString()
    }
}

internal object XraySpeedTestConfigFactory {
    fun buildXraySpeedTestConfig(request: XrayConfigRequest): String {
        val customServer = request.selectedServer.server as? Custom
        if (customServer != null) {
            return buildCustomXrayConfig(request, customServer)
        }

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

private fun buildCustomXrayConfig(
    request: XrayConfigRequest,
    server: Custom,
): String {
    server.check()
    val config = JSONObject(server.configJson).apply {
        if (server.overrideAsteriskInboundAndDns) {
            put("inbounds", request.inbounds.toJsonObjectArray())
            overwriteCustomInboundDns(request, server)
        }
    }
    logGeneratedXrayConfig(config)
    return config.toString()
}

private fun JSONObject.overwriteCustomInboundDns(
    request: XrayConfigRequest,
    server: Custom,
): JSONObject {
    val startupProxyServerDomains = if (request.appState.enableDirectDnsForProxyServerDomains) {
        customXrayConfigProxyServerHosts(server.configJson).startupProxyServerHostDnsDomains()
    } else {
        emptyList()
    }
    val dnsRoutingOptions = request.appState.xrayDnsRoutingOptions(
        proxyDnsServers = request.proxyDnsServers,
        directDnsServers = request.directDnsServers,
        directDnsDomains = request.directDnsDomains,
        startupProxyServerDomains = startupProxyServerDomains,
    )
    val enableLocalDns = request.appState.effectiveLocalDnsEnabled
    val proxyDnsOutboundTag = overwriteCustomDnsOutbounds(
        appState = request.appState,
        enableLocalDns = enableLocalDns,
    )

    put(
        "dns",
        buildXrayDnsConfig(
            appState = request.appState,
            proxyDnsServers = request.proxyDnsServers,
            directDnsServers = request.directDnsServers,
            directDnsDomains = request.directDnsDomains,
            dnsHosts = request.dnsHosts,
            startupProxyServerDomains = startupProxyServerDomains,
        ),
    )
    putXrayFakeDnsConfig(request.appState)
    overwriteCustomDnsRouting(
        dnsHijackInboundTags = request.dnsHijackInboundTags,
        enableLocalDns = enableLocalDns,
        routeProxyDns = dnsRoutingOptions.routeProxyDns,
        routeDirectDns = dnsRoutingOptions.routeDirectDns,
        proxyDnsOutboundTag = proxyDnsOutboundTag,
    )
    return this
}

private fun JSONObject.overwriteCustomDnsOutbounds(
    appState: AppState,
    enableLocalDns: Boolean,
): String? {
    val outbounds = optJSONArray("outbounds") ?: JSONArray().also { put("outbounds", it) }
    val proxyOutboundTag = outbounds.customProxyOutboundTag()
    val rewrittenOutbounds = JSONArray()

    for (index in 0 until outbounds.length()) {
        val outbound = outbounds.optJSONObject(index) ?: continue
        when (outbound.optString("tag")) {
            XrayTags.DNS_OUT -> Unit
            XrayTags.DIRECT -> Unit
            else -> rewrittenOutbounds.put(outbound)
        }
    }

    rewrittenOutbounds.put(
        buildFreedomOutbound(
            tag = XrayTags.DIRECT,
            domainStrategy = appState.xrayDirectOutboundDomainStrategy(),
        ),
    )
    if (enableLocalDns) {
        rewrittenOutbounds.put(
            buildSimpleOutbound(
                tag = XrayTags.DNS_OUT,
                protocol = XrayProtocols.DNS,
            ),
        )
    }
    put("outbounds", rewrittenOutbounds)
    return proxyOutboundTag
}

private fun JSONArray.customProxyOutboundTag(): String? {
    var firstProxyCandidate: JSONObject? = null
    for (index in 0 until length()) {
        val outbound = optJSONObject(index) ?: continue
        val tag = outbound.optString("tag").takeIf(String::isNotBlank)
        if (tag == XrayTags.PROXY) {
            return tag
        }
        if (tag !in XrayTags.FIXED_OUTBOUND_TAGS) {
            if (firstProxyCandidate == null) {
                firstProxyCandidate = outbound
            }
        }
    }
    firstProxyCandidate?.let { outbound ->
        val candidateTag = outbound.optString("tag").takeIf(String::isNotBlank)
        if (candidateTag != null) {
            return candidateTag
        }
        outbound.put("tag", XrayTags.PROXY)
        return XrayTags.PROXY
    }
    return null
}

private fun JSONObject.overwriteCustomDnsRouting(
    dnsHijackInboundTags: List<String>,
    enableLocalDns: Boolean,
    routeProxyDns: Boolean,
    routeDirectDns: Boolean,
    proxyDnsOutboundTag: String?,
): JSONObject {
    val routing = optJSONObject("routing") ?: JSONObject().also { put("routing", it) }
    val existingRules = routing.optJSONArray("rules") ?: JSONArray()
    val rewrittenRules = JSONArray()

    if (enableLocalDns) {
        buildXrayDnsHijackRule(dnsHijackInboundTags)?.let(rewrittenRules::put)
    }
    if (routeDirectDns) {
        rewrittenRules.put(buildCustomDnsUpstreamRoute(XrayTags.DIRECT_DNS, XrayTags.DIRECT))
    }
    if (routeProxyDns && !proxyDnsOutboundTag.isNullOrBlank()) {
        rewrittenRules.put(buildCustomDnsUpstreamRoute(XrayTags.PROXY_DNS, proxyDnsOutboundTag))
    }
    for (index in 0 until existingRules.length()) {
        rewrittenRules.put(existingRules.get(index))
    }
    routing.put("rules", rewrittenRules)
    return this
}

private fun buildCustomDnsUpstreamRoute(
    inboundTag: String,
    outboundTag: String,
): JSONObject {
    return JSONObject()
        .put("inboundTag", JSONArray().put(inboundTag))
        .put("outboundTag", outboundTag)
}

private fun logGeneratedXrayConfig(config: JSONObject) {
    val json = config.toString(2)
    val chunks = json.chunked(LogChunkSize)
    chunks.forEachIndexed { index, chunk ->
        val progress = if (chunks.size == 1) "" else " (${index + 1}/${chunks.size})"
        AndroidAppLogger.info(LogTag, "Generated xray config JSON$progress:\n$chunk")
    }
}
