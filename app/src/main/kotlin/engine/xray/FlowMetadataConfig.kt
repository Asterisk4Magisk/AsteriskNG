// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import android.content.Context
import app.AppState
import app.effectiveFakeDnsEnabled
import engine.proxy.FlowMetadataInboundTag
import engine.proxy.flowMetadataOptions
import utils.toJsonStringArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun JsonObject.withFlowMetadata(
    context: Context,
    state: AppState,
): JsonObject {
    val options = context.flowMetadataOptions()
    val inbound = buildJsonObject {
        put("protocol", "socks")
        put("tag", FlowMetadataInboundTag)
        put("listen", options.listenAddress)
        put("port", options.port)
        put(
            "sniffing",
            buildJsonObject {
                put("enabled", state.enableSniffing)
                put("destOverride", xraySniffingDestOverrides(state.effectiveFakeDnsEnabled).toJsonStringArray())
                put("routeOnly", state.enableSniffingRouteOnly)
            },
        )
        putJsonObject("settings") {
            put("auth", "password")
            put("udp", true)
            putJsonArray("accounts") {
                add(
                    buildJsonObject {
                        put("user", options.username)
                        put("pass", options.password)
                    }
                )
            }
        }
    }
    val existing = this["inbounds"]?.jsonArray.orEmpty().filterNot {
        it.jsonObject["tag"]?.jsonPrimitive?.content == FlowMetadataInboundTag
    }
    return JsonObject(this + ("inbounds" to JsonArray(existing + inbound)))
}
