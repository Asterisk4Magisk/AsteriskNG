// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.logs.AndroidAppLogger
import features.proxy.server.model.Custom
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.formatCustomXrayConfigJson
import features.proxy.server.usecase.EmptyProxyServerImportResult
import features.proxy.server.usecase.ProxyServerImportContext
import features.proxy.server.usecase.ProxyServerImportResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val LogTag = "ProxyServerJsonImport"

// Parsing into a JSON tree and formatting it below removes comments while retaining
// comment-like text inside strings. All other JSON syntax remains strict.
private val XrayImportJson = Json(ProxyServer.json) {
    allowComments = true
}

internal suspend fun parseProxyServersFromJsonConfig(
    text: String,
    context: ProxyServerImportContext,
): ProxyServerImportResult {
    val source = context.source
    val root = runCatching {
        XrayImportJson.parseToJsonElement(text.trimStart(ImportByteOrderMark))
    }.getOrNull() ?: return EmptyProxyServerImportResult
    val configs = when (root) {
        is JsonObject -> listOf(root)
        is JsonArray -> root.filterIsInstance<JsonObject>()
        else -> emptyList()
    }
    if (configs.isEmpty()) {
        return EmptyProxyServerImportResult
    }

    var failedCount = 0
    val servers = configs.mapIndexedNotNull { index, config ->
        runCatching {
            val server = Custom(
                remarks = config.customRemarks(index),
                configJson = formatCustomXrayConfigJson(config),
            )
            val issues = server.validateBasic()
            if (issues.isNotEmpty()) {
                throw IllegalArgumentException("Basic validation failed: ${issues.joinToString { it.error.name }}")
            }
            server
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
