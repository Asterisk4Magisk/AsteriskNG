// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json

private val requestHeaderName = Regex("[!#$%&'*+.^_~|`0-9A-Za-z-]+")
private val managedRequestHeaderNames = setOf(
    "host", "connection", "content-length", "accept-encoding", "transfer-encoding", "trailer",
    "te", "upgrade", "keep-alive", "proxy-connection", "proxy-authenticate", "proxy-authorization",
    "if-none-match", "if-modified-since", "user-agent", "x-age-public-key",
)

private val requestHeadersEditorJson = Json { prettyPrint = true }

internal fun defaultSubscriptionRequestHeaders(installationHwid: String): Map<String, String> =
    mapOf("x-hwid" to installationHwid)

internal fun formatSubscriptionRequestHeaders(headers: Map<String, String>): String =
    requestHeadersEditorJson.encodeToString(headers)

internal fun encodeSubscriptionRequestHeaders(headers: Map<String, String>): String =
    Json.encodeToString(headers)

internal fun decodeSubscriptionRequestHeaders(json: String): Map<String, String> =
    try {
        Json.decodeFromString(json)
    } catch (_: SerializationException) {
        error("Invalid subscription request headers JSON")
    }

internal fun legacySubscriptionRequestHeaders(hwid: String?): Map<String, String>? =
    hwid?.trim()?.takeIf(String::isNotEmpty)?.let { mapOf("x-hwid" to it) }

internal fun parseSubscriptionRequestHeaders(text: String): Map<String, String>? =
    runCatching {
        if (text.isBlank()) emptyMap()
        else Json.decodeFromString(UniqueRequestHeadersDeserializer, text).validatedSubscriptionRequestHeaders()
    }.getOrNull()

private object UniqueRequestHeadersDeserializer : DeserializationStrategy<Map<String, String>> {
    override val descriptor = MapSerializer(String.serializer(), String.serializer()).descriptor

    override fun deserialize(decoder: Decoder): Map<String, String> = decoder.decodeStructure(descriptor) {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val keyIndex = decodeElementIndex(descriptor)
            if (keyIndex == CompositeDecoder.DECODE_DONE) break
            val key = decodeStringElement(descriptor, keyIndex)
            val value = decodeStringElement(descriptor, decodeElementIndex(descriptor))
            require(headers.putIfAbsent(key, value) == null) { "Duplicate subscription request header name" }
        }
        headers
    }
}

internal fun Map<String, String>.withoutImplicitSubscriptionHeaders(
    previousHeaders: Map<String, String>?,
    defaultHeaders: Map<String, String>,
): Map<String, String>? = takeUnless { previousHeaders == null && it == defaultHeaders }

private fun Map<String, String>.validatedSubscriptionRequestHeaders(): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    for ((name, value) in this) {
        val key = name.lowercase()
        require(requestHeaderName.matches(name) && key !in managedRequestHeaderNames && key !in headers) {
            "Invalid or duplicate subscription request header name"
        }
        require(value.all { it in ' '..'~' || it == '\t' } && (key != "x-hwid" || value.isNotBlank())) {
            "Invalid subscription request header value"
        }
        headers[key] = value
    }
    return headers
}

internal fun resolveSubscriptionRequestHeaders(
    customHeaders: Map<String, String>?,
    installationHwid: () -> String,
): Map<String, String> =
    customHeaders?.validatedSubscriptionRequestHeaders() ?: defaultSubscriptionRequestHeaders(installationHwid())
