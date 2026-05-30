// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.proxy.server.model.HTTP

internal fun MihomoYamlMap.toMihomoHttpProxyServer(): HTTP {
    ensureNoMihomoTlsOptions("TLS for HTTP proxy nodes is not supported")
    if (map("headers")?.isNotEmpty() == true) {
        unsupported("HTTP proxy custom headers are not supported")
    }
    return HTTP(
        remarks = requiredString("name"),
        server = requiredString("server"),
        port = requiredString("port"),
        user = string("username", "user"),
        password = string("password", "pass"),
    )
}
