// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import app.AppState
import app.effectiveLocalDnsEnabled
import features.proxy.server.model.Custom
import features.proxy.server.model.customXrayConfigProxyServerHosts
import features.proxy.server.model.parseCustomXrayConfigJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CustomXrayConfigRewriter {
    fun rewrite(
        request: XrayConfigRequest,
        server: Custom,
    ): JsonObject {
        server.check()
        val config = parseCustomXrayConfigJsonObject(server.configJson)
        return if (server.overrideAsteriskInboundAndDns) {
            config.overwriteAsteriskInboundDns(request, server)
        } else {
            config
        }
    }
}

private fun JsonObject.overwriteAsteriskInboundDns(
    request: XrayConfigRequest,
    server: Custom,
): JsonObject {
    val startupProxyServerDomains = if (request.appState.enableDirectDnsForProxyServerDomains) {
        customXrayConfigProxyServerHosts(server.configJson).startupProxyServerHostDnsDomains()
    } else {
        emptyList()
    }
    val dnsPlan = request.buildXrayDnsPlan(startupProxyServerDomains)
    val outboundsRewrite = rewriteCustomDnsOutbounds(
        appState = request.appState,
        enableLocalDns = request.appState.effectiveLocalDnsEnabled,
    )
    val routing = rewriteCustomDnsRouting(
        CustomDnsRoutingPlan(
            dnsHijackInboundTags = request.dnsHijackInboundTags,
            enableLocalDns = request.appState.effectiveLocalDnsEnabled,
            routeProxyDns = dnsPlan.routingOptions.routeProxyDns,
            routeDirectDns = dnsPlan.routingOptions.routeDirectDns,
            proxyDnsOutboundTag = outboundsRewrite.proxyOutboundTag,
        ),
    )

    return updatedWithout(setOf("fakedns")) {
        put("inbounds", request.inbounds.toJsonObjectArray())
        put("dns", buildXrayDnsConfig(dnsPlan))
        putIfNotNull("fakedns", dnsPlan.fakeDns)
        put("outbounds", outboundsRewrite.outbounds)
        put("routing", routing)
    }
}

private data class CustomOutboundsRewrite(
    val outbounds: JsonArray,
    val proxyOutboundTag: String?,
)

private fun JsonObject.rewriteCustomDnsOutbounds(
    appState: AppState,
    enableLocalDns: Boolean,
): CustomOutboundsRewrite {
    val proxyOutbounds = (arrayValue("outbounds") ?: buildJsonArray {}).withProxyOutboundTag()
    val rewrittenOutbounds = buildJsonArray {
        proxyOutbounds.outbounds.forEach { outbound ->
            when (outbound.stringValue("tag")) {
                XrayTags.DNS_OUT -> Unit
                XrayTags.DIRECT -> Unit
                else -> add(outbound)
            }
        }
        add(
            buildFreedomOutbound(
                tag = XrayTags.DIRECT,
                domainStrategy = appState.xrayDirectOutboundDomainStrategy(),
            ),
        )
        if (enableLocalDns) {
            add(
                buildSimpleOutbound(
                    tag = XrayTags.DNS_OUT,
                    protocol = XrayProtocols.DNS,
                ),
            )
        }
    }
    return CustomOutboundsRewrite(
        outbounds = rewrittenOutbounds,
        proxyOutboundTag = proxyOutbounds.proxyOutboundTag,
    )
}

private data class CustomProxyOutbounds(
    val outbounds: List<JsonObject>,
    val proxyOutboundTag: String?,
)

private fun JsonArray.withProxyOutboundTag(): CustomProxyOutbounds {
    val outbounds = mapNotNull { element -> element as? JsonObject }.toMutableList()
    var firstProxyCandidateIndex: Int? = null
    outbounds.forEachIndexed { index, outbound ->
        val tag = outbound.stringValue("tag")
        if (tag == XrayTags.PROXY) {
            return CustomProxyOutbounds(outbounds, tag)
        }
        if (tag !in XrayTags.FIXED_OUTBOUND_TAGS && firstProxyCandidateIndex == null) {
            firstProxyCandidateIndex = index
        }
    }

    val candidateIndex = firstProxyCandidateIndex ?: return CustomProxyOutbounds(outbounds, null)
    val candidate = outbounds[candidateIndex]
    val candidateTag = candidate.stringValue("tag")
    if (!candidateTag.isNullOrBlank()) {
        return CustomProxyOutbounds(outbounds, candidateTag)
    }
    outbounds[candidateIndex] = candidate.updated {
        put("tag", XrayTags.PROXY)
    }
    return CustomProxyOutbounds(outbounds, XrayTags.PROXY)
}

private data class CustomDnsRoutingPlan(
    val dnsHijackInboundTags: List<String>,
    val enableLocalDns: Boolean,
    val routeProxyDns: Boolean,
    val routeDirectDns: Boolean,
    val proxyDnsOutboundTag: String?,
)

private fun JsonObject.rewriteCustomDnsRouting(plan: CustomDnsRoutingPlan): JsonObject {
    val routing = objectValue("routing") ?: buildJsonObject {}
    val existingRules = routing.arrayValue("rules") ?: buildJsonArray {}
    return routing.updated {
        put(
            "rules",
            buildJsonArray {
                if (plan.enableLocalDns) {
                    buildXrayDnsHijackRule(plan.dnsHijackInboundTags)?.let(::add)
                }
                if (plan.routeDirectDns) {
                    add(buildCustomDnsUpstreamRoute(XrayTags.DIRECT_DNS, XrayTags.DIRECT))
                }
                if (plan.routeProxyDns && !plan.proxyDnsOutboundTag.isNullOrBlank()) {
                    add(buildCustomDnsUpstreamRoute(XrayTags.PROXY_DNS, plan.proxyDnsOutboundTag))
                }
                existingRules.forEach(::add)
            },
        )
    }
}

private fun buildCustomDnsUpstreamRoute(
    inboundTag: String,
    outboundTag: String,
): JsonObject {
    return buildJsonObject {
        put("inboundTag", listOf(inboundTag).toJsonStringArray())
        put("outboundTag", outboundTag)
    }
}
