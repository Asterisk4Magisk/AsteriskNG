package engine.xray

import app.AppState
import app.effectiveLocalDnsEnabled
import features.routing.model.RouteRule
import org.json.JSONArray
import org.json.JSONObject

internal fun buildXrayRouting(
    appState: AppState,
    routeTargets: Map<String, XrayRouteTarget>,
    balancers: List<JSONObject>,
    routeProxyDns: Boolean,
    routeDirectDns: Boolean,
    dnsHijackInboundTags: List<String>,
): JSONObject {
    return JSONObject()
        .put(
            "domainStrategy",
            when (appState.routeDomainStrategy) {
                0 -> "AsIs"
                2 -> "IPOnDemand"
                else -> "IPIfNonMatch"
            },
        )
        .put("rules", appState.routingRules(routeTargets, routeProxyDns, routeDirectDns, dnsHijackInboundTags))
        .apply {
            if (balancers.isNotEmpty()) {
                put("balancers", balancers.toJsonObjectArray())
            }
        }
}

private fun AppState.routingRules(
    routeTargets: Map<String, XrayRouteTarget>,
    routeProxyDns: Boolean,
    routeDirectDns: Boolean,
    dnsHijackInboundTags: List<String>,
): JSONArray {
    return JSONArray().apply {
        if (effectiveLocalDnsEnabled) {
            buildXrayDnsHijackRule(dnsHijackInboundTags)?.let(::put)
        }
        if (routeDirectDns) {
            routeTargets[XrayTags.DIRECT]?.let { target -> put(buildDnsUpstreamRoute(XrayTags.DIRECT_DNS, target)) }
        }
        if (routeProxyDns) {
            routeTargets[XrayTags.PROXY]?.let { target -> put(buildDnsUpstreamRoute(XrayTags.PROXY_DNS, target)) }
        }
        routeRules
            .filter(RouteRule::enabled)
            .mapNotNull { rule -> rule.toXrayRule(routeTargets) }
            .forEach(::put)
        defaultRouteTarget(routeTargets)?.let { target ->
            put(
                target.applyTo(
                    JSONObject()
                        .put("network", "tcp,udp"),
                ),
            )
        }
    }
}

private fun AppState.defaultRouteTarget(routeTargets: Map<String, XrayRouteTarget>): XrayRouteTarget? {
    val defaultOutboundTag = defaultRouteOutboundTag.trim().ifBlank { XrayTags.PROXY }
    val defaultTarget = routeTargets[defaultOutboundTag]?.takeIf {
        defaultOutboundTag !in ReservedDefaultRouteOutboundTags
    }
    return defaultTarget ?: routeTargets[XrayTags.PROXY]
}

internal fun buildXrayDnsHijackRule(inboundTags: List<String>): JSONObject? {
    val tags = inboundTags.map(String::trim).filter(String::isNotEmpty).distinct()
    if (tags.isEmpty()) return null
    return JSONObject()
        .put("inboundTag", tags.toJsonStringArray())
        .put("network", "tcp,udp")
        .put("port", "53")
        .put("outboundTag", XrayTags.DNS_OUT)
}

private fun buildDnsUpstreamRoute(
    inboundTag: String,
    target: XrayRouteTarget,
): JSONObject {
    return target.applyTo(
        JSONObject()
            .put("inboundTag", JSONArray().put(inboundTag)),
    )
}

private fun RouteRule.toXrayRule(routeTargets: Map<String, XrayRouteTarget>): JSONObject? {
    val targetOutboundTag = outboundTag.trim().ifBlank { XrayTags.PROXY }
    val target = routeTargets[targetOutboundTag] ?: return null
    val rule = target.applyTo(JSONObject())
    rule.putJsonStringArrayIfNotEmpty("domain", domain)
    rule.putJsonStringArrayIfNotEmpty("ip", ip)
    rule.putJsonStringArrayIfNotEmpty("process", process)
    rule.putIfNotBlank("port", port)
    rule.putIfNotBlank("network", network)
    rule.putJsonStringArrayIfNotEmpty("protocol", protocol.toCommaSeparatedList())
    rule.putIfNotBlank("ruleTag", remarks)
    return if (rule.length() > 1) rule else null
}

private fun JSONObject.putIfNotBlank(name: String, value: String): JSONObject {
    val trimmed = value.trim()
    if (trimmed.isNotEmpty()) {
        put(name, trimmed)
    }
    return this
}

private fun JSONObject.putJsonStringArrayIfNotEmpty(name: String, values: List<String>): JSONObject {
    val sanitized = values.map(String::trim).filter(String::isNotEmpty).distinct()
    if (sanitized.isNotEmpty()) {
        put(name, sanitized.toJsonStringArray())
    }
    return this
}

private fun String.toCommaSeparatedList(): List<String> {
    return split(",").map(String::trim).filter(String::isNotEmpty).distinct()
}

private val ReservedDefaultRouteOutboundTags = setOf(XrayTags.DNS_OUT, XrayTags.FRAGMENT)
