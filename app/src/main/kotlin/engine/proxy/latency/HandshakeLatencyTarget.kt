// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy.latency

import engine.network.toPortOrNull
import features.proxy.server.model.Custom
import features.proxy.server.model.HTTP
import features.proxy.server.model.Hysteria2
import features.proxy.server.model.Masque
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.ProxyTransportHandshake
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import features.proxy.server.model.Trojan
import features.proxy.server.model.V2RayParameters
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import features.proxy.server.model.Wireguard
import features.proxy.server.model.WireGuardHandshakeKeys
import features.proxy.server.model.customXrayConfigProxyOutboundEndpoint
import features.proxy.server.model.toXrayJsonObjectOrNull
import features.proxy.server.model.v2RayTransportHandshake
import utils.toCsvValues

/**
 * How the handshake latency test reaches a proxy server: a TCP connect, a QUIC handshake or a
 * WireGuard handshake initiation sent straight to the server. Servers whose UDP packets are
 * masked (Salamander obfuscation, finalmask), connectionless transports (mKCP) and servers
 * without a single endpoint (strategy groups, chains) cannot be probed this way.
 */
internal sealed interface HandshakeLatencyTarget {
    data class TcpEndpoint(val host: String, val port: Int) : HandshakeLatencyTarget

    data class QuicEndpoint(val host: String, val port: Int, val serverName: String) : HandshakeLatencyTarget

    data class WireGuardEndpoint(
        val host: String,
        val port: Int,
        val keys: WireGuardHandshakeKeys,
    ) : HandshakeLatencyTarget

    data object UdpMasked : HandshakeLatencyTarget

    data object Connectionless : HandshakeLatencyTarget

    data object NoSingleEndpoint : HandshakeLatencyTarget

    data object InvalidEndpoint : HandshakeLatencyTarget
}

internal fun ProxyServer<*>.handshakeLatencyTarget(): HandshakeLatencyTarget {
    return when (this) {
        is Hysteria2 -> hysteria2Target()
        is Wireguard -> wireGuardTarget()
        is Masque -> masqueTarget()
        is HTTP -> tcpEndpointTarget(server, port)
        is Socks -> tcpEndpointTarget(server, port)
        is Shadowsocks -> v2RayTarget(server, port, parms)
        is Trojan -> v2RayTarget(server, port, parms)
        is VLESS -> v2RayTarget(server, port, parms)
        is VMess -> v2RayTarget(server, port, parms)
        is Custom -> customTarget(configJson)
        else -> HandshakeLatencyTarget.NoSingleEndpoint
    }
}

private fun Hysteria2.hysteria2Target(): HandshakeLatencyTarget {
    if (obfs == "salamander" && obfsPassword.isNotBlank()) return HandshakeLatencyTarget.UdpMasked
    return quicEndpointTarget(server, port, sni)
}

private fun Wireguard.wireGuardTarget(): HandshakeLatencyTarget {
    if (finalMask.isNotBlank()) return HandshakeLatencyTarget.UdpMasked
    val endpoint = parsedEndpoint(server, port) ?: return HandshakeLatencyTarget.InvalidEndpoint
    val reserved = reserved.toCsvValues().mapNotNull(String::toIntOrNull)
    return HandshakeLatencyTarget.WireGuardEndpoint(
        endpoint.host,
        endpoint.port,
        WireGuardHandshakeKeys(secretKey = secretKey, publicKey = publicKey, reserved = reserved),
    )
}

// MASQUE runs over HTTP/3 unless the ALPN pins HTTP/2 (Extended CONNECT over TLS/TCP).
private fun Masque.masqueTarget(): HandshakeLatencyTarget {
    if (usesHttp2()) return tcpEndpointTarget(server, port)
    // quicParams alone leaves the handshake probe-able; UDP masks (or an unreadable finalmask) do not.
    val udpMasks = runCatching { finalMask.toXrayJsonObjectOrNull("FinalMask") }
        .getOrElse { return HandshakeLatencyTarget.UdpMasked }
        ?.get("udp")
    if (udpMasks != null) return HandshakeLatencyTarget.UdpMasked
    return quicEndpointTarget(server, port, sni)
}

private fun v2RayTarget(host: String, port: String, params: V2RayParameters): HandshakeLatencyTarget {
    return when (v2RayTransportHandshake(params.type, params.alpn.toCsvValues())) {
        ProxyTransportHandshake.Tcp -> tcpEndpointTarget(host, port)
        ProxyTransportHandshake.Connectionless -> HandshakeLatencyTarget.Connectionless
        ProxyTransportHandshake.Quic -> if (params.fm.isNullOrBlank()) {
            quicEndpointTarget(host, port, params.sni.orEmpty())
        } else {
            HandshakeLatencyTarget.UdpMasked
        }
        ProxyTransportHandshake.WireGuard -> HandshakeLatencyTarget.NoSingleEndpoint
    }
}

private fun customTarget(configJson: String): HandshakeLatencyTarget {
    val endpoint = customXrayConfigProxyOutboundEndpoint(configJson) ?: return HandshakeLatencyTarget.InvalidEndpoint
    if (endpoint.handshake == ProxyTransportHandshake.Tcp) {
        return HandshakeLatencyTarget.TcpEndpoint(endpoint.host, endpoint.port)
    }
    if (endpoint.handshake == ProxyTransportHandshake.Connectionless) return HandshakeLatencyTarget.Connectionless
    if (endpoint.udpMasked) return HandshakeLatencyTarget.UdpMasked
    return when (endpoint.handshake) {
        ProxyTransportHandshake.Quic -> HandshakeLatencyTarget.QuicEndpoint(
            endpoint.host,
            endpoint.port,
            endpoint.serverName ?: endpoint.host,
        )
        else -> endpoint.wireGuardKeys
            ?.let { keys -> HandshakeLatencyTarget.WireGuardEndpoint(endpoint.host, endpoint.port, keys) }
            ?: HandshakeLatencyTarget.InvalidEndpoint
    }
}

private fun quicEndpointTarget(host: String, port: String, serverName: String): HandshakeLatencyTarget {
    val endpoint = parsedEndpoint(host, port) ?: return HandshakeLatencyTarget.InvalidEndpoint
    return HandshakeLatencyTarget.QuicEndpoint(endpoint.host, endpoint.port, serverName.trim().ifBlank { endpoint.host })
}

private fun tcpEndpointTarget(host: String, port: String): HandshakeLatencyTarget {
    return parsedEndpoint(host, port) ?: HandshakeLatencyTarget.InvalidEndpoint
}

private fun parsedEndpoint(host: String, port: String): HandshakeLatencyTarget.TcpEndpoint? {
    val parsedPort = port.toPortOrNull() ?: return null
    val trimmedHost = host.trim().takeIf(String::isNotEmpty) ?: return null
    return HandshakeLatencyTarget.TcpEndpoint(trimmedHost, parsedPort)
}
