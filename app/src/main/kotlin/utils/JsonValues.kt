// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

internal fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.arrayValue(name: String): JsonArray? = this[name] as? JsonArray

internal fun Iterable<String>.toJsonStringArray(): JsonArray = buildJsonArray {
    forEach { value -> add(JsonPrimitive(value)) }
}

/** Preserve nonblank values verbatim, including whitespace in credentials. */
internal fun JsonObjectBuilder.putIfNotBlank(name: String, value: String?) {
    if (!value.isNullOrBlank()) {
        put(name, value)
    }
}

internal fun JsonObjectBuilder.putTrimmedIfNotBlank(name: String, value: String?) {
    putIfNotBlank(name, value?.trim())
}
