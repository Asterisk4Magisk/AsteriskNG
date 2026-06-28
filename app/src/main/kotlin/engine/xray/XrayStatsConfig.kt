// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class XrayStatsApiConfig(
    val listenAddress: String,
    val port: Int,
)

internal fun JsonObject.withXrayStatsApiConfig(config: XrayStatsApiConfig?): JsonObject {
    if (config == null) return this
    val policy = objectValue("policy") ?: buildJsonObject {}
    val api = objectValue("api") ?: buildJsonObject {}
    return updated {
        put("stats", objectValue("stats") ?: buildJsonObject {})
        put("policy", policy.withStatsPolicy())
        put("api", api.withStatsApi(config))
    }
}

internal fun JsonObjectBuilder.putXrayStatsApiConfig(config: XrayStatsApiConfig?) {
    if (config == null) return
    put("stats", buildJsonObject {})
    put(
        "policy",
        buildJsonObject {
            putStatsPolicySystem()
        },
    )
    put(
        "api",
        buildJsonObject {
            put("tag", XrayStatsApiTag)
            put("listen", "${config.listenAddress}:${config.port}")
            putStatsApiServices(listOf(XrayStatsServiceName))
        },
    )
}

private fun JsonObject.withStatsPolicy(): JsonObject {
    val system = objectValue("system") ?: buildJsonObject {}
    return updated {
        put("system", system.updated { putStatsPolicyFlags() })
    }
}

private fun JsonObject.withStatsApi(config: XrayStatsApiConfig): JsonObject {
    val tag = stringValue("tag") ?: XrayStatsApiTag
    val services = arrayValue("services")
        ?.mapNotNull { element -> element.jsonPrimitive.contentOrNull }
        .orEmpty()
        .plus(XrayStatsServiceName)
        .distinct()

    return updated {
        put("tag", tag)
        put("listen", "${config.listenAddress}:${config.port}")
        putStatsApiServices(services)
    }
}

private fun JsonObjectBuilder.putStatsPolicySystem() {
    put("system", buildJsonObject { putStatsPolicyFlags() })
}

private fun JsonObjectBuilder.putStatsPolicyFlags() {
    put("statsOutboundUplink", true)
    put("statsOutboundDownlink", true)
}

private fun JsonObjectBuilder.putStatsApiServices(services: List<String>) {
    put(
        "services",
        buildJsonArray {
            services.forEach { service -> add(JsonPrimitive(service)) }
        },
    )
}

private const val XrayStatsApiTag = "api"
private const val XrayStatsServiceName = "StatsService"
