// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

internal typealias MihomoYamlMap = Map<String, Any?>

internal fun MihomoYamlMap.requiredString(vararg names: String): String {
    return string(*names) ?: unsupported("${names.first()} is required")
}

internal fun MihomoYamlMap.string(vararg names: String): String? {
    return names.firstNotNullOfOrNull { name -> this[name].scalarString() }
}

internal fun MihomoYamlMap.boolean(name: String): Boolean? {
    return when (val value = this[name]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }

        else -> null
    }
}

internal fun MihomoYamlMap.map(name: String): MihomoYamlMap? {
    return this[name].asStringMap()
}

internal fun MihomoYamlMap.list(name: String): List<Any?>? {
    return this[name].asList()
}

internal fun MihomoYamlMap.csvString(name: String): String? {
    return this[name].scalarStringList()
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString(",")
}

internal fun MihomoYamlMap.headerString(name: String): String? {
    val value = entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
    return value.scalarStringList()
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString(",")
}

internal fun MihomoYamlMap.hasTlsFields(): Boolean {
    return listOf(
        "sni",
        "servername",
        "fingerprint",
        "client-fingerprint",
        "certificate",
        "private-key",
    ).any { key -> !string(key).isNullOrBlank() } ||
        list("alpn").orEmpty().isNotEmpty() ||
        map("ech-opts")?.isNotEmpty() == true ||
        map("reality-opts")?.isNotEmpty() == true
}

internal fun MihomoYamlMap.v2raySecurity(defaultSecurity: String): String {
    val realityOpts = map("reality-opts")
    if (realityOpts != null && realityOpts.isNotEmpty()) {
        return "reality"
    }
    return if (defaultSecurity == "tls" || boolean("tls") == true || hasTlsFields()) {
        "tls"
    } else {
        "none"
    }
}

internal fun MihomoYamlMap.ensureNoMihomoTlsOptions(message: String) {
    if (boolean("tls") == true || hasTlsFields()) {
        unsupported(message)
    }
}

internal fun Any?.scalarString(): String? {
    val value = when (this) {
        is String -> this
        is Number -> toString()
        is Boolean -> toString()
        else -> return null
    }.trim()
    return value.takeIf(String::isNotBlank)
}

internal fun Any?.scalarStringList(): List<String> {
    return when (this) {
        is Iterable<*> -> mapNotNull { item -> item.scalarString() }
        else -> scalarString()?.split(',')?.map(String::trim).orEmpty()
    }.filter(String::isNotBlank)
}

internal fun Any?.asStringMap(): MihomoYamlMap? {
    val map = this as? Map<*, *> ?: return null
    return map.entries.associate { (key, value) -> key.toString() to value }
}

internal fun Any?.asList(): List<Any?>? {
    return (this as? Iterable<*>)?.toList()
}

internal fun unsupported(message: String): Nothing {
    throw UnsupportedMihomoProxyException(message)
}

internal class UnsupportedMihomoProxyException(message: String) : IllegalArgumentException(message)
