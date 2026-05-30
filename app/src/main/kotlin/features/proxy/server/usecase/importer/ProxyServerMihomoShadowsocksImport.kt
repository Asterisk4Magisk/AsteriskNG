// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.proxy.server.model.Shadowsocks

internal fun MihomoYamlMap.toMihomoShadowsocksProxyServer(): Shadowsocks {
    if (!string("plugin").isNullOrBlank() || map("plugin-opts")?.isNotEmpty() == true) {
        unsupported("Shadowsocks plugin options are not supported")
    }
    return Shadowsocks(
        remarks = requiredString("name"),
        server = requiredString("server"),
        port = requiredString("port"),
        method = requiredString("cipher", "method"),
        password = requiredString("password"),
    )
}
