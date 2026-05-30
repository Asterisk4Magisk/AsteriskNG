// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.proxy.server.model.VMess

internal fun MihomoYamlMap.toMihomoVMessProxyServer(): VMess {
    val alterId = string("alterId", "alter-id", "alterid")?.toIntOrNull() ?: 0
    if (alterId != 0) {
        unsupported("VMess legacy alterId is not supported")
    }
    return VMess(
        remarks = requiredString("name"),
        server = requiredString("server"),
        port = requiredString("port"),
        id = requiredString("uuid", "id"),
        encryption = string("cipher", "encryption") ?: "auto",
        parms = toMihomoV2RayParameters(defaultSecurity = "none"),
    )
}
