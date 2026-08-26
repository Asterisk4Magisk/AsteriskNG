// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import app.AppState
import app.effectiveFakeDnsEnabled
import app.rootIpv6DataPathEnabled
import engine.network.NetworkLimits
import engine.network.toPortOrNull
import engine.proxy.LocalProxyOptions
import engine.proxy.buildLocalSocksInbound
import engine.proxy.toLocalProxyOptions
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootModeStartConfig
import engine.root.config.buildAsteriskdConfig
import engine.root.config.buildRootSharedProxyInbounds
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.xray.XrayProtocols
import engine.xray.XrayTags
import engine.xray.toJsonStringArray
import engine.xray.xraySniffingDestOverrides
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun RootConfigBuildContext.buildTproxyStartConfig(): RootModeStartConfig {
    val appState = this.appState
    val tproxyPort = appState.tproxyPortValue()
    val iptablesConfig = buildRootIptablesConfig()
    val rootStartConfig = buildRootStartConfig(
            inbounds = appState.buildTproxyInbounds(appState.toLocalProxyOptions(), tproxyPort),
            dnsHijackInboundTags = listOf(XrayTags.TPROXY_INBOUND),
        )
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Tproxy,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = emptyList(),
            modeOptions = AsteriskdModeOptions(
                transparentPort = tproxyPort,
                tunnelName = null,
            ),
        ),
    )
}

internal const val DefaultTproxyPort = NetworkLimits.PORT_MAX

private fun AppState.buildTproxyInbounds(
    localProxyOptions: LocalProxyOptions,
    tproxyPort: Int,
): List<JsonObject> {
    return buildList {
        add(buildTproxyTunnelInbound(this@buildTproxyInbounds, tproxyPort))
        add(buildLocalSocksInbound(this@buildTproxyInbounds, XrayTags.LOCAL_SOCKS_INBOUND, localProxyOptions))
        addAll(
            buildRootSharedProxyInbounds(
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
        if (appState.rootIpv6DataPathEnabled) {
            put("listen", "::")
        }
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
