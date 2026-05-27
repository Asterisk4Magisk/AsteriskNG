package features.proxy.server.usecase

import features.logs.AndroidAppLogger
import features.proxy.server.model.Custom
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.formatCustomXrayConfigJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val JsonByteOrderMark = '\uFEFF'
private const val LogTag = "ProxyServerJsonImport"

internal fun parseProxyServersFromJsonConfig(
    text: String,
    source: ProxyServerImportSource,
): ProxyServerImportResult {
    val root = runCatching {
        ProxyServer.json.parseToJsonElement(text.trimStart(JsonByteOrderMark))
    }.getOrNull() ?: return ProxyServerImportResult(urlCount = 0, servers = emptyList())
    val configs = when (root) {
        is JsonObject -> listOf(root)
        is JsonArray -> root.mapNotNull { element -> element as? JsonObject }
        else -> emptyList()
    }
    if (configs.isEmpty()) {
        return ProxyServerImportResult(urlCount = 0, servers = emptyList())
    }

    var failedCount = 0
    val servers = configs.mapIndexedNotNull { index, config ->
        runCatching {
            Custom(
                remarks = config.customRemarks(index),
                configJson = formatCustomXrayConfigJson(config),
            ).also { server -> server.check() }
        }.onFailure { error ->
            failedCount += 1
            AndroidAppLogger.warn(
                LogTag,
                "Failed to import custom ${source.logName} JSON config index=$index",
                error,
            )
        }.getOrNull()
    }
    if (failedCount > 0) {
        AndroidAppLogger.warn(
            LogTag,
            "Imported ${servers.size} ${source.logName} custom JSON configs, skipped $failedCount/${configs.size} failed configs",
        )
    }
    return ProxyServerImportResult(
        urlCount = configs.size,
        servers = servers,
    )
}

private fun JsonObject.customRemarks(index: Int): String {
    return string("remarks")
        ?: string("remark")
        ?: string("name")
        ?: string("tag")
        ?: "Custom ${index + 1}"
}

private fun JsonObject.string(name: String): String? {
    return (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}
