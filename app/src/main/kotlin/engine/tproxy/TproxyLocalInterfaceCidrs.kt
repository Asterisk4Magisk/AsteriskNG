// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tproxy

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration

internal fun collectTproxyLocalInterfaceCidrs(): List<String> {
    val networkInterfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        ?: return emptyList()
    return networkInterfaces.asSequence()
        .filter(NetworkInterface::isUsableForTproxyLocalAddress)
        .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
        .mapNotNull(InetAddress::toTproxyLocalAddressCidr)
        .distinct()
        .toList()
}

private fun NetworkInterface.isUsableForTproxyLocalAddress(): Boolean {
    if (name == TproxyDummyDevice) return false
    return runCatching { isUp }.getOrDefault(false)
}

private fun InetAddress.toTproxyLocalAddressCidr(): String? {
    if (isAnyLocalAddress || isMulticastAddress) return null
    val hostAddress = hostAddress?.substringBefore('%')?.takeIf(String::isNotEmpty) ?: return null
    return when (this) {
        is Inet4Address -> "$hostAddress/32"
        is Inet6Address -> "$hostAddress/128"
        else -> null
    }
}

private fun <T> Enumeration<T>.asSequence(): Sequence<T> {
    return sequence {
        while (hasMoreElements()) {
            yield(nextElement())
        }
    }
}
