// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.network


data class NetworkCidrAddress(
    val address: String,
    val prefixLength: Int,
)

private val Ipv6HextetRegex = Regex("[0-9A-Fa-f]{1,4}")

fun isCidrAddress(cidr: String): Boolean {
    return parseCidrAddressOrNull(cidr) != null
}

fun parseCidrAddressOrNull(cidr: String): NetworkCidrAddress? {
    val parts = cidr.trim().split("/", limit = 2)
    if (parts.size != 2) return null

    val address = parts[0].trim()
    val prefixLength = parsePrefixLength(parts[1]) ?: return null
    val prefixRange = when {
        address.contains(":") -> NetworkLimits.IPV6_PREFIX_MIN..NetworkLimits.IPV6_PREFIX_MAX
        address.contains(".") -> NetworkLimits.IPV4_PREFIX_MIN..NetworkLimits.IPV4_PREFIX_MAX
        else -> return null
    }
    val validAddress = when {
        address.contains(":") -> isIpv6Address(address)
        address.contains(".") -> isIpv4Address(address)
        else -> false
    }
    if (!validAddress || prefixLength !in prefixRange) {
        return null
    }
    return NetworkCidrAddress(
        address = address,
        prefixLength = prefixLength,
    )
}

fun isIpAddress(address: String): Boolean {
    val trimmed = address.trim()
    return isIpv4Address(trimmed) || isIpv6Address(trimmed)
}

fun isIpOrCidrAddress(value: String): Boolean {
    val trimmed = value.trim()
    return isIpAddress(trimmed) || isCidrAddress(trimmed)
}

fun isPortList(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return true

    return trimmed.split(",").all { segment ->
        val bounds = segment.trim().split("-")
        when (bounds.size) {
            1 -> parsePort(bounds[0]) != null
            2 -> {
                val start = parsePort(bounds[0])
                val end = parsePort(bounds[1])
                start != null && end != null && start <= end
            }
            else -> false
        }
    }
}

fun isIpv4Address(address: String): Boolean {
    val octets = address.split(".")
    return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() &&
            octet.all(Char::isDigit) &&
            octet.toIntOrNull()?.let { it in NetworkLimits.IPV4_OCTET_MIN..NetworkLimits.IPV4_OCTET_MAX } == true
    }
}

fun isIpv6Address(address: String): Boolean {
    if (address.isBlank()) return false

    val compressedParts = address.split("::")
    if (compressedParts.size > 2) return false

    if (compressedParts.size == 1) {
        return parseIpv6Hextets(address)?.size == 8
    }

    val left = parseIpv6Hextets(compressedParts[0]) ?: return false
    val right = parseIpv6Hextets(compressedParts[1]) ?: return false
    return left.size + right.size < 8
}

private fun parsePort(value: String): Int? {
    if (value.isEmpty() || !value.all(Char::isDigit)) return null
    return value.toIntOrNull()?.takeIf { it in NetworkLimits.PORT_MIN..NetworkLimits.PORT_MAX }
}

private fun parsePrefixLength(prefix: String): Int? {
    if (prefix.isEmpty() || !prefix.all(Char::isDigit)) return null
    return prefix.toIntOrNull()
}

private fun parseIpv6Hextets(section: String): List<String>? {
    if (section.isEmpty()) return emptyList()

    val hextets = section.split(":")
    if (hextets.any { it.isEmpty() || !Ipv6HextetRegex.matches(it) }) {
        return null
    }
    return hextets
}
