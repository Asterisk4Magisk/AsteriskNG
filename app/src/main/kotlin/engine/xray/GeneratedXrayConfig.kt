// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal data class GeneratedXrayConfig(
    val log: JsonObject,
    val dns: JsonObject? = null,
    val inbounds: JsonArray,
    val outbounds: JsonArray,
    val routing: JsonObject? = null,
    val fakeDns: JsonElement? = null,
    val observatory: JsonObject? = null,
    val burstObservatory: JsonObject? = null,
    val statsApiConfig: XrayStatsApiConfig? = null,
) {
    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("log", log)
            dns?.let { put("dns", it) }
            put("inbounds", inbounds)
            put("outbounds", outbounds)
            routing?.let { put("routing", it) }
            fakeDns?.let { put("fakeDns", it) }
            observatory?.let { put("observatory", it) }
            burstObservatory?.let { put("burstObservatory", it) }
            putXrayStatsApiConfig(statsApiConfig)
        }
    }
}

internal fun GeneratedXrayConfig.encodeToJsonString(): String {
    return XrayConfigJson.encodeToString(toJsonObject())
}
