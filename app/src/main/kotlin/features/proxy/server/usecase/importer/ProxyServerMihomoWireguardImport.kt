package features.proxy.server.usecase.importer

import features.proxy.server.model.Wireguard

internal fun MihomoYamlMap.toMihomoWireguardProxyServer(): Wireguard {
    val peers = list("peers").orEmpty().mapNotNull { item -> item.asStringMap() }
    if (peers.size > 1) {
        unsupported("multiple WireGuard peers are not supported")
    }
    val peer = peers.firstOrNull()
    val endpoint = peer ?: this
    return Wireguard(
        remarks = requiredString("name"),
        server = endpoint.requiredString("server"),
        port = endpoint.requiredString("port"),
        secretKey = requiredString("private-key", "privateKey"),
        publicKey = endpoint.requiredString("public-key", "publicKey"),
        preSharedKey = endpoint.string("pre-shared-key", "presharedkey", "preSharedKey").orEmpty(),
        reserved = endpoint.wireguardReservedString(),
        address = wireguardAddressString(),
        mtu = string("mtu") ?: "1420",
    )
}

private fun MihomoYamlMap.wireguardAddressString(): String {
    val addresses = listOfNotNull(
        string("ip")?.toWireguardCidrAddress(ipv6 = false),
        string("ipv6")?.toWireguardCidrAddress(ipv6 = true),
    )
    if (addresses.isEmpty()) {
        unsupported("WireGuard ip or ipv6 is required")
    }
    return addresses.joinToString(",")
}

private fun String.toWireguardCidrAddress(ipv6: Boolean): String {
    val value = trim()
    if ('/' in value) return value
    return if (ipv6) "$value/128" else "$value/32"
}

private fun MihomoYamlMap.wireguardReservedString(): String {
    val value = this["reserved"] ?: return "0,0,0"
    val parts = when (value) {
        is Iterable<*> -> value.mapNotNull { item -> item.scalarString() }
        else -> value.scalarString()?.split(',')?.map(String::trim).orEmpty()
    }.filter(String::isNotBlank)
    if (parts.isEmpty()) {
        return "0,0,0"
    }
    if (parts.size != 3 || parts.any { part -> part.toIntOrNull() == null }) {
        unsupported("WireGuard reserved must be three numeric bytes")
    }
    return parts.joinToString(",")
}
