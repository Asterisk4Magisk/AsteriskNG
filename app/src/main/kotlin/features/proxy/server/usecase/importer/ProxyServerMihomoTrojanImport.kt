package features.proxy.server.usecase.importer

import features.proxy.server.model.Trojan

internal fun MihomoYamlMap.toMihomoTrojanProxyServer(): Trojan {
    val ssOpts = map("ss-opts")
    if (ssOpts?.boolean("enabled") == true) {
        unsupported("Trojan ss-opts are not supported")
    }
    return Trojan(
        remarks = requiredString("name"),
        server = requiredString("server"),
        port = requiredString("port"),
        password = requiredString("password"),
        parms = toMihomoV2RayParameters(defaultSecurity = "tls"),
    )
}
