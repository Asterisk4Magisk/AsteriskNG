// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.proxy.server.model.V2RayParameters
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun MihomoYamlMap.toMihomoV2RayParameters(
    defaultSecurity: String,
    xhttpHost: ((MihomoYamlMap) -> String?)? = null,
    xhttpExtra: ((xhttpOpts: MihomoYamlMap, parentNode: MihomoYamlMap) -> String?)? = null,
): V2RayParameters {
    ensureNoUnsupportedV2RayTlsOptions()
    val wsOpts = map("ws-opts")
    val wsHeaders = wsOpts.orEmpty().mergedWsHeaders(this)
    val grpcOpts = map("grpc-opts")
    val xhttpOpts = map("xhttp-opts")
    val kcpOpts = map("kcp-opts")
    val xhttpSupported = xhttpHost != null || xhttpExtra != null
    val transport = when (val network = string("network")?.lowercase().orEmpty()) {
        "", "tcp", "raw" -> "raw"
        "ws", "websocket" -> if (wsOpts?.boolean("v2ray-http-upgrade") == true) "httpupgrade" else "websocket"
        "grpc" -> "grpc"
        "httpupgrade" -> "httpupgrade"
        "xhttp", "splithttp" -> if (xhttpSupported) "xhttp" else unsupported("xhttp transport is only supported for VLESS")
        "kcp", "mkcp" -> "mkcp"
        "http", "h2", "h3" -> unsupported("transport $network is not supported")
        else -> "raw"
    }
    if (transport in setOf("websocket", "httpupgrade")) {
        wsOpts?.ensureNoUnsupportedWsOptions()
    }
    val echOpts = map("ech-opts")
    val realityOpts = map("reality-opts")
    return V2RayParameters(
        type = transport,
        security = v2raySecurity(defaultSecurity),
        path = when (transport) {
            "websocket", "httpupgrade" -> wsOpts?.pathWithEarlyData()
            "xhttp" -> xhttpOpts?.string("path")
            else -> null
        },
        host = when (transport) {
            "websocket", "httpupgrade" -> wsHeaders?.headerString("Host") ?: wsOpts?.string("host")
            "xhttp" -> xhttpOpts?.let { xhttpHost?.invoke(it) }
            "mkcp" -> (kcpOpts ?: this).map("header")?.string("host", "domain")
                ?: kcpString(kcpOpts, "host", "domain")
            else -> null
        },
        headers = when (transport) {
            "websocket", "httpupgrade" -> wsHeaders?.transportHeadersJson()
            else -> null
        },
        mtu = kcpString(kcpOpts, "mtu"),
        tti = kcpString(kcpOpts, "tti"),
        seed = kcpString(kcpOpts, "seed"),
        serviceName = grpcOpts?.string("grpc-service-name", "service-name", "serviceName"),
        mode = when (transport) {
            "grpc" -> grpcOpts?.string("mode") ?: "gun"
            "xhttp" -> xhttpOpts?.string("mode")
            else -> null
        },
        authority = grpcOpts?.string("authority"),
        headerType = when (transport) {
            "mkcp" -> (kcpOpts ?: this).kcpHeaderType() ?: "none"
            else -> "none"
        },
        fp = string("client-fingerprint")?.lowercase(),
        sni = string("servername", "sni"),
        alpn = csvString("alpn"),
        ech = echOpts?.takeIf { it.boolean("enable") == true }?.string("config"),
        pcs = string("fingerprint"),
        vcn = string("name-cert-verify"),
        pbk = realityOpts?.string("public-key", "publicKey"),
        sid = realityOpts?.string("short-id", "shortId"),
        spx = realityOpts?.string("spider-x", "spiderX"),
        extra = when (transport) {
            "xhttp" -> xhttpOpts?.let { xhttpExtra?.invoke(it, this) }
            else -> null
        },
    )
}

private fun MihomoYamlMap.ensureNoUnsupportedWsOptions() {
    if (boolean("v2ray-http-upgrade-fast-open") == true) {
        unsupported("WebSocket v2ray-http-upgrade-fast-open is not supported by Xray")
    }
}

private fun MihomoYamlMap.pathWithEarlyData(): String? {
    val rawMaxEarlyData = this["max-early-data"] ?: return string("path")
    val maxEarlyData = rawMaxEarlyData.scalarString()
        ?.toIntOrNull()
        ?: unsupported("WebSocket max-early-data must be integer")
    if (maxEarlyData <= 0) {
        return string("path")
    }
    val headerName = string("early-data-header-name")
    if (!headerName.isNullOrBlank() && !headerName.equals("Sec-WebSocket-Protocol", ignoreCase = true)) {
        unsupported("WebSocket early-data-header-name is not supported by Xray")
    }
    return (string("path") ?: "/").appendEdQuery(maxEarlyData)
}

private fun String.appendEdQuery(value: Int): String {
    val separator = if ('?' in this) "&" else "?"
    return "$this${separator}ed=$value"
}

private fun MihomoYamlMap.mergedWsHeaders(parentNode: MihomoYamlMap): MihomoYamlMap? {
    val wsHeaders = parentNode.map("ws-headers")
    val headers = map("headers")
    if (wsHeaders.isNullOrEmpty() && headers.isNullOrEmpty()) return null
    return buildMap {
        wsHeaders?.let { putAll(it) }
        headers?.let { putAll(it) }
    }
}

private fun MihomoYamlMap.kcpHeaderType(): String? {
    return map("header")?.string("type")
        ?: string("headerType", "header-type")
}

private fun MihomoYamlMap.kcpString(kcpOpts: MihomoYamlMap?, vararg names: String): String? {
    return kcpOpts?.string(*names) ?: string(*names)
}

private fun MihomoYamlMap.ensureNoUnsupportedV2RayTlsOptions() {
    if (boolean("skip-cert-verify") == true) {
        unsupported("skip-cert-verify is not supported")
    }
    if (!string("certificate", "private-key").isNullOrBlank()) {
        unsupported("TLS certificate/private-key options are not supported")
    }
    val echOpts = map("ech-opts")
    if (echOpts?.boolean("enable") == true) {
        if (echOpts.string("config").isNullOrBlank()) {
            unsupported("ECH without explicit config is not supported")
        }
        if (!echOpts.string("query-server-name").isNullOrBlank()) {
            unsupported("ECH query-server-name is not supported")
        }
    }
}

private fun MihomoYamlMap.transportHeadersJson(): String? {
    val json = buildJsonObject {
        this@transportHeadersJson.entries.forEach { (name, value) ->
            if (!name.equals("Host", ignoreCase = true)) {
                value.headerValueString("transport header $name")?.let { put(name, it) }
            }
        }
    }
    return json.takeIf { it.isNotEmpty() }?.toString()
}
