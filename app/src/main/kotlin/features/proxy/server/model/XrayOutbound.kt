// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import engine.network.NetworkLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class OutboundObject(
    val tag: String,
    val protocol: String,
    val settings: JsonObject = buildJsonObject {},
    val streamSettings: JsonObject? = null,
    val mux: JsonObject? = null,
) {
    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("tag", tag)
            put("protocol", protocol)
            put("settings", settings)
            streamSettings?.let { put("streamSettings", it) }
            mux?.let { put("mux", it) }
        }
    }
}

internal fun String.toXrayPort(): Int {
    return toIntOrNull()?.takeIf { it in NetworkLimits.PORT_MIN..NetworkLimits.PORT_MAX }
        ?: proxyValidationError(
            ProxyServerValidationError.PortOutOfRange,
            NetworkLimits.PORT_MIN,
            NetworkLimits.PORT_MAX,
        )
}

internal fun JsonObjectBuilder.putIfNotBlank(name: String, value: String?) {
    if (!value.isNullOrBlank()) {
        put(name, value)
    }
}

internal fun JsonObjectBuilder.putJsonArrayIfNotBlank(name: String, value: String?) {
    val values = value?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
    if (values.isNotEmpty()) {
        putJsonArray(name) {
            values.forEach { add(it) }
        }
    }
}

internal fun V2RayParameters.toXrayStreamSettings(): JsonObject {
    val network = type.toXrayNetwork()
    return buildJsonObject {
        put("network", network)
        when (security) {
            "tls" -> {
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    putIfNotBlank("serverName", sni)
                    putIfNotBlank("fingerprint", fp)
                    putJsonArrayIfNotBlank("alpn", alpn)
                    putIfNotBlank("echConfigList", ech)
                    putIfNotBlank("pinnedPeerCertSha256", pcs)
                    putIfNotBlank("verifyPeerCertByName", vcn)
                }
            }

            "reality" -> {
                put("security", "reality")
                putJsonObject("realitySettings") {
                    putIfNotBlank("serverName", sni)
                    putIfNotBlank("fingerprint", fp)
                    putIfNotBlank("password", pbk)
                    putIfNotBlank("shortId", sid)
                    putIfNotBlank("mldsa65Verify", pqv)
                    putIfNotBlank("spiderX", spx)
                }
            }
        }

        when (network) {
            "raw" -> putRawSettings(this@toXrayStreamSettings)
            "websocket" -> putWsSettings(this@toXrayStreamSettings)
            "grpc" -> putGrpcSettings(this@toXrayStreamSettings)
            "httpupgrade" -> putHttpUpgradeSettings(this@toXrayStreamSettings)
            "xhttp" -> putXhttpSettings(this@toXrayStreamSettings)
            "mkcp" -> putKcpSettings(this@toXrayStreamSettings)
        }

        val finalMask = fm.toXrayJsonObjectOrNull("FinalMask")
        if (finalMask != null) {
            put("finalmask", finalMask)
        }
    }
}

private fun String.toXrayNetwork(): String {
    return when (ifBlank { "raw" }) {
        "tcp", "raw" -> "raw"
        "kcp", "mkcp" -> "mkcp"
        "ws", "websocket" -> "websocket"
        "grpc" -> "grpc"
        "httpupgrade" -> "httpupgrade"
        "xhttp", "splithttp" -> "xhttp"
        "http", "h2", "h3" -> throw UnsupportedOperationException("Unsupported Xray transport type: $this")
        else -> throw UnsupportedOperationException("Unknown Xray transport type: $this")
    }
}

private fun JsonObjectBuilder.putRawSettings(params: V2RayParameters) {
    val headerType = params.headerType.orEmpty()
    if (headerType.isNotBlank() && headerType != "none") {
        putJsonObject("rawSettings") {
            putJsonObject("header") {
                put("type", headerType)
                if (headerType == "http") {
                    putJsonObject("request") {
                        putJsonArrayIfNotBlank("path", params.path)
                        val hostValues = params.host.toXrayCsvValues()
                        if (hostValues.isNotEmpty()) {
                            putJsonObject("headers") {
                                putJsonArray("Host") {
                                    hostValues.forEach { add(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun JsonObjectBuilder.putWsSettings(params: V2RayParameters) {
    putJsonObject("wsSettings") {
        putIfNotBlank("path", params.path)
        putIfNotBlank("host", params.host)
    }
}

private fun JsonObjectBuilder.putGrpcSettings(params: V2RayParameters) {
    putJsonObject("grpcSettings") {
        putIfNotBlank("serviceName", params.serviceName)
        putIfNotBlank("authority", params.authority)
        if (params.mode == "multi") {
            put("multiMode", true)
        }
    }
}

private fun JsonObjectBuilder.putHttpUpgradeSettings(params: V2RayParameters) {
    putJsonObject("httpupgradeSettings") {
        putIfNotBlank("path", params.path)
        putIfNotBlank("host", params.host)
    }
}

private fun JsonObjectBuilder.putXhttpSettings(params: V2RayParameters) {
    putJsonObject("xhttpSettings") {
        putIfNotBlank("path", params.path)
        putIfNotBlank("host", params.host)
        putIfNotBlank("mode", params.mode)
        val extra = params.extra.toXrayJsonObjectOrNull("XHTTP Extra")
        if (extra != null) {
            put("extra", extra)
        }
    }
}

private fun JsonObjectBuilder.putKcpSettings(params: V2RayParameters) {
    putJsonObject("kcpSettings") {
        params.mtu?.toIntOrNull()?.let { put("mtu", it) }
        params.tti?.toIntOrNull()?.let { put("tti", it) }
    }
}

private fun String?.toXrayCsvValues(): List<String> {
    return this?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
}

private fun String?.toXrayJsonObjectOrNull(fieldName: String): JsonObject? {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return null
    val element: JsonElement = runCatching {
        Json.parseToJsonElement(value)
    }.getOrElse {
        proxyValidationError(ProxyServerValidationError.JsonObjectRequired, fieldName)
    }
    return element as? JsonObject
        ?: proxyValidationError(ProxyServerValidationError.JsonObjectRequired, fieldName)
}
