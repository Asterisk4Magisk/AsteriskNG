package features.proxy.server.usecase.importer

import features.proxy.server.model.VLESS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun MihomoYamlMap.toMihomoVlessProxyServer(): VLESS {
    return VLESS(
        remarks = requiredString("name"),
        server = requiredString("server"),
        port = requiredString("port"),
        id = requiredString("uuid", "id"),
        encryption = string("encryption").orEmpty(),
        flow = string("flow").orEmpty(),
        parms = toMihomoV2RayParameters(
            defaultSecurity = "none",
            xhttpHost = { xhttpOpts -> xhttpOpts.xhttpHost() },
            xhttpExtra = { xhttpOpts, parentNode -> xhttpOpts.toXrayXhttpExtraString(parentNode) },
        ),
    )
}

private fun MihomoYamlMap.xhttpHost(fallback: MihomoYamlMap? = null): String? {
    return string("host")
        ?: map("headers")?.headerString("Host")
        ?: fallback?.string("host")
        ?: fallback?.map("headers")?.headerString("Host")
}

private fun MihomoYamlMap.toXrayXhttpExtraString(parentNode: MihomoYamlMap): String? {
    return toXrayXhttpExtra(parentNode).toCompactJsonStringOrNull()
}

private fun MihomoYamlMap.toXrayXhttpExtra(
    parentNode: MihomoYamlMap,
    fallback: MihomoYamlMap? = null,
    includeDownloadSettings: Boolean = true,
): JsonObject {
    return buildJsonObject {
        putXrayXhttpHeaders(primary = this@toXrayXhttpExtra, fallback = fallback)
        putXrayXhttpMappedFields(primary = this@toXrayXhttpExtra, fallback = fallback)
        val xmux = buildXrayXhttpXmux(
            primary = map("reuse-settings") ?: map("xmux"),
            fallback = fallback?.map("reuse-settings") ?: fallback?.map("xmux"),
        )
        if (xmux.isNotEmpty()) {
            put("xmux", xmux)
        }
        if (includeDownloadSettings) {
            val downloadSettings = map("download-settings")
            if (downloadSettings != null) {
                put("downloadSettings", parentNode.toXrayDownloadSettings(this@toXrayXhttpExtra, downloadSettings))
            }
        }
    }
}

private fun MihomoYamlMap.toXrayDownloadSettings(
    uploadXhttpOpts: MihomoYamlMap,
    downloadSettings: MihomoYamlMap,
): JsonObject {
    val network = downloadSettings.string("network")?.lowercase()
    if (network != null && network !in setOf("xhttp", "splithttp")) {
        unsupported("XHTTP download-settings network must be xhttp")
    }
    val downloadXhttpOpts = downloadSettings.map("xhttp-opts") ?: downloadSettings
    return buildJsonObject {
        putStringIfNotBlank("address", downloadSettings.string("server", "address") ?: string("server", "address"))
        putXrayPort("port", downloadSettings.string("port") ?: string("port"))
        put("network", "xhttp")

        val security = downloadSettings.v2raySecurityOrInherited(this@toXrayDownloadSettings)
        if (security != "none") {
            put("security", security)
        }
        when (security) {
            "tls" -> putJsonObject("tlsSettings") {
                putXrayTlsSettings(primary = downloadSettings, fallback = this@toXrayDownloadSettings)
            }

            "reality" -> putJsonObject("realitySettings") {
                putXrayRealitySettings(primary = downloadSettings, fallback = this@toXrayDownloadSettings)
            }
        }

        putJsonObject("xhttpSettings") {
            putStringIfNotBlank("path", downloadXhttpOpts.string("path") ?: uploadXhttpOpts.string("path"))
            putStringIfNotBlank("host", downloadXhttpOpts.xhttpHost(uploadXhttpOpts))
            putStringIfNotBlank("mode", downloadXhttpOpts.string("mode") ?: uploadXhttpOpts.string("mode"))
            val extra = downloadXhttpOpts.toXrayXhttpExtra(
                parentNode = this@toXrayDownloadSettings,
                fallback = uploadXhttpOpts,
                includeDownloadSettings = false,
            )
            if (extra.isNotEmpty()) {
                put("extra", extra)
            }
        }
    }
}

private fun JsonObjectBuilder.putXrayXhttpMappedFields(
    primary: MihomoYamlMap,
    fallback: MihomoYamlMap?,
) {
    XrayXhttpFields.forEach { field ->
        putXrayXhttpMappedField(field, primary, fallback)
    }
}

private fun buildXrayXhttpXmux(
    primary: MihomoYamlMap?,
    fallback: MihomoYamlMap?,
): JsonObject {
    val source: MihomoYamlMap = primary ?: emptyMap()
    return buildJsonObject {
        XrayXhttpXmuxFields.forEach { field ->
            putXrayXhttpMappedField(field, source, fallback)
        }
    }
}

private fun JsonObjectBuilder.putXrayXhttpMappedField(
    field: XrayXhttpField,
    primary: MihomoYamlMap,
    fallback: MihomoYamlMap?,
) {
    val sourceNames = field.sourceNames.toTypedArray()
    val value = primary.firstRaw(*sourceNames) ?: fallback?.firstRaw(*sourceNames) ?: return
    val element = when (field.valueType) {
        XrayXhttpValueType.Scalar -> value.scalarJsonElement("XHTTP ${field.sourceNames.first()}")
        XrayXhttpValueType.Boolean -> value.booleanJsonElement("XHTTP ${field.sourceNames.first()}")
        XrayXhttpValueType.Integer -> value.integerJsonElement("XHTTP ${field.sourceNames.first()}")
    }
    if (element != null) {
        put(field.targetName, element)
    }
}

private fun JsonObjectBuilder.putXrayXhttpHeaders(
    primary: MihomoYamlMap,
    fallback: MihomoYamlMap?,
) {
    val headers = buildJsonObject {
        fallback?.map("headers")?.putXrayXhttpHeaderEntries(this)
        primary.map("headers")?.putXrayXhttpHeaderEntries(this)
    }
    if (headers.isNotEmpty()) {
        put("headers", headers)
    }
}

private fun MihomoYamlMap.putXrayXhttpHeaderEntries(builder: JsonObjectBuilder) {
    entries.forEach { (name, value) ->
        if (!name.equals("Host", ignoreCase = true)) {
            value.headerJsonString("XHTTP header $name")?.let { builder.put(name, it) }
        }
    }
}

private fun MihomoYamlMap.v2raySecurityOrInherited(fallback: MihomoYamlMap): String {
    return if (hasSecurityOverride()) {
        v2raySecurity(defaultSecurity = "none")
    } else {
        fallback.v2raySecurity(defaultSecurity = "none")
    }
}

private fun MihomoYamlMap.hasSecurityOverride(): Boolean {
    return containsKey("tls") || hasTlsFields()
}

private fun JsonObjectBuilder.putXrayTlsSettings(
    primary: MihomoYamlMap,
    fallback: MihomoYamlMap,
) {
    putStringIfNotBlank("serverName", primary.string("servername", "sni") ?: fallback.string("servername", "sni"))
    putStringIfNotBlank(
        "fingerprint",
        (primary.string("client-fingerprint") ?: fallback.string("client-fingerprint"))?.lowercase(),
    )
    val alpn = (primary.firstRaw("alpn") ?: fallback.firstRaw("alpn")).scalarStringList()
    if (alpn.isNotEmpty()) {
        put("alpn", alpn.toJsonStringArray())
    }
    putStringIfNotBlank("echConfigList", (primary.map("ech-opts") ?: fallback.map("ech-opts"))?.string("config"))
    putStringIfNotBlank("pinnedPeerCertSha256", primary.string("fingerprint") ?: fallback.string("fingerprint"))
    if (primary.boolean("skip-cert-verify") ?: fallback.boolean("skip-cert-verify") == true) {
        put("allowInsecure", true)
    }
}

private fun JsonObjectBuilder.putXrayRealitySettings(
    primary: MihomoYamlMap,
    fallback: MihomoYamlMap,
) {
    val realityOpts = primary.map("reality-opts") ?: fallback.map("reality-opts") ?: emptyMap()
    if (realityOpts.boolean("support-x25519mlkem768") == true) {
        unsupported("Reality X25519-MLKEM768 is not supported")
    }
    putStringIfNotBlank("serverName", primary.string("servername", "sni") ?: fallback.string("servername", "sni"))
    putStringIfNotBlank(
        "fingerprint",
        (primary.string("client-fingerprint") ?: fallback.string("client-fingerprint"))?.lowercase(),
    )
    putStringIfNotBlank("password", realityOpts.string("public-key", "publicKey"))
    putStringIfNotBlank("shortId", realityOpts.string("short-id", "shortId"))
    putStringIfNotBlank("mldsa65Verify", realityOpts.string("mldsa65-verify", "mldsa65Verify"))
    putStringIfNotBlank("spiderX", realityOpts.string("spider-x", "spiderX"))
}

private fun JsonObjectBuilder.putStringIfNotBlank(name: String, value: String?) {
    if (!value.isNullOrBlank()) {
        put(name, value)
    }
}

private fun JsonObjectBuilder.putXrayPort(name: String, value: String?) {
    val port = value?.toIntOrNull() ?: unsupported("XHTTP download-settings port is required")
    put(name, port)
}

private fun MihomoYamlMap.firstRaw(vararg names: String): Any? {
    return names.firstNotNullOfOrNull { name ->
        if (containsKey(name)) this[name] else null
    }
}

private fun Any?.scalarJsonElement(fieldName: String): JsonElement? {
    return when (this) {
        is String -> trim().takeIf(String::isNotBlank)?.let(::JsonPrimitive)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        null -> null
        else -> unsupported("$fieldName must be a scalar value")
    }
}

private fun Any?.booleanJsonElement(fieldName: String): JsonElement? {
    return when (this) {
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(toInt() != 0)
        is String -> when (trim().lowercase()) {
            "" -> null
            "1", "true", "yes", "on" -> JsonPrimitive(true)
            "0", "false", "no", "off" -> JsonPrimitive(false)
            else -> unsupported("$fieldName must be boolean")
        }

        null -> null
        else -> unsupported("$fieldName must be boolean")
    }
}

private fun Any?.integerJsonElement(fieldName: String): JsonElement? {
    return when (this) {
        is Number -> JsonPrimitive(toLong())
        is String -> trim()
            .takeIf(String::isNotBlank)
            ?.toLongOrNull()
            ?.let(::JsonPrimitive)
            ?: if (trim().isBlank()) null else unsupported("$fieldName must be integer")

        null -> null
        else -> unsupported("$fieldName must be integer")
    }
}

private fun Any?.headerJsonString(fieldName: String): String? {
    return when (this) {
        is Iterable<*> -> map { item -> item.scalarStringAllowBlank() ?: unsupported("$fieldName must be scalar") }
            .joinToString(",")

        null -> null
        else -> scalarStringAllowBlank() ?: unsupported("$fieldName must be scalar")
    }
}

private fun List<String>.toJsonStringArray(): JsonArray {
    return buildJsonArray {
        forEach { value -> add(JsonPrimitive(value)) }
    }
}

private fun JsonObject.toCompactJsonStringOrNull(): String? {
    return takeIf { it.isNotEmpty() }?.toString()
}

private fun Any?.scalarStringAllowBlank(): String? {
    return when (this) {
        is String -> this
        is Number -> toString()
        is Boolean -> toString()
        else -> null
    }
}

private enum class XrayXhttpValueType {
    Scalar,
    Boolean,
    Integer,
}

private data class XrayXhttpField(
    val targetName: String,
    val sourceNames: List<String>,
    val valueType: XrayXhttpValueType,
)

private fun scalarXhttpField(targetName: String, vararg sourceNames: String): XrayXhttpField {
    return XrayXhttpField(targetName, listOf(*sourceNames), XrayXhttpValueType.Scalar)
}

private fun booleanXhttpField(targetName: String, vararg sourceNames: String): XrayXhttpField {
    return XrayXhttpField(targetName, listOf(*sourceNames), XrayXhttpValueType.Boolean)
}

private fun integerXhttpField(targetName: String, vararg sourceNames: String): XrayXhttpField {
    return XrayXhttpField(targetName, listOf(*sourceNames), XrayXhttpValueType.Integer)
}

private val XrayXhttpFields = listOf(
    scalarXhttpField("xPaddingBytes", "x-padding-bytes"),
    booleanXhttpField("xPaddingObfsMode", "x-padding-obfs-mode"),
    scalarXhttpField("xPaddingKey", "x-padding-key"),
    scalarXhttpField("xPaddingHeader", "x-padding-header"),
    scalarXhttpField("xPaddingPlacement", "x-padding-placement"),
    scalarXhttpField("xPaddingMethod", "x-padding-method"),
    scalarXhttpField("uplinkHTTPMethod", "uplink-http-method"),
    scalarXhttpField("sessionPlacement", "session-placement"),
    scalarXhttpField("sessionKey", "session-key"),
    scalarXhttpField("seqPlacement", "seq-placement"),
    scalarXhttpField("seqKey", "seq-key"),
    scalarXhttpField("uplinkDataPlacement", "uplink-data-placement"),
    scalarXhttpField("uplinkDataKey", "uplink-data-key"),
    scalarXhttpField("uplinkChunkSize", "uplink-chunk-size"),
    booleanXhttpField("noGRPCHeader", "no-grpc-header"),
    booleanXhttpField("noSSEHeader", "no-sse-header"),
    scalarXhttpField("scMaxEachPostBytes", "sc-max-each-post-bytes"),
    scalarXhttpField("scMinPostsIntervalMs", "sc-min-posts-interval-ms", "sc-min-post-interval-ms"),
    integerXhttpField("scMaxBufferedPosts", "sc-max-buffered-posts"),
    scalarXhttpField("scStreamUpServerSecs", "sc-stream-up-server-secs"),
    integerXhttpField("serverMaxHeaderBytes", "server-max-header-bytes"),
)

private val XrayXhttpXmuxFields = listOf(
    scalarXhttpField("maxConcurrency", "max-concurrency"),
    scalarXhttpField("maxConnections", "max-connections"),
    scalarXhttpField("cMaxReuseTimes", "c-max-reuse-times"),
    scalarXhttpField("hMaxRequestTimes", "h-max-request-times"),
    scalarXhttpField("hMaxReusableSecs", "h-max-reusable-secs"),
    integerXhttpField("hKeepAlivePeriod", "h-keep-alive-period"),
)
