// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import engine.network.toPortOrNull
import engine.network.NetworkLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import utils.toCsvValues

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
    return toPortOrNull()
        ?: throw IllegalArgumentException("Port must be ${NetworkLimits.PORT_MIN}-${NetworkLimits.PORT_MAX}")
}

internal fun JsonObjectBuilder.putIfNotBlank(name: String, value: String?) {
    if (!value.isNullOrBlank()) {
        put(name, value)
    }
}

internal fun JsonObjectBuilder.putJsonArrayIfNotBlank(name: String, value: String?) {
    val values = value.toCsvValues()
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
            "mkcp" -> {
                putKcpSettings(this@toXrayStreamSettings)
                putKcpFinalMask(this@toXrayStreamSettings)
            }
        }

        val finalMask = fm.toXrayJsonObjectOrNull("FinalMask")
        if (finalMask != null) {
            put("finalmask", finalMask)
        }
    }
}

private fun String.toXrayNetwork(): String {
    return when (toCanonicalV2RayTransportType()) {
        V2RayTransportRaw -> "raw"
        V2RayTransportMkcp -> "mkcp"
        V2RayTransportWebSocket -> "websocket"
        V2RayTransportGrpc -> "grpc"
        V2RayTransportHttpUpgrade -> "httpupgrade"
        V2RayTransportXhttp -> "xhttp"
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
                        val hostValues = params.host.toCsvValues()
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
        val transportHeaders = params.httpTransportHeaders("WebSocket headers")
        putIfNotBlank("path", params.path)
        putIfNotBlank("host", transportHeaders.host)
        transportHeaders.headers?.let { put("headers", it) }
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
        val transportHeaders = params.httpTransportHeaders("HTTPUpgrade headers")
        putIfNotBlank("path", params.path)
        putIfNotBlank("host", transportHeaders.host)
        transportHeaders.headers?.let { put("headers", it) }
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

private fun JsonObjectBuilder.putKcpFinalMask(params: V2RayParameters) {
    putJsonObject("finalmask") {
        putJsonArray("udp") {
            val headerType = params.headerType.orEmpty()
            if (headerType.isNotBlank() && headerType != "none") {
                add(buildJsonObject {
                    put("type", headerType.toKcpHeaderMaskType())
                    if (headerType == "dns" && !params.host.isNullOrBlank()) {
                        putJsonObject("settings") {
                            put("domain", params.host)
                        }
                    }
                })
            }
            add(buildJsonObject {
                val seed = params.seed.orEmpty()
                if (seed.isBlank()) {
                    put("type", "mkcp-original")
                } else {
                    put("type", "mkcp-aes128gcm")
                    putJsonObject("settings") {
                        put("password", seed)
                    }
                }
            })
        }
    }
}

private fun String.toKcpHeaderMaskType(): String {
    return if (this == "wechat-video") "header-wechat" else "header-$this"
}

internal fun String?.toXrayJsonObjectOrNull(fieldName: String): JsonObject? {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return null
    val element: JsonElement = runCatching {
        Json.parseToJsonElement(value)
    }.getOrElse {
        throw IllegalArgumentException("$fieldName must be a JSON object")
    }
    return element as? JsonObject
        ?: throw IllegalArgumentException("$fieldName must be a JSON object")
}

private data class XrayHttpTransportHeaders(
    val host: String?,
    val headers: JsonObject?,
)

private fun V2RayParameters.httpTransportHeaders(fieldName: String): XrayHttpTransportHeaders {
    val explicitHost = host?.trim()?.takeIf(String::isNotEmpty)
    val parsedHeaders = headers.toXrayJsonObjectOrNull(fieldName)
        ?: return XrayHttpTransportHeaders(host = explicitHost, headers = null)
    var hostHeader: String? = null
    val filteredHeaders = buildJsonObject {
        parsedHeaders.forEach { (name, value) ->
            if (name.equals("Host", ignoreCase = true)) {
                if (hostHeader.isNullOrBlank()) {
                    hostHeader = value.headerStringOrNull()
                }
            } else {
                put(name, value)
            }
        }
    }
    return XrayHttpTransportHeaders(
        host = explicitHost ?: hostHeader,
        headers = filteredHeaders.takeIf { it.isNotEmpty() },
    )
}

private fun JsonElement.headerStringOrNull(): String? {
    return (this as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}
