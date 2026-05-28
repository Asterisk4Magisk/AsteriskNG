package features.proxy.server.usecase.importer

import features.proxy.server.model.V2RayParameters

internal fun MihomoYamlMap.toMihomoV2RayParameters(
    defaultSecurity: String,
    xhttpHost: ((MihomoYamlMap) -> String?)? = null,
    xhttpExtra: ((xhttpOpts: MihomoYamlMap, parentNode: MihomoYamlMap) -> String?)? = null,
): V2RayParameters {
    ensureNoUnsupportedV2RayTlsOptions()
    val wsOpts = map("ws-opts")
    wsOpts?.ensureNoUnsupportedWsHeaders()
    val grpcOpts = map("grpc-opts")
    val xhttpOpts = map("xhttp-opts")
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
    val echOpts = map("ech-opts")
    val realityOpts = map("reality-opts")
    return V2RayParameters(
        type = transport,
        security = v2raySecurity(defaultSecurity),
        path = when (transport) {
            "websocket", "httpupgrade" -> wsOpts?.string("path")
            "xhttp" -> xhttpOpts?.string("path")
            else -> null
        },
        host = when (transport) {
            "websocket", "httpupgrade" -> wsOpts?.map("headers")?.headerString("Host") ?: wsOpts?.string("host")
            "xhttp" -> xhttpOpts?.let { xhttpHost?.invoke(it) }
            else -> null
        },
        mtu = map("kcp-opts")?.string("mtu"),
        tti = map("kcp-opts")?.string("tti"),
        serviceName = grpcOpts?.string("grpc-service-name", "service-name", "serviceName"),
        mode = when (transport) {
            "grpc" -> grpcOpts?.string("mode") ?: "gun"
            "xhttp" -> xhttpOpts?.string("mode")
            else -> null
        },
        authority = grpcOpts?.string("authority"),
        fp = string("client-fingerprint")?.lowercase(),
        sni = string("servername", "sni"),
        alpn = csvString("alpn"),
        ech = echOpts?.takeIf { it.boolean("enable") == true }?.string("config"),
        pcs = string("fingerprint"),
        pbk = realityOpts?.string("public-key", "publicKey"),
        sid = realityOpts?.string("short-id", "shortId"),
        spx = realityOpts?.string("spider-x", "spiderX"),
        extra = when (transport) {
            "xhttp" -> xhttpOpts?.let { xhttpExtra?.invoke(it, this) }
            else -> null
        },
    )
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
    val realityOpts = map("reality-opts")
    if (realityOpts?.boolean("support-x25519mlkem768") == true) {
        unsupported("Reality X25519-MLKEM768 is not supported")
    }
}

private fun MihomoYamlMap.ensureNoUnsupportedWsHeaders() {
    val headers = map("headers") ?: return
    val unsupportedKeys = headers.keys.filterNot { key -> key.equals("Host", ignoreCase = true) }
    if (unsupportedKeys.isNotEmpty()) {
        unsupported("WebSocket custom headers are not supported")
    }
}
