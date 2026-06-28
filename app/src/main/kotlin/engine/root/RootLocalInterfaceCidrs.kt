// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

internal fun collectRootLocalInterfaceCidrs(
    ignoredInterfaceNames: Set<String> = emptySet(),
): List<String> {
    val networkInterfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        ?: return emptyList()
    return networkInterfaces.asSequence()
        .filter { networkInterface -> networkInterface.isUsableForRootLocalAddress(ignoredInterfaceNames) }
        .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
        .mapNotNull(InetAddress::toRootLocalAddressCidr)
        .distinct()
        .toList()
}

private fun NetworkInterface.isUsableForRootLocalAddress(ignoredInterfaceNames: Set<String>): Boolean {
    if (name in ignoredInterfaceNames) return false
    return runCatching { isUp }.getOrDefault(false)
}

private fun InetAddress.toRootLocalAddressCidr(): String? {
    if (isAnyLocalAddress || isMulticastAddress) return null
    val hostAddress = hostAddress?.substringBefore('%')?.takeIf(String::isNotEmpty) ?: return null
    return when (this) {
        is Inet4Address -> "$hostAddress/32"
        is Inet6Address -> "$hostAddress/128"
        else -> null
    }
}
