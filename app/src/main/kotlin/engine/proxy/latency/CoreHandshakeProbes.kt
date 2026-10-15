// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy.latency

import features.proxy.server.model.WireGuardHandshakeKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// JSON contracts of the Xray core library calls used by the handshake latency test.
// Libv2ray.fetchQuicCertSha256 completes a QUIC (HTTP/3) handshake, so timing it measures
// the handshake; Libv2ray.measureWireGuardHandshake times one WireGuard initiation itself.

@Serializable
private data class QuicCertRequest(
    val address: String,
    val port: Int,
    val serverName: String,
    val timeoutMs: Long,
)

@Serializable
private data class QuicCertResult(
    val sha256: String = "",
    val error: String = "",
)

@Serializable
private data class WireGuardHandshakeRequest(
    val endpoint: String,
    val secretKey: String,
    val publicKey: String,
    val reserved: List<Int>,
    val timeoutMs: Long,
)

@Serializable
private data class WireGuardHandshakeResult(
    val delayMs: Long = -1L,
    val error: String = "",
)

internal fun quicHandshakeRequestJson(address: String, port: Int, serverName: String, timeoutMs: Long): String {
    return CoreHandshakeJson.encodeToString(QuicCertRequest(address, port, serverName, timeoutMs))
}

/** Returns the error of a QUIC handshake probe, or null when the handshake completed. */
internal fun quicHandshakeError(response: String): String? {
    val result = runCatching { CoreHandshakeJson.decodeFromString<QuicCertResult>(response) }
        .getOrElse { error -> return error.message ?: "invalid response" }
    return result.error.ifBlank { null }
}

internal fun wireGuardHandshakeRequestJson(
    host: String,
    port: Int,
    keys: WireGuardHandshakeKeys,
    timeoutMs: Long,
): String {
    return CoreHandshakeJson.encodeToString(
        WireGuardHandshakeRequest(
            endpoint = hostPort(host, port),
            secretKey = keys.secretKey,
            publicKey = keys.publicKey,
            reserved = keys.reserved,
            timeoutMs = timeoutMs,
        ),
    )
}

/** Returns the handshake delay in milliseconds and the error, if any. */
internal fun wireGuardHandshakeResult(response: String): Pair<Long, String> {
    val result = runCatching { CoreHandshakeJson.decodeFromString<WireGuardHandshakeResult>(response) }
        .getOrElse { error -> return -1L to (error.message ?: "invalid response") }
    return result.delayMs to result.error
}

private fun hostPort(host: String, port: Int): String {
    return if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

private val CoreHandshakeJson = Json { ignoreUnknownKeys = true }
