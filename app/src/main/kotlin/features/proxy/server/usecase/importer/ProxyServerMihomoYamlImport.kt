// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.logs.AndroidAppLogger
import features.proxy.server.model.ProxyServer
import features.proxy.server.usecase.EmptyProxyServerImportResult
import features.proxy.server.usecase.ProxyServerImportResult
import features.proxy.server.usecase.ProxyServerImportSource
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

private const val LogTag = "ProxyServerMihomoYamlImport"

internal fun parseProxyServersFromMihomoYamlConfig(
    text: String,
    source: ProxyServerImportSource,
): ProxyServerImportResult {
    val root = runCatching {
        newMihomoYamlParser().loadFromString(text.trimStart(ImportByteOrderMark))
    }.onFailure { error ->
        AndroidAppLogger.warn(
            LogTag,
            "Failed to parse ${source.logName} as mihomo YAML",
            error,
        )
    }.getOrNull() ?: return EmptyProxyServerImportResult
    val configs = root.mihomoProxyConfigs()
    if (configs.isEmpty()) {
        return EmptyProxyServerImportResult
    }

    var skippedCount = 0
    val servers = configs.mapIndexedNotNull { index, config ->
        config.toMihomoProxyServerOrNull(
            source = source,
            index = index,
            onSkipped = { skippedCount += 1 },
        )
    }

    if (skippedCount > 0) {
        AndroidAppLogger.warn(
            LogTag,
            "Imported ${servers.size} ${source.logName} mihomo YAML proxy nodes, " +
                "skipped $skippedCount/${configs.size} unsupported or invalid nodes",
        )
    }
    return ProxyServerImportResult(
        urlCount = configs.size,
        servers = servers,
    )
}

private fun MihomoYamlMap.toMihomoProxyServerOrNull(
    source: ProxyServerImportSource,
    index: Int,
    onSkipped: () -> Unit,
): ProxyServer<*>? {
    val type = string("type")?.lowercase().orEmpty()
    val name = string("name").orEmpty()
    val skipReason = when {
        type.isBlank() -> "missing proxy type"
        type !in SupportedMihomoProxyTypes -> "unsupported proxy type"
        else -> null
    }
    if (skipReason != null) {
        onSkipped()
        AndroidAppLogger.warn(
            LogTag,
            skippedMessage(source, index, name, type, skipReason),
        )
        return null
    }

    return runCatching {
        toMihomoProxyServer()
    }.onFailure { error ->
        onSkipped()
        val reason = if (error is UnsupportedMihomoProxyException) {
            error.message.orEmpty()
        } else {
            "invalid proxy config"
        }
        AndroidAppLogger.warn(
            LogTag,
            skippedMessage(source, index, name, type, reason),
            error.takeUnless { it is UnsupportedMihomoProxyException },
        )
    }.getOrNull()
}

private fun MihomoYamlMap.toMihomoProxyServer(): ProxyServer<*> {
    ensureNoMihomoDialerProxy()
    return when (requiredString("type").lowercase()) {
        "http" -> toMihomoHttpProxyServer()
        "socks", "socks5" -> toMihomoSocksProxyServer()
        "ss", "shadowsocks" -> toMihomoShadowsocksProxyServer()
        "vmess" -> toMihomoVMessProxyServer()
        "vless" -> toMihomoVlessProxyServer()
        "trojan" -> toMihomoTrojanProxyServer()
        "hy2", "hysteria2" -> toMihomoHysteria2ProxyServer()
        "wg", "wireguard" -> toMihomoWireguardProxyServer()
        else -> unsupported("unsupported proxy type")
    }.also { server -> server.check() }
}

private fun MihomoYamlMap.ensureNoMihomoDialerProxy() {
    if (!string("dialer-proxy").isNullOrBlank()) {
        unsupported("dialer-proxy is not supported")
    }
}

private fun Any?.mihomoProxyConfigs(): List<MihomoYamlMap> {
    val rootMap = asStringMap()
    if (rootMap != null) {
        val proxies = rootMap.list("proxies")
        if (proxies != null) {
            return proxies.mapNotNull { item -> item.asStringMap() }
        }
        if (!rootMap.string("type").isNullOrBlank()) {
            return listOf(rootMap)
        }
    }
    return asList().orEmpty().mapNotNull { item -> item.asStringMap() }
}

private fun skippedMessage(
    source: ProxyServerImportSource,
    index: Int,
    name: String,
    type: String,
    reason: String,
): String {
    return "Skipped mihomo YAML proxy node source=${source.logName} index=$index " +
        "type=${type.ifBlank { "<blank>" }} name=${name.ifBlank { "<blank>" }} reason=$reason"
}

private fun newMihomoYamlParser(): Load {
    return Load(LoadSettings.builder().build())
}

private val SupportedMihomoProxyTypes = setOf(
    "http",
    "socks",
    "socks5",
    "ss",
    "shadowsocks",
    "vmess",
    "vless",
    "trojan",
    "hy2",
    "hysteria2",
    "wg",
    "wireguard",
)
