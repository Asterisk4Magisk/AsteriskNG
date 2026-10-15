// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

internal const val V2RayTransportRaw = "raw"
internal const val V2RayTransportMkcp = "mkcp"
internal const val V2RayTransportWebSocket = "websocket"
internal const val V2RayTransportHttpUpgrade = "httpupgrade"
internal const val V2RayTransportXhttp = "xhttp"
internal const val V2RayTransportGrpc = "grpc"

internal data class V2RayTransportOption(
    val value: String,
) {
    val label: String get() = value
}

internal val V2RayTransportOptions = listOf(
    V2RayTransportOption(V2RayTransportRaw),
    V2RayTransportOption(V2RayTransportMkcp),
    V2RayTransportOption(V2RayTransportWebSocket),
    V2RayTransportOption(V2RayTransportHttpUpgrade),
    V2RayTransportOption(V2RayTransportXhttp),
    V2RayTransportOption(V2RayTransportGrpc),
)

internal fun String?.toCanonicalV2RayTransportType(defaultType: String = V2RayTransportRaw): String {
    val normalized = this
        ?.trim()
        ?.lowercase()
        .orEmpty()
        .ifBlank { defaultType }
    return when (normalized) {
        "tcp", V2RayTransportRaw -> V2RayTransportRaw
        "kcp", V2RayTransportMkcp -> V2RayTransportMkcp
        "ws", V2RayTransportWebSocket -> V2RayTransportWebSocket
        V2RayTransportHttpUpgrade -> V2RayTransportHttpUpgrade
        "splithttp", V2RayTransportXhttp -> V2RayTransportXhttp
        V2RayTransportGrpc -> V2RayTransportGrpc
        else -> normalized
    }
}

internal fun String.toV2RayTransportUrlType(): String {
    return when (val canonicalType = toCanonicalV2RayTransportType()) {
        V2RayTransportMkcp -> "kcp"
        V2RayTransportWebSocket -> "ws"
        else -> canonicalType
    }
}

internal fun v2RayTransportOptionIndex(type: String?): Int {
    val canonicalType = type.toCanonicalV2RayTransportType()
    val index = V2RayTransportOptions.indexOfFirst { option -> option.value == canonicalType }
    return if (index >= 0) index else 0
}

/** The handshake a client performs with the server before relaying traffic. */
internal enum class ProxyTransportHandshake {
    Tcp,
    Quic,
    WireGuard,

    /** mKCP is connectionless: no exchange happens until traffic is sent. */
    Connectionless,
}

// XHTTP pinned to the "h3" ALPN runs over QUIC; mKCP runs over UDP without a handshake.
internal fun v2RayTransportHandshake(type: String?, alpn: List<String>): ProxyTransportHandshake {
    return when (type.toCanonicalV2RayTransportType()) {
        V2RayTransportMkcp -> ProxyTransportHandshake.Connectionless
        V2RayTransportXhttp -> if (alpn.map { value -> value.trim().lowercase() } == listOf("h3")) {
            ProxyTransportHandshake.Quic
        } else {
            ProxyTransportHandshake.Tcp
        }
        else -> ProxyTransportHandshake.Tcp
    }
}
