// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.proxy.server.model.Socks

internal fun MihomoYamlMap.toMihomoSocksProxyServer(): Socks {
    ensureNoMihomoTlsOptions("TLS for SOCKS proxy nodes is not supported")
    return Socks(
        remarks = requiredString("name"),
        server = requiredString("server"),
        port = requiredString("port"),
        user = string("username", "user"),
        password = string("password", "pass"),
    )
}
