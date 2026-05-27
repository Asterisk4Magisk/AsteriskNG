package engine.xray

import app.AppState
import app.effectiveLocalDnsEnabled
import engine.network.NetworkDefaults
import features.proxy.server.model.ProxyServerConstants
import org.json.JSONArray
import org.json.JSONObject

internal fun buildXrayOutbounds(
    appState: AppState,
    proxyOutbounds: List<XrayProxyOutboundServer>,
): JSONArray {
    return JSONArray().apply {
        proxyOutbounds.forEach { outboundServer ->
            put(buildProxyOutbound(appState, outboundServer))
        }
        put(buildFreedomOutbound(XrayTags.DIRECT, appState.xrayDirectOutboundDomainStrategy()))
        put(buildSimpleOutbound(XrayTags.BLOCK, XrayProtocols.BLACKHOLE))
        if (appState.effectiveLocalDnsEnabled) {
            put(buildSimpleOutbound(XrayTags.DNS_OUT, XrayProtocols.DNS))
        }
        if (appState.enableFragment) {
            put(buildFragmentOutbound(appState))
        }
    }
}

internal fun buildXrayBalancers(plans: List<XrayBalancerPlan>): List<JSONObject> {
    return plans.map { plan ->
        JSONObject()
            .put("tag", plan.tag)
            .put("selector", JSONArray().put(plan.selector))
            .put(
                "strategy",
                JSONObject()
                    .put("type", plan.strategy),
            )
    }
}

internal fun buildXrayObservatory(selectors: List<String>): JSONObject? {
    if (selectors.isEmpty()) return null
    return JSONObject()
        .put("subjectSelector", JSONArray().apply { selectors.distinct().forEach(::put) })
        .put("probeUrl", XrayObservatoryProbeUrl)
        .put("probeInterval", "3m")
        .put("enableConcurrency", true)
}

internal fun buildXrayBurstObservatory(selectors: List<String>): JSONObject? {
    if (selectors.isEmpty()) return null
    return JSONObject()
        .put("subjectSelector", JSONArray().apply { selectors.distinct().forEach(::put) })
        .put(
            "pingConfig",
            JSONObject()
                .put("destination", XrayObservatoryProbeUrl)
                .put("interval", "5m")
                .put("sampling", 2)
                .put("timeout", "30s"),
        )
}

internal fun AppState.xrayDirectOutboundDomainStrategy(): String {
    return when {
        enableIpv6 && enableIpv6Prefer -> "UseIPv6v4"
        enableIpv6 -> "UseIP"
        else -> "UseIPv4"
    }
}

private fun buildProxyOutbound(appState: AppState, outboundServer: XrayProxyOutboundServer): JSONObject {
    val tag = outboundServer.tag
    val server = outboundServer.server
    val outbound = JSONObject(server.toXrayOutbound(tag).toJsonObject().toString())
        .applyProxyOutboundDomainStrategy(appState)
    outbound.put("tag", tag)
    outboundServer.dialerProxyTag?.let { dialerProxyTag ->
        outbound.putDialerProxyTag(dialerProxyTag)
    }
    if (appState.enableMux) {
        outbound.put("mux", buildMuxConfig(appState))
    }
    if (appState.enableFragment && outboundServer.allowFragment) {
        outbound.put(
            "proxySettings",
            JSONObject()
                .put("tag", XrayTags.FRAGMENT),
        )
    }
    return outbound
}

private fun JSONObject.applyProxyOutboundDomainStrategy(appState: AppState): JSONObject {
    if (optString("protocol") == ProxyServerConstants.PROTOCOL_WIREGUARD) {
        val settings = optJSONObject("settings") ?: JSONObject().also { put("settings", it) }
        settings.put("domainStrategy", appState.wireguardDomainStrategy())
        return this
    }

    val streamSettings = optJSONObject("streamSettings") ?: JSONObject().also { put("streamSettings", it) }
    val sockopt = streamSettings.optJSONObject("sockopt") ?: JSONObject().also { streamSettings.put("sockopt", it) }
    sockopt.put("domainStrategy", appState.xrayDirectOutboundDomainStrategy())
    return this
}

internal fun buildSimpleOutbound(tag: String, protocol: String): JSONObject {
    return JSONObject()
        .put("tag", tag)
        .put("protocol", protocol)
}

internal fun buildFreedomOutbound(tag: String, domainStrategy: String): JSONObject {
    return JSONObject()
        .put("tag", tag)
        .put("protocol", XrayProtocols.FREEDOM)
        .put(
            "settings",
            JSONObject()
                .put("domainStrategy", domainStrategy),
        )
}

private fun buildFragmentOutbound(appState: AppState): JSONObject {
    return JSONObject()
        .put("tag", XrayTags.FRAGMENT)
        .put("protocol", XrayProtocols.FREEDOM)
        .put(
            "settings",
            JSONObject()
                .put("domainStrategy", appState.xrayDirectOutboundDomainStrategy())
                .put(
                    "fragment",
                    JSONObject()
                        .put("packets", appState.fragmentPackets.ifBlank { DefaultFragmentPackets })
                        .put("length", appState.fragmentLength.ifBlank { DefaultFragmentLength })
                        .put("interval", appState.fragmentInterval.ifBlank { DefaultFragmentInterval }),
                ),
        )
}

private fun buildMuxConfig(appState: AppState): JSONObject {
    return JSONObject()
        .put("enabled", true)
        .put("concurrency", appState.muxConcurrency.toMuxConcurrency())
        .put("xudpConcurrency", appState.muxXudpConcurrency.toMuxXudpConcurrency())
        .put("xudpProxyUDP443", appState.muxXudpProxyUdp443.toMuxUdp443Mode())
}

private fun JSONObject.putDialerProxyTag(tag: String): JSONObject {
    val streamSettings = optJSONObject("streamSettings") ?: JSONObject().also { put("streamSettings", it) }
    val sockopt = streamSettings.optJSONObject("sockopt") ?: JSONObject().also { streamSettings.put("sockopt", it) }
    sockopt.put("dialerProxy", tag)
    return this
}

private fun AppState.wireguardDomainStrategy(): String {
    return when {
        enableIpv6 && enableIpv6Prefer -> "ForceIPv6v4"
        enableIpv6 -> "ForceIP"
        else -> "ForceIPv4"
    }
}

private fun String.toMuxConcurrency(): Int {
    return toIntOrNull()?.coerceIn(-1, MaxMuxConcurrency) ?: DefaultMuxConcurrency.toInt()
}

private fun String.toMuxXudpConcurrency(): Int {
    return toIntOrNull()?.coerceIn(-1, MaxMuxXudpConcurrency) ?: DefaultMuxXudpConcurrency.toInt()
}

private fun Int.toMuxUdp443Mode(): String {
    return MuxUdp443Values.getOrElse(this) { MuxUdp443Values.first() }
}

private const val XrayObservatoryProbeUrl = NetworkDefaults.CONNECTIVITY_CHECK_URL
