// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.proxy.server.model.Hysteria2

internal fun MihomoYamlMap.toMihomoHysteria2ProxyServer(): Hysteria2 {
    val realmOpts = map("realm-opts")
    if (realmOpts?.boolean("enable") == true) {
        unsupported("Hysteria2 realm-opts are not supported")
    }
    val multiPorts = string("ports").orEmpty()
    val hopInterval = string("hop-interval").orEmpty()
    if ('-' in hopInterval) {
        unsupported("Hysteria2 ranged hop-interval is not supported")
    }
    val port = string("port") ?: multiPorts.firstPortInRange()
    return Hysteria2(
        remarks = requiredString("name"),
        server = requiredString("server"),
        port = port,
        auth = requiredString("password", "auth", "auth-str"),
        obfs = string("obfs").orEmpty(),
        obfsPassword = string("obfs-password", "obfsPassword").orEmpty(),
        sni = string("sni", "servername").orEmpty(),
        insecure = if (boolean("skip-cert-verify") == true) 1 else 0,
        pinSHA256 = string("pinSHA256", "pin-sha256", "fingerprint").orEmpty(),
        mport = multiPorts,
        mportHopInt = hopInterval,
        up = string("up").orEmpty(),
        down = string("down").orEmpty(),
        security = if (hasTlsFields() || boolean("tls") == true) "tls" else "none",
    )
}

private fun String.firstPortInRange(): String {
    return split(',').firstOrNull()
        ?.substringBefore('-')
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: unsupported("Hysteria2 port or ports is required")
}
