// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tproxy

import app.AppState
import app.effectiveFakeDnsEnabled
import engine.network.toPortOrNull
import engine.root.RootConfigBuildContext
import engine.root.RootIptablesConfig
import engine.root.RootModeStartConfig
import engine.root.RootStartConfig
import engine.root.buildRootSharedProxyInbounds
import engine.xray.XrayProtocols
import engine.xray.XrayTags
import engine.xray.toJsonStringArray
import engine.xray.xraySniffingDestOverrides
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class TproxyStartConfig(
    override val root: RootStartConfig,
    val tproxyPort: Int,
    val iptablesConfig: RootIptablesConfig,
) : RootModeStartConfig

internal val TproxyBaseIptablesConfig = RootIptablesConfig(
    mark = TproxyFwmark,
    ipv4Table = TproxyRouteTable,
    ipv6Table = TproxyRouteTable,
)

internal fun RootConfigBuildContext.buildTproxyStartConfig(): TproxyStartConfig {
    val appState = this.appState
    val tproxyPort = appState.tproxyPortValue()
    return TproxyStartConfig(
        root = buildRootStartConfig(
            inbounds = appState.buildTproxyInbounds(tproxyPort),
            dnsHijackInboundTags = listOf(XrayTags.TPROXY_INBOUND),
        ),
        tproxyPort = tproxyPort,
        iptablesConfig = buildRootIptablesConfig(
            base = TproxyBaseIptablesConfig,
            ignoredLocalInterfaceNames = setOf(TproxyDummyDevice),
        ),
    )
}

private fun AppState.buildTproxyInbounds(tproxyPort: Int): List<JsonObject> {
    return buildList {
        add(buildTproxyTunnelInbound(this@buildTproxyInbounds, tproxyPort))
        addAll(
            buildRootSharedProxyInbounds(
                socksInboundTag = XrayTags.TPROXY_SOCKS_INBOUND,
                httpInboundTag = XrayTags.TPROXY_HTTP_INBOUND,
            ),
        )
    }
}

private fun buildTproxyTunnelInbound(
    appState: AppState,
    port: Int,
): JsonObject {
    return buildJsonObject {
        put("tag", XrayTags.TPROXY_INBOUND)
        put("port", port)
        put("protocol", XrayProtocols.TUNNEL)
        put(
            "settings",
            buildJsonObject {
                put("allowedNetwork", "tcp,udp")
                put("followRedirect", true)
                put("userLevel", 0)
            },
        )
        put(
            "streamSettings",
            buildJsonObject {
                put(
                    "sockopt",
                    buildJsonObject {
                        put("tproxy", "tproxy")
                    },
                )
            },
        )
        if (appState.enableSniffing) {
            put(
                "sniffing",
                buildJsonObject {
                    put("enabled", true)
                    put("destOverride", xraySniffingDestOverrides(appState.effectiveFakeDnsEnabled).toJsonStringArray())
                    put("routeOnly", appState.enableSniffingRouteOnly)
                },
            )
        }
    }
}

private fun AppState.tproxyPortValue(): Int {
    return transparentProxyPort.toPortOrNull() ?: DefaultTproxyPort
}
