// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import app.AppState
import engine.network.NetworkDefaults
import engine.network.toPortOrNull
import engine.xray.XrayProtocols
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val RootSharedProxyListenAddress = NetworkDefaults.IPV4_ANY_ADDRESS

internal fun AppState.buildRootSharedProxyInbounds(
    httpInboundTag: String,
): List<JsonObject> {
    return buildList {
        httpProxyPort.toPortOrNull()
            ?.takeIf { enableHttpProxy }
            ?.let { port -> add(buildRootHttpProxyInbound(httpInboundTag, port)) }
    }
}

private fun buildRootHttpProxyInbound(
    tag: String,
    port: Int,
): JsonObject {
    return buildJsonObject {
        put("tag", tag)
        put("listen", RootSharedProxyListenAddress)
        put("port", port)
        put("protocol", XrayProtocols.HTTP)
        put(
            "settings",
            buildJsonObject {
                put("allowTransparent", false)
                put("userLevel", 0)
            },
        )
    }
}
