// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import app.ProxyServerState
import data.toPersistedProxyServer
import features.proxy.server.model.Custom
import features.proxy.server.model.ProxyServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun matchImportedProxyServers(
    previous: List<ProxyServerState>,
    imported: List<ProxyServer<*>>,
): Map<Int, Int> {
    // As in BOX, names are identities only when unique in both full snapshots.
    // Exact matches must not make previously ambiguous names appear unique.
    val previousByName = previous.groupBy { it.server.subscriptionNameIdentity() }
    val importedByName = imported.withIndex().groupBy { it.value.subscriptionNameIdentity() }
    val previousByContent = previous.groupBy { it.server.subscriptionContentIdentity() }
        .mapValues { (_, matches) -> ArrayDeque(matches) }

    return buildMap {
        val reusedIds = mutableSetOf<Int>()
        imported.forEachIndexed { index, server ->
            val identity = server.subscriptionContentIdentity() ?: return@forEachIndexed
            val matched = previousByContent[identity]?.removeFirstOrNull() ?: return@forEachIndexed
            put(index, matched.id)
            reusedIds += matched.id
        }
        importedByName.forEach { (name, importedMatches) ->
            if (name.second.isBlank()) return@forEach
            val old = previousByName[name]?.singleOrNull() ?: return@forEach
            val item = importedMatches.singleOrNull() ?: return@forEach
            if (item.index !in this && old.id !in reusedIds) {
                put(item.index, old.id)
                reusedIds += old.id
            }
        }
    }
}

private fun ProxyServer<*>.subscriptionNameIdentity(): Pair<String, String> {
    val info = getInfo()
    return info.protocol to info.remarks.trim()
}

private data class SubscriptionContentIdentity(
    val protocol: String,
    val payload: JsonElement,
)

private fun ProxyServer<*>.subscriptionContentIdentity(): SubscriptionContentIdentity? = runCatching {
    val persisted = toPersistedProxyServer()
    val payload = persisted.payload as? JsonObject ?: return@runCatching null
    val normalized = payload.toMutableMap().apply {
        put("remarks", JsonPrimitive(getInfo().remarks.trim()))
        if (this@subscriptionContentIdentity is Custom) {
            put("configJson", Json.parseToJsonElement(configJson))
        }
    }
    // JsonObject equality ignores key order while preserving array order and all
    // credentials/protocol fields. NG has no rewritten detour/resolver references.
    SubscriptionContentIdentity(persisted.protocol, JsonObject(normalized))
}.getOrNull()
