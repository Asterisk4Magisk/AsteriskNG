// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import app.AppState
import app.effectiveLocalDnsEnabled
import engine.network.NetworkDefaults
import features.proxy.server.model.ProxyServerConstants
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import utils.toIntCoercedInOrDefault

internal fun buildXrayOutbounds(
    appState: AppState,
    proxyOutbounds: List<XrayProxyOutboundServer>,
): JsonArray {
    return buildJsonArray {
        proxyOutbounds.forEach { outboundServer ->
            add(buildProxyOutbound(appState, outboundServer))
        }
        add(buildFreedomOutbound(XrayTags.DIRECT, appState.xrayDirectOutboundDomainStrategy()))
        add(buildSimpleOutbound(XrayTags.BLOCK, XrayProtocols.BLACKHOLE))
        if (appState.effectiveLocalDnsEnabled) {
            add(buildSimpleOutbound(XrayTags.DNS_OUT, XrayProtocols.DNS))
        }
        if (appState.enableFragment) {
            add(buildFragmentOutbound(appState))
        }
    }
}

internal fun buildXrayBalancers(plans: List<XrayBalancerPlan>): List<JsonObject> {
    return plans.map { plan ->
        buildJsonObject {
            put("tag", plan.tag)
            put("selector", listOf(plan.selector).toJsonStringArray())
            put(
                "strategy",
                buildJsonObject {
                    put("type", plan.strategy)
                },
            )
        }
    }
}

internal fun buildXrayObservatory(selectors: List<String>): JsonObject? {
    if (selectors.isEmpty()) return null
    return buildJsonObject {
        put("subjectSelector", selectors.distinct().toJsonStringArray())
        put("probeUrl", XrayObservatoryProbeUrl)
        put("probeInterval", "3m")
        put("enableConcurrency", true)
    }
}

internal fun buildXrayBurstObservatory(selectors: List<String>): JsonObject? {
    if (selectors.isEmpty()) return null
    return buildJsonObject {
        put("subjectSelector", selectors.distinct().toJsonStringArray())
        put(
            "pingConfig",
            buildJsonObject {
                put("destination", XrayObservatoryProbeUrl)
                put("interval", "5m")
                put("sampling", 2)
                put("timeout", "30s")
            },
        )
    }
}

internal fun AppState.xrayDirectOutboundDomainStrategy(): String {
    return when {
        enableIpv6 && enableIpv6Prefer -> "UseIPv6v4"
        enableIpv6 -> "UseIP"
        else -> "UseIPv4"
    }
}

private fun buildProxyOutbound(appState: AppState, outboundServer: XrayProxyOutboundServer): JsonObject {
    val tag = outboundServer.tag
    val server = outboundServer.server
    var outbound = server.toXrayOutbound(tag).toJsonObject()
        .applyProxyOutboundDomainStrategy(appState)
        .updated {
            put("tag", tag)
        }
    outboundServer.dialerProxyTag?.let { dialerProxyTag ->
        outbound = outbound.withDialerProxyTag(dialerProxyTag)
    }
    if (appState.enableMux) {
        outbound = outbound.updated {
            put("mux", buildMuxConfig(appState))
        }
    }
    if (appState.enableFragment && outboundServer.allowFragment) {
        outbound = outbound.updated {
            put(
                "proxySettings",
                buildJsonObject {
                    put("tag", XrayTags.FRAGMENT)
                },
            )
        }
    }
    return outbound
}

private fun JsonObject.applyProxyOutboundDomainStrategy(appState: AppState): JsonObject {
    if (stringValue("protocol") == ProxyServerConstants.PROTOCOL_WIREGUARD) {
        val settings = objectValue("settings") ?: buildJsonObject {}
        return updated {
            put(
                "settings",
                settings.updated {
                    put("domainStrategy", appState.wireguardDomainStrategy())
                },
            )
        }
    }

    return withSockopt {
        put("domainStrategy", appState.xrayDirectOutboundDomainStrategy())
    }
}

internal fun buildSimpleOutbound(tag: String, protocol: String): JsonObject {
    return buildJsonObject {
        put("tag", tag)
        put("protocol", protocol)
    }
}

internal fun buildFreedomOutbound(tag: String, domainStrategy: String): JsonObject {
    return buildJsonObject {
        put("tag", tag)
        put("protocol", XrayProtocols.FREEDOM)
        put(
            "settings",
            buildJsonObject {
                put("domainStrategy", domainStrategy)
            },
        )
    }
}

private fun buildFragmentOutbound(appState: AppState): JsonObject {
    return buildJsonObject {
        put("tag", XrayTags.FRAGMENT)
        put("protocol", XrayProtocols.FREEDOM)
        put(
            "settings",
            buildJsonObject {
                put("domainStrategy", appState.xrayDirectOutboundDomainStrategy())
                put(
                    "fragment",
                    buildJsonObject {
                        put("packets", appState.fragmentPackets.ifBlank { DefaultFragmentPackets })
                        put("length", appState.fragmentLength.ifBlank { DefaultFragmentLength })
                        put("interval", appState.fragmentInterval.ifBlank { DefaultFragmentInterval })
                    },
                )
            },
        )
    }
}

private fun buildMuxConfig(appState: AppState): JsonObject {
    return buildJsonObject {
        put("enabled", true)
        put("concurrency", appState.muxConcurrency.toMuxConcurrency())
        put("xudpConcurrency", appState.muxXudpConcurrency.toMuxXudpConcurrency())
        put("xudpProxyUDP443", appState.muxXudpProxyUdp443.toMuxUdp443Mode())
    }
}

private fun JsonObject.withDialerProxyTag(tag: String): JsonObject {
    return withSockopt {
        put("dialerProxy", tag)
    }
}

private fun JsonObject.withSockopt(block: JsonObjectBuilder.() -> Unit): JsonObject {
    return updatedNestedObject("streamSettings", "sockopt", block)
}

private fun AppState.wireguardDomainStrategy(): String {
    return when {
        enableIpv6 && enableIpv6Prefer -> "ForceIPv6v4"
        enableIpv6 -> "ForceIP"
        else -> "ForceIPv4"
    }
}

private fun String.toMuxConcurrency(): Int {
    return toIntCoercedInOrDefault(-1..MaxMuxConcurrency, default = DefaultMuxConcurrency.toInt())
}

private fun String.toMuxXudpConcurrency(): Int {
    return toIntCoercedInOrDefault(-1..MaxMuxXudpConcurrency, default = DefaultMuxXudpConcurrency.toInt())
}

private fun Int.toMuxUdp443Mode(): String {
    return MuxUdp443Values.getOrElse(this) { MuxUdp443Values.first() }
}

private const val XrayObservatoryProbeUrl = NetworkDefaults.CONNECTIVITY_CHECK_URL
