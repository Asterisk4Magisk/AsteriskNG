// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.routing.usecase

import app.DefaultRouteOutboundTag
import features.routing.model.RouteRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ui.clipboard.ClipboardImportException
import ui.clipboard.ClipboardImportFailure
import ui.clipboard.ClipboardImportMode
import utils.toTrimmedNonEmptyDistinctList

@Serializable
internal data class RouteRuleClipboardItem(
    val remarks: String = "",
    val outboundTag: String = DefaultRouteOutboundTag,
    val domain: List<String> = emptyList(),
    val ip: List<String> = emptyList(),
    val process: List<String> = emptyList(),
    val port: String = "",
    val protocol: String = "",
    val network: String = "",
    val enabled: Boolean = true,
)

internal data class RouteRuleClipboardApplyResult(
    val rules: List<RouteRule>,
    val nextRuleId: Int,
)

internal fun encodeRouteRulesForClipboard(rules: List<RouteRule>): String {
    return ClipboardJson.encodeToString(rules.map(RouteRule::toClipboardItem))
}

internal fun decodeRouteRulesFromClipboard(text: String): List<RouteRuleClipboardItem> {
    val normalizedText = text.trimStart(ImportByteOrderMark).trim()
    if (normalizedText.isEmpty()) {
        throw ClipboardImportException(ClipboardImportFailure.EmptyClipboard)
    }

    val rules = runCatching {
        ClipboardJson.decodeFromString<List<RouteRuleClipboardItem>>(normalizedText)
            .map(RouteRuleClipboardItem::normalized)
    }.getOrElse { error ->
        if (error !is SerializationException && error !is IllegalArgumentException) {
            throw error
        }
        runCatching { decodeV2RayRouteRulesFromClipboard(normalizedText) }
            .getOrElse { throw ClipboardImportException(ClipboardImportFailure.UnsupportedFormat) }
    }

    if (rules.isEmpty()) {
        throw ClipboardImportException(ClipboardImportFailure.NoValidRoutingRules)
    }
    return rules
}

internal fun applyRouteRuleClipboardImport(
    existingRules: List<RouteRule>,
    importedRules: List<RouteRuleClipboardItem>,
    nextRuleId: Int,
    mode: ClipboardImportMode,
): RouteRuleClipboardApplyResult {
    return when (mode) {
        ClipboardImportMode.Replace -> {
            val rules = importedRules.toRouteRules(startId = 1)
            RouteRuleClipboardApplyResult(
                rules = rules,
                nextRuleId = rules.nextRouteRuleId(defaultValue = 1),
            )
        }

        ClipboardImportMode.Merge -> {
            val existingKeys = existingRules.mapTo(mutableSetOf()) { rule -> rule.effectiveKey() }
            var candidateId = nextRuleId
            val appendedRules = importedRules.mapNotNull { item ->
                val normalizedItem = item.normalized()
                val key = normalizedItem.effectiveKey()
                if (!existingKeys.add(key)) {
                    null
                } else {
                    normalizedItem.toRouteRule(candidateId++)
                }
            }
            RouteRuleClipboardApplyResult(
                rules = existingRules + appendedRules,
                nextRuleId = maxOf(nextRuleId, candidateId),
            )
        }
    }
}

private fun decodeV2RayRouteRulesFromClipboard(text: String): List<RouteRuleClipboardItem> {
    return ClipboardJson.decodeFromString<List<V2RayRouteRuleClipboardItem>>(text)
        .map { item ->
            RouteRuleClipboardItem(
                remarks = item.remarks.orEmpty(),
                outboundTag = item.outboundTag,
                domain = item.domain.orEmpty(),
                ip = item.ip.orEmpty(),
                process = item.process.orEmpty(),
                port = item.port.orEmpty(),
                protocol = item.protocol.toProtocolText(),
                network = item.network.orEmpty(),
                enabled = item.enabled,
            ).normalized()
        }
}

@Serializable
private data class V2RayRouteRuleClipboardItem(
    val remarks: String? = "",
    val ip: List<String>? = null,
    val domain: List<String>? = null,
    val process: List<String>? = null,
    val outboundTag: String = "",
    val port: String? = null,
    val network: String? = null,
    val protocol: JsonElement? = null,
    val enabled: Boolean = true,
)

private fun RouteRule.toClipboardItem(): RouteRuleClipboardItem {
    return RouteRuleClipboardItem(
        remarks = remarks.trim(),
        outboundTag = outboundTag.trim().ifBlank { DefaultRouteOutboundTag },
        domain = domain.toTrimmedNonEmptyDistinctList(),
        ip = ip.toTrimmedNonEmptyDistinctList(),
        process = process.toTrimmedNonEmptyDistinctList(),
        port = port.trim(),
        protocol = protocol.trim(),
        network = network.trim(),
        enabled = enabled,
    )
}

private fun List<RouteRuleClipboardItem>.toRouteRules(startId: Int): List<RouteRule> {
    var nextId = startId
    return map { item -> item.normalized().toRouteRule(nextId++) }
}

private fun RouteRuleClipboardItem.toRouteRule(id: Int): RouteRule {
    val normalized = normalized()
    return RouteRule(
        id = id,
        remarks = normalized.remarks,
        outboundTag = normalized.outboundTag,
        domain = normalized.domain,
        ip = normalized.ip,
        process = normalized.process,
        port = normalized.port,
        protocol = normalized.protocol,
        network = normalized.network,
        enabled = normalized.enabled,
    )
}

private fun RouteRuleClipboardItem.normalized(): RouteRuleClipboardItem {
    return copy(
        remarks = remarks.trim(),
        outboundTag = outboundTag.trim().ifBlank { DefaultRouteOutboundTag },
        domain = domain.toTrimmedNonEmptyDistinctList(),
        ip = ip.toTrimmedNonEmptyDistinctList(),
        process = process.toTrimmedNonEmptyDistinctList(),
        port = port.trim(),
        protocol = protocol.trim(),
        network = network.trim(),
    )
}

private fun RouteRule.effectiveKey(): RouteRuleEffectiveKey {
    return toClipboardItem().effectiveKey()
}

private fun RouteRuleClipboardItem.effectiveKey(): RouteRuleEffectiveKey {
    val normalized = normalized()
    return RouteRuleEffectiveKey(
        outboundTag = normalized.outboundTag,
        domain = normalized.domain,
        ip = normalized.ip,
        process = normalized.process,
        port = normalized.port,
        protocol = normalized.protocol,
        network = normalized.network,
    )
}

private data class RouteRuleEffectiveKey(
    val outboundTag: String,
    val domain: List<String>,
    val ip: List<String>,
    val process: List<String>,
    val port: String,
    val protocol: String,
    val network: String,
)

private fun JsonElement?.toProtocolText(): String {
    return when (this) {
        null -> ""
        is JsonArray -> mapNotNull { element ->
            element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }.joinToString(",")
        is JsonPrimitive -> contentOrNull.orEmpty().trim()
        else -> ""
    }
}

private fun List<RouteRule>.nextRouteRuleId(defaultValue: Int): Int {
    return maxOf(defaultValue, (maxOfOrNull { rule -> rule.id } ?: 0) + 1)
}

private val ClipboardJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private const val ImportByteOrderMark = '\uFEFF'
