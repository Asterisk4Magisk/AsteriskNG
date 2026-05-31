// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import android.content.Context
import features.logs.androidCoreLogAccessFile
import features.logs.androidCoreLogErrorFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

internal data class XrayCoreLogPaths(
    val accessLogPath: String,
    val errorLogPath: String,
)

internal val XrayConfigJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal val XrayConfigPrettyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

internal object XrayTags {
    const val PROXY = "proxy"
    const val DIRECT = "direct"
    const val BLOCK = "block"
    const val DNS_OUT = "dns-out"
    const val PROXY_DNS = "dns-proxy"
    const val DIRECT_DNS = "dns-direct"
    const val LOCAL_SOCKS_INBOUND = "socks-in"
    const val VPN_APPEND_HTTP_INBOUND = "vpn-http-in"
    const val TPROXY_INBOUND = "tproxy-in"
    const val TPROXY_SOCKS_INBOUND = "tproxy-socks-in"
    const val TPROXY_HTTP_INBOUND = "tproxy-http-in"
    const val FRAGMENT = "fragment"
    const val VPN_TUN_INBOUND = "vpn-tun-in"
    const val TUN2SOCKS_INBOUND = "tun2socks-in"
    const val TUN2SOCKS_HTTP_INBOUND = "tun2socks-http-in"

    val FIXED_OUTBOUND_TAGS = setOf(
        PROXY,
        DIRECT,
        BLOCK,
        DNS_OUT,
        FRAGMENT,
    )
}

internal object XrayProtocols {
    const val TUN = "tun"
    const val DNS = "dns"
    const val FREEDOM = "freedom"
    const val BLACKHOLE = "blackhole"
    const val SOCKS = "socks"
    const val HTTP = "http"
    const val TUNNEL = "tunnel"
}

internal fun xraySniffingDestOverrides(enableFakeDns: Boolean): List<String> {
    return buildList {
        add("http")
        add("tls")
        add("quic")
        if (enableFakeDns) {
            add("fakedns")
        }
    }
}

internal fun Context.prepareXrayCoreLogPaths(): XrayCoreLogPaths {
    return XrayCoreLogPaths(
        accessLogPath = androidCoreLogAccessFile().absolutePath,
        errorLogPath = androidCoreLogErrorFile().absolutePath,
    )
}

internal fun XrayCoreLogPaths.logDirectoryPath(): String {
    return File(errorLogPath).parentFile?.absolutePath
        ?: File(accessLogPath).parentFile?.absolutePath
        ?: error("xray log directory is unavailable")
}

internal fun Iterable<String>.toJsonStringArray(): JsonArray {
    return buildJsonArray {
        forEach { item -> add(JsonPrimitive(item)) }
    }
}

internal fun Iterable<JsonObject>.toJsonObjectArray(): JsonArray {
    return buildJsonArray {
        forEach { item -> add(item) }
    }
}

internal fun JsonObject.updated(block: JsonObjectBuilder.() -> Unit): JsonObject {
    return buildJsonObject {
        this@updated.forEach { (name, value) -> put(name, value) }
        block()
    }
}

internal fun JsonObject.updatedWithout(
    keys: Set<String>,
    block: JsonObjectBuilder.() -> Unit = {},
): JsonObject {
    return buildJsonObject {
        this@updatedWithout.forEach { (name, value) ->
            if (name !in keys) {
                put(name, value)
            }
        }
        block()
    }
}

internal fun JsonObject.updatedNestedObject(
    name: String,
    nestedName: String,
    block: JsonObjectBuilder.() -> Unit,
): JsonObject {
    val parent = objectValue(name) ?: buildJsonObject {}
    val nested = parent.objectValue(nestedName) ?: buildJsonObject {}
    return updated {
        put(
            name,
            parent.updated {
                put(nestedName, nested.updated(block))
            },
        )
    }
}

internal fun JsonObject.stringValue(name: String): String? {
    return (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}

internal fun JsonObject.objectValue(name: String): JsonObject? {
    return this[name] as? JsonObject
}

internal fun JsonObject.arrayValue(name: String): JsonArray? {
    return this[name] as? JsonArray
}

internal fun JsonObjectBuilder.putIfNotBlank(name: String, value: String?) {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isNotEmpty()) {
        put(name, trimmed)
    }
}

internal fun JsonObjectBuilder.putJsonStringArrayIfNotEmpty(name: String, values: List<String>) {
    if (values.isNotEmpty()) {
        put(name, values.toJsonStringArray())
    }
}

internal fun JsonObjectBuilder.putIfNotEmpty(name: String, value: JsonObject) {
    if (value.isNotEmpty()) {
        put(name, value)
    }
}

internal fun JsonObjectBuilder.putIfNotNull(name: String, value: JsonElement?) {
    if (value != null) {
        put(name, value)
    }
}
