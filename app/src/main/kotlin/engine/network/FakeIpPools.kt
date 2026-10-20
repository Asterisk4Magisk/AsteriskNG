// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.network

const val DefaultFakeIpIpv4Pool = "198.18.0.0/15"

/** Preserve saved IPv4 ranges; only unsupported IPv6 pools are reset. */
fun String.withoutIpv6FakeIpPool(): String =
    if (':' in this) DefaultFakeIpIpv4Pool else this

/** The existing ROOT contract accepts IPv4 pools with prefixes /1 through /30. */
fun isValidFakeIpIpv4Pool(value: String): Boolean {
    val cidr = parseCidrAddressOrNull(value) ?: return false
    return isIpv4Address(cidr.address) && cidr.prefixLength in 1..30
}

fun String.normalizedFakeIpIpv4Pool(): String {
    val cidr = parseCidrAddressOrNull(withoutIpv6FakeIpPool())
        ?.takeIf { isIpv4Address(it.address) }
        ?: return DefaultFakeIpIpv4Pool
    val address = cidr.address.split('.').fold(0L) { result, octet ->
        (result shl 8) or octet.toLong()
    }
    val mask = if (cidr.prefixLength == 0) 0L else (0xffffffffL shl (32 - cidr.prefixLength)) and 0xffffffffL
    val network = address and mask
    return (3 downTo 0).joinToString(".") { shift ->
        ((network shr (shift * 8)) and 255L).toString()
    } + "/${cidr.prefixLength}"
}
