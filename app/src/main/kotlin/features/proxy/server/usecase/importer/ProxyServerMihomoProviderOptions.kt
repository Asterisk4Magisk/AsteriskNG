// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.proxy.server.model.HTTP
import features.proxy.server.model.Hysteria2
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import features.proxy.server.model.Trojan
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import features.proxy.server.model.Wireguard
import java.net.URLDecoder

/** The subset of provider overrides represented by NG's structured Xray nodes. */
internal class MihomoProviderOptions private constructor(
    private val filters: List<Regex>,
    private val excludedNames: List<Regex>,
    private val excludedTypes: Set<String>,
    private val overrides: MihomoYamlMap,
    private val replacements: List<Pair<Regex, String>>,
) {
    val enforcesCertificateVerification: Boolean
        get() = overrides.boolean("skip-cert-verify") == false

    fun apply(nodes: List<MihomoYamlMap>): List<MihomoYamlMap> = select(
        nodes,
        name = { it.string("name").orEmpty() },
        type = { it.string("type").orEmpty() },
    ).map { node ->
        node.toMutableMap().apply {
            this["name"] = renamed(node.string("name").orEmpty())
            listOf("skip-cert-verify", "name-cert-verify", "up", "down").forEach { key ->
                if (overrides.containsKey(key)) this[key] = overrides[key]
            }
            validateCertificateNameOverride(node.string("type").orEmpty())
        }
    }

    fun applyServers(servers: List<ProxyServer<*>>): List<ProxyServer<*>> = select(
        servers,
        name = { it.getInfo().remarks },
        type = { it.getInfo().protocol },
    ).map { server ->
        val name = renamed(server.getInfo().remarks)
        val certName = overrides.string("name-cert-verify")
        validateCertificateNameOverride(server.getInfo().protocol)
        when (server) {
            is HTTP -> server.copy(remarks = name)
            is Socks -> server.copy(remarks = name)
            is Shadowsocks -> server.copy(remarks = name)
            is VMess -> server.copy(remarks = name, parms = server.parms.copy(vcn = certName ?: server.parms.vcn))
            is VLESS -> server.copy(remarks = name, parms = server.parms.copy(vcn = certName ?: server.parms.vcn))
            is Trojan -> server.copy(remarks = name, parms = server.parms.copy(vcn = certName ?: server.parms.vcn))
            is Hysteria2 -> server.copy(
                remarks = name,
                up = overrides.string("up") ?: server.up,
                down = overrides.string("down") ?: server.down,
            )
            is Wireguard -> server.copy(remarks = name)
            else -> providerUnsupported("provider overrides require a supported structured proxy")
        }
    }

    private fun validateCertificateNameOverride(type: String) {
        if (overrides.containsKey("name-cert-verify") && canonicalType(type) !in setOf("vmess", "vless", "trojan")) {
            providerUnsupported("name-cert-verify override cannot be represented for proxy type ${canonicalType(type)}")
        }
    }

    private fun <T> select(nodes: List<T>, name: (T) -> String, type: (T) -> String): List<T> {
        val seen = mutableSetOf<String>()
        // Mihomo's backtick-separated include filters also determine output order.
        return filters.flatMap { filter ->
            nodes.filter { node ->
                val originalName = name(node)
                canonicalType(type(node)) !in excludedTypes &&
                    excludedNames.none { it.containsMatchIn(originalName) } &&
                    filter.containsMatchIn(originalName) && seen.add(originalName)
            }
        }
    }

    private fun renamed(original: String): String {
        val replaced = replacements.fold(original) { value, (pattern, target) ->
            pattern.replace(value) { match -> expandMihomoReplacement(target, value, match) }
        }
        return overrides["additional-prefix"].rawProviderString().orEmpty() + replaced +
            overrides["additional-suffix"].rawProviderString().orEmpty()
    }

    companion object {
        fun from(provider: MihomoYamlMap): MihomoProviderOptions {
            val overrides = provider.map("override") ?: if (provider["override"] == null) emptyMap() else {
                providerUnsupported("provider override must be an object")
            }
            val supported = setOf(
                "additional-prefix", "additional-suffix", "proxy-name", "skip-cert-verify",
                "name-cert-verify", "up", "down",
            )
            overrides.keys.firstOrNull { it !in supported && !overrides.isNeutralProviderOverride(it) }?.let { key ->
                providerUnsupported("provider override $key cannot be represented by Xray node import")
            }
            if (overrides.containsKey("skip-cert-verify") && overrides.boolean("skip-cert-verify") != false) {
                providerUnsupported("skip-cert-verify override must be false for Xray")
            }
            listOf("additional-prefix", "additional-suffix", "name-cert-verify", "up", "down").forEach { key ->
                if (overrides.containsKey(key) && overrides[key].rawProviderString() == null) {
                    providerUnsupported("provider override $key must be a scalar")
                }
            }
            val replacements = overrides.list("proxy-name")?.map { raw ->
                val item = raw.asStringMap() ?: providerUnsupported("proxy-name entry must be an object")
                val pattern = item["pattern"].rawProviderString() ?: providerUnsupported("proxy-name pattern is required")
                val target = item["target"].rawProviderString() ?: providerUnsupported("proxy-name target is required")
                providerRegex(pattern, "proxy-name") to target
            } ?: if (overrides["proxy-name"] == null) emptyList() else {
                providerUnsupported("proxy-name must be a list")
            }
            return MihomoProviderOptions(
                filters = provider.string("filter").orEmpty().split('`').map { providerRegex(it, "filter") },
                excludedNames = provider.string("exclude-filter")?.split('`')
                    ?.map { providerRegex(it, "exclude-filter") }.orEmpty(),
                excludedTypes = provider.string("exclude-type")?.split('|')
                    ?.map(::canonicalType)?.toSet().orEmpty(),
                overrides = overrides,
                replacements = replacements,
            )
        }
    }
}

/** Expand regexp2 replacement tokens without applying JVM replacement escaping. */
private fun expandMihomoReplacement(template: String, input: String, match: MatchResult): String = buildString {
    fun groupValue(reference: String): String? {
        val number = reference.toIntOrNull()
        if (number != null) {
            return if (number in match.groups.indices) match.groups[number]?.value.orEmpty() else null
        }
        return runCatching { match.groups[reference]?.value.orEmpty() }.getOrNull()
    }

    var index = 0
    while (index < template.length) {
        if (template[index] != '$' || index + 1 == template.length) {
            append(template[index++])
            continue
        }
        val next = template[index + 1]
        val special = when (next) {
            '$' -> "$"
            '&' -> match.value
            '`' -> input.substring(0, match.range.first)
            '\'' -> input.substring(match.range.last + 1)
            '+' -> match.groups[match.groups.size - 1]?.value.orEmpty()
            '_' -> input
            else -> null
        }
        if (special != null) {
            append(special)
            index += 2
            continue
        }
        val end = when (next) {
            '{' -> template.indexOf('}', index + 2).takeIf { it >= 0 }?.plus(1)
            in '0'..'9' -> {
                var cursor = index + 2
                while (cursor < template.length && template[cursor] in '0'..'9') cursor++
                cursor
            }
            else -> null
        }
        if (end != null) {
            val reference = if (next == '{') template.substring(index + 2, end - 1) else template.substring(index + 1, end)
            // Unknown groups remain literal in regexp2 instead of aborting the provider.
            append(groupValue(reference) ?: template.substring(index, end))
            index = end
        } else {
            append(template[index++])
        }
    }
}

/** These explicit values match NG's existing structured outbound defaults. */
private fun MihomoYamlMap.isNeutralProviderOverride(name: String): Boolean = when (name) {
    "tfo", "mptcp", "udp-over-tcp" -> boolean(name) == false
    "udp" -> boolean(name) == true
    "ip-version" -> string(name)?.lowercase() == "dual"
    else -> false
}

internal fun List<MihomoYamlMap>.applyMihomoProviderOptions(options: List<MihomoProviderOptions>): List<MihomoYamlMap> =
    options.fold(this) { nodes, option -> option.apply(nodes) }

internal fun List<ProxyServer<*>>.applyMihomoProviderServerOptions(options: List<MihomoProviderOptions>): List<ProxyServer<*>> =
    options.fold(this) { nodes, option -> option.applyServers(nodes) }

internal fun String.withMihomoProviderUrlOverrides(options: List<MihomoProviderOptions>): String {
    if (options.none { it.enforcesCertificateVerification }) return this
    val scheme = substringBefore("://").lowercase()
    if (scheme != "hysteria2" && scheme != "hy2") return this
    // Keep HY2's authority intact: its standard URI can contain a port list/range,
    // which a generic URL parser rejects before the HY2 parser can normalize it.
    val fragmentIndex = indexOf('#').let { if (it < 0) length else it }
    val queryIndex = indexOf('?').takeIf { it in 0 until fragmentIndex } ?: return this
    val query = substring(queryIndex + 1, fragmentIndex).split('&').filterNot { entry ->
        val key = entry.substringBefore('=')
        runCatching { URLDecoder.decode(key, Charsets.UTF_8.name()) }.getOrDefault(key) == "insecure"
    }.joinToString("&")
    return substring(0, queryIndex) + (if (query.isEmpty()) "" else "?$query") + substring(fragmentIndex)
}

internal fun MihomoYamlMap.mihomoProviderHeaders(): Map<String, List<String>> {
    val header = map("header") ?: if (this["header"] == null) return emptyMap() else {
        providerUnsupported("provider header must be an object")
    }
    return header.mapValues { (name, value) ->
        if (!HttpHeaderName.matches(name)) providerUnsupported("invalid provider header name")
        val values = if (value is Iterable<*>) value.toList() else listOf(value)
        values.map { item ->
            val text = item.rawProviderString() ?: providerUnsupported("provider header values must be strings")
            if ('\r' in text || '\n' in text) providerUnsupported("invalid provider header value")
            text
        }
    }
}

private fun Any?.rawProviderString(): String? = when (this) {
    is String -> this
    is Number, is Boolean -> toString()
    else -> null
}

private fun canonicalType(value: String): String = when (val type = value.trim().lowercase()) {
    "shadowsocks" -> "ss"
    "socks", "socks4" -> "socks5"
    "hy2" -> "hysteria2"
    "wg" -> "wireguard"
    else -> type
}

private fun providerRegex(value: String, field: String): Regex = try {
    Regex(value)
} catch (_: IllegalArgumentException) {
    providerUnsupported("invalid provider $field regex")
}

private val HttpHeaderName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

internal class UnsupportedMihomoProviderException(message: String) : IllegalArgumentException(message)

private fun providerUnsupported(message: String): Nothing = throw UnsupportedMihomoProviderException(message)
