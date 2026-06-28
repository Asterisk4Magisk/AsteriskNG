// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.network

import java.net.InetAddress
import java.net.ServerSocket

internal fun findAvailableTcpPort(
    listenAddress: String,
    excludedPorts: Set<Int> = emptySet(),
    attempts: Int = DefaultAvailablePortAttempts,
): Int? {
    val address = runCatching { InetAddress.getByName(listenAddress) }.getOrNull() ?: return null
    return runCatching {
        repeat(attempts.coerceAtLeast(1)) {
            ServerSocket(0, 0, address).use { socket ->
                val port = socket.localPort
                if (port > 0 && port !in excludedPorts) {
                    return@runCatching port
                }
            }
        }
        null
    }.getOrNull()
}

internal fun isTcpPortAvailable(
    listenAddress: String,
    port: Int,
): Boolean {
    if (!port.isPort()) return false
    val address = runCatching { InetAddress.getByName(listenAddress) }.getOrNull() ?: return false
    return runCatching {
        ServerSocket(port, 0, address).use { }
    }.isSuccess
}

private const val DefaultAvailablePortAttempts = 10
