// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.logs.AndroidAppLogger
import features.proxy.server.model.ProxyServer
import features.proxy.server.usecase.EmptyProxyServerImportResult
import features.proxy.server.usecase.ProxyServerImportContext
import features.proxy.server.usecase.ProxyServerImportResult
import features.proxy.server.usecase.ProxyServerImportSource
import features.proxy.server.usecase.ProxyServerProviderRequest
import kotlin.coroutines.cancellation.CancellationException
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.net.URI

private const val LogTag = "ProxyServerMihomoYamlImport"

internal suspend fun parseProxyServersFromMihomoYamlConfig(
    text: String,
    context: ProxyServerImportContext,
): ProxyServerImportResult {
    val source = context.source
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
    val providers = root.mihomoProxyProviders()
    if (configs.isEmpty() && providers.isEmpty()) {
        return EmptyProxyServerImportResult
    }

    var skippedCount = 0
    val servers = configs.applyMihomoProviderOptions(context.mihomoProviderOptions).toMihomoProxyServers(
        source = source,
        startIndex = 0,
        onSkipped = { skippedCount += 1 },
    ).toMutableList()

    val providerResults = providers.map { provider ->
        provider.importProvider(context)
    }
    providerResults.forEach { result ->
        skippedCount += result.skippedCount
        servers += result.servers
    }

    val importedConfigCount = configs.size + providers.size + providerResults.sumOf { it.urlCount }
    if (skippedCount > 0) {
        AndroidAppLogger.warn(
            LogTag,
            "Imported ${servers.size} ${source.logName} mihomo YAML Proxy Servers, " +
                "skipped $skippedCount/$importedConfigCount unsupported or invalid Proxy Servers",
        )
    }
    return ProxyServerImportResult(
        urlCount = importedConfigCount,
        servers = servers,
    )
}

private fun List<MihomoYamlMap>.toMihomoProxyServers(
    source: ProxyServerImportSource,
    startIndex: Int,
    onSkipped: () -> Unit,
): List<ProxyServer<*>> {
    return mapIndexedNotNull { offset, config ->
        config.toMihomoProxyServerOrNull(
            source = source,
            index = startIndex + offset,
            onSkipped = onSkipped,
        )
    }
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
    }.also { server ->
        val issues = server.validateBasic()
        if (issues.isNotEmpty()) {
            unsupported("basic validation failed: ${issues.joinToString { it.error.name }}")
        }
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

private fun Any?.mihomoProxyProviders(): List<MihomoProxyProvider> {
    val providers = asStringMap()
        ?.map("proxy-providers")
        ?: return emptyList()
    return providers.entries.mapNotNull { (name, rawProvider) ->
        val provider = rawProvider.asStringMap() ?: return@mapNotNull null
        MihomoProxyProvider(
            name = name,
            type = provider.string("type").orEmpty().lowercase(),
            url = provider.string("url"),
            payload = provider.list("payload").orEmpty().mapNotNull { item -> item.asStringMap() },
            options = MihomoProviderOptions.from(provider),
            headers = provider.mihomoProviderHeaders(),
        )
    }
}

private suspend fun MihomoProxyProvider.importProvider(
    context: ProxyServerImportContext,
): MihomoProviderImportResult {
    return when (type) {
        "inline" -> importPayload(context)
        "http" -> importHttpProvider(context)
        else -> MihomoProviderImportResult.Empty
    }
}

private suspend fun MihomoProxyProvider.importHttpProvider(
    context: ProxyServerImportContext,
): MihomoProviderImportResult {
    val providerUrl = url.normalizedProviderUrlOrNull()
    if (providerUrl == null) {
        AndroidAppLogger.warn(LogTag, "Skipped mihomo proxy-provider name=$name reason=invalid provider URL")
        return importPayload(context)
    }
    if (providerUrl in context.fetchedProviderUrls) {
        AndroidAppLogger.warn(LogTag, "Skipped mihomo proxy-provider name=$name reason=recursive provider URL")
        return importPayload(context)
    }
    val fetcher = context.providerUrlFetcher
    if (fetcher == null) {
        AndroidAppLogger.warn(LogTag, "Skipped mihomo proxy-provider name=$name reason=provider URL fetcher unavailable")
        return importPayload(context)
    }
    val result = runCatching {
        val text = fetcher.fetch(ProxyServerProviderRequest(providerUrl, headers))
        parseProxyServersFromMihomoProviderText(
            text = text,
            parentContext = context.copy(mihomoProviderOptions = listOf(options) + context.mihomoProviderOptions),
            providerUrl = providerUrl,
        )
    }.onFailure { error ->
        if (error is CancellationException || error is UnsupportedMihomoProviderException) throw error
        AndroidAppLogger.warn(
            LogTag,
            "Failed to fetch mihomo proxy-provider name=$name urlHost=${providerUrl.toLogHost()}",
            error,
        )
    }.getOrNull()
    return if (result != null && result.servers.isNotEmpty()) {
        MihomoProviderImportResult(
            urlCount = 1 + result.urlCount,
            servers = result.servers,
        )
    } else {
        importPayload(context)
    }
}

private fun MihomoProxyProvider.importPayload(
    context: ProxyServerImportContext,
): MihomoProviderImportResult {
    if (payload.isEmpty()) return MihomoProviderImportResult.Empty
    var skippedCount = 0
    val servers = payload.applyMihomoProviderOptions(listOf(options) + context.mihomoProviderOptions).toMihomoProxyServers(
        source = context.source,
        startIndex = 0,
        onSkipped = { skippedCount += 1 },
    )
    return MihomoProviderImportResult(
        urlCount = payload.size,
        servers = servers,
        skippedCount = skippedCount,
    )
}

private fun String?.normalizedProviderUrlOrNull(): String? {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    return uri.toString()
}

private fun String.toLogHost(): String {
    return runCatching { URI(this).host }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: "<unknown>"
}

private fun skippedMessage(
    source: ProxyServerImportSource,
    index: Int,
    name: String,
    type: String,
    reason: String,
): String {
    return "Skipped mihomo YAML Proxy Server source=${source.logName} index=$index " +
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

private data class MihomoProxyProvider(
    val name: String,
    val type: String,
    val url: String?,
    val payload: List<MihomoYamlMap>,
    val options: MihomoProviderOptions,
    val headers: Map<String, List<String>>,
)

private data class MihomoProviderImportResult(
    val urlCount: Int,
    val servers: List<ProxyServer<*>>,
    val skippedCount: Int = 0,
) {
    companion object {
        val Empty = MihomoProviderImportResult(
            urlCount = 0,
            servers = emptyList(),
        )
    }
}
