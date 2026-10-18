// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.parsing.ParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import utils.decodeFlexibleBase64OrNull
import utils.encodeUrlSafeBase64OptionalPadding
import utils.toProxyUrlRemarks

object ProxyServerConstants {
    const val PROTOCOL_HTTP = "http"
    const val PROTOCOL_SOCKS = "socks"
    const val PROTOCOL_SOCKS4 = "socks4"
    const val PROTOCOL_SOCKS5 = "socks5"
    const val PROTOCOL_SS = "ss"
    const val PROTOCOL_VMESS = "vmess"
    const val PROTOCOL_VLESS = "vless"
    const val PROTOCOL_TROJAN = "trojan"
    const val PROTOCOL_HYSTERIA2 = "hysteria2"
    const val PROTOCOL_HY2 = "hy2"
    const val PROTOCOL_WIREGUARD = "wireguard"
    const val PROTOCOL_MASQUE = "masque"
    const val PROTOCOL_STRATEGY_GROUP = "strategy-group"
    const val PROTOCOL_CHAIN_PROXY = "chain-proxy"
    const val PROTOCOL_CUSTOM = "custom"
}

@Serializable
data class ProxyServerInfo(
    val remarks: String,
    val address: String,
    val protocol: String,
)

interface ProxyServer<T : ProxyServer<T>> {
    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun parse(str: String): ProxyServer<*> {
            val value = str.trim()
            parseLegacyProxyServerUrl(value)?.let { server ->
                return server
            }

            val url = Url(value)
            val server = when (url.protocol.name) {
                ProxyServerConstants.PROTOCOL_HTTP -> HTTP().parse(url)
                ProxyServerConstants.PROTOCOL_SOCKS,
                ProxyServerConstants.PROTOCOL_SOCKS4,
                ProxyServerConstants.PROTOCOL_SOCKS5 -> Socks().parse(url)
                ProxyServerConstants.PROTOCOL_SS -> Shadowsocks().parse(url)
                ProxyServerConstants.PROTOCOL_VMESS -> {
                    if (url.user == null) {
                        val originJson = url.host.decodeProxyUrlBase64().decodeToString()
                        json.decodeFromString<LegacyVMess>(originJson).convertToAEAD()
                    } else {
                        VMess().parse(url)
                    }
                }

                ProxyServerConstants.PROTOCOL_VLESS -> VLESS().parse(url)
                ProxyServerConstants.PROTOCOL_TROJAN -> Trojan().parse(url)
                ProxyServerConstants.PROTOCOL_HY2, ProxyServerConstants.PROTOCOL_HYSTERIA2 -> Hysteria2().parse(url)
                ProxyServerConstants.PROTOCOL_WIREGUARD -> Wireguard().parse(url)
                else -> {
                    unsupportedProxyServerUrl(url)
                }
            }
            return server
        }

        private fun parseLegacyProxyServerUrl(value: String): ProxyServer<*>? {
            val scheme = value.substringBefore("://", missingDelimiterValue = "")
            val payload = value.substringAfter("://", missingDelimiterValue = "")
            val body = payload.substringBefore("#")
            if (body.isBlank()) return null
            return when {
                scheme.equals(ProxyServerConstants.PROTOCOL_VMESS, ignoreCase = true) &&
                    !body.contains('@') &&
                    !body.contains('?') -> {
                    val originJson = body.decodeProxyUrlBase64().decodeToString()
                    json.decodeFromString<LegacyVMess>(originJson).convertToAEAD()
                }

                scheme.equals(ProxyServerConstants.PROTOCOL_SS, ignoreCase = true) &&
                    !body.contains('@') -> {
                    val remarks = payload.substringAfter("#", missingDelimiterValue = "").toProxyUrlRemarks()
                    val decoded = body.decodeProxyUrlBase64().decodeToString()
                    Shadowsocks().parseLegacy(decoded, remarks)
                }

                else -> null
            }
        }
    }

    fun getInfo(): ProxyServerInfo
    fun toXrayOutbound(tag: String): OutboundObject
    fun update(other: ProxyServer<*>)
    fun validateBasic(): List<ProxyServerValidationIssue>
    fun validateFull(): List<ProxyServerValidationIssue>
}

internal fun String.decodeProxyUrlBase64(): ByteArray {
    return decodeFlexibleBase64OrNull()
        ?: throw IllegalArgumentException("Bad proxy URL base64")
}

internal fun ByteArray.encodeProxyUrlBase64(): String {
    return encodeUrlSafeBase64OptionalPadding()
}

interface UrlProxyServer<T : UrlProxyServer<T>> : ProxyServer<T> {
    fun parse(url: Url): T
    fun getUrl(): String
}

fun ProxyServer<*>.isCompositeProxyServer(): Boolean {
    return this is StrategyGroup || this is ChainProxy
}

fun ProxyServer<*>.isCustomProxyServer(): Boolean {
    return this is Custom
}

fun ProxyServer<*>.getUrlOrNull(): String? {
    return (this as? UrlProxyServer<*>)?.getUrl()
}

fun ProxyServer<*>.getCopyTextOrNull(): String? {
    return when (this) {
        is Custom -> configJson
        is UrlProxyServer<*> -> getUrl()
        else -> null
    }
}

internal fun URLBuilder.setProxyUrlHost(value: String) {
    val normalized = value.toProxyUrlHost()
    host = if (normalized.contains(':')) "[$normalized]" else normalized
}

internal fun Url.proxyUrlHost(): String {
    return host.toProxyUrlHost()
}

private fun String.toProxyUrlHost(): String {
    return trim().removeSurrounding("[", "]")
}

internal fun proxyServerTypeMismatch(): Nothing {
    throw IllegalArgumentException("Proxy server type mismatch")
}

internal fun unsupportedProxyServerUrl(url: Url): Nothing {
    throw ParseException("Unsupported ProxyServer url protocol: ${url.protocol.name}")
}

@Serializable
data class V2RayParameters(
    // standard
    var type: String = "raw",
    var security: String = "none",
    var path: String? = null,
    var host: String? = null,
    var headers: String? = null,
    var mtu: String? = null,
    var tti: String? = null,
    var seed: String? = null,
    var serviceName: String? = null,
    var mode: String? = null,
    var authority: String? = null,
    var extra: String? = null,
    var fm: String? = null,
    var fp: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var ech: String? = null,
    var pcs: String? = null,
    var vcn: String? = null,
    var pbk: String? = null,
    var sid: String? = null,
    var pqv: String? = null,
    var spx: String? = null,
    // v2rayN
    var headerType: String? = "none",
) {
    fun parse(url: Url, defaultType: String, defaultSecurity: String): V2RayParameters {
        this.type = url.parameters["type"].toCanonicalV2RayTransportType(defaultType)
        this.security = url.parameters["security"] ?: defaultSecurity
        this.fm = url.parameters["fm"]
        when (this.type) {
            V2RayTransportRaw -> {
                this.headerType = url.parameters["headerType"] ?: "none"
                this.host = url.parameters["host"]
            }

            V2RayTransportMkcp -> {
                this.headerType = url.parameters["headerType"] ?: "none"
                this.host = url.parameters["host"]
                this.mtu = url.parameters["mtu"]
                this.tti = url.parameters["tti"]
                this.seed = url.parameters["seed"]
            }

            V2RayTransportWebSocket -> {
                this.path = url.parameters["path"].appendWebSocketEarlyData(
                    ed = url.parameters["ed"],
                    eh = url.parameters["eh"],
                )
                this.host = url.parameters["host"]
                this.headers = url.parameters["headers"]
            }

            V2RayTransportHttpUpgrade -> {
                this.path = url.parameters["path"]
                this.host = url.parameters["host"]
                this.headers = url.parameters["headers"]
            }

            V2RayTransportGrpc -> {
                this.mode = url.parameters["mode"] ?: "gun"
                this.authority = url.parameters["authority"]
                this.serviceName = url.parameters["serviceName"]
            }

            V2RayTransportXhttp -> {
                this.path = url.parameters["path"]
                this.host = url.parameters["host"]
                this.mode = url.parameters["mode"]
                this.extra = url.parameters["extra"]
            }

            "http", "h2", "h3" -> {}
            else -> {}
        }
        when (this.security) {
            "none" -> {}
            "tls" -> {
                this.fp = url.parameters["fp"]
                this.sni = url.parameters["sni"]
                this.alpn = url.parameters["alpn"]
                this.ech = url.parameters["ech"]
                this.pcs = url.parameters["pcs"]
                this.vcn = url.parameters["vcn"]
            }

            "reality" -> {
                this.fp = url.parameters["fp"] ?: "chrome"
                this.sni = url.parameters["sni"]
                this.pbk = url.parameters["pbk"]
                this.sid = url.parameters["sid"]
                this.pqv = url.parameters["pqv"]
                this.spx = url.parameters["spx"]
            }

            else -> {}
        }
        return this
    }

    fun get(): Parameters {
        val transportType = type.toV2RayTransportUrlType()
        val securityType = security.ifBlank { "none" }
        return ParametersBuilder().apply {
            appendIfNotBlank("type", transportType)
            appendIfNotBlank("security", securityType)
            appendIfNotBlank("fm", this@V2RayParameters.fm)
            when (transportType) {
                "raw" -> {
                    appendIfNotBlank("headerType", this@V2RayParameters.headerType)
                    appendIfNotBlank("host", this@V2RayParameters.host)
                }

                "kcp", "mkcp" -> {
                    appendIfNotBlank("headerType", this@V2RayParameters.headerType)
                    appendIfNotBlank("host", this@V2RayParameters.host)
                    appendIfNotBlank("mtu", this@V2RayParameters.mtu)
                    appendIfNotBlank("tti", this@V2RayParameters.tti)
                    appendIfNotBlank("seed", this@V2RayParameters.seed)
                }

                "ws", "websocket", "httpupgrade" -> {
                    appendIfNotBlank("path", this@V2RayParameters.path)
                    appendIfNotBlank("host", this@V2RayParameters.host)
                    appendIfNotBlank("headers", this@V2RayParameters.headers)
                }

                "grpc" -> {
                    appendIfNotBlank("mode", this@V2RayParameters.mode)
                    appendIfNotBlank("authority", this@V2RayParameters.authority)
                    appendIfNotBlank("serviceName", this@V2RayParameters.serviceName)
                }

                "xhttp", "splithttp" -> {
                    appendIfNotBlank("path", this@V2RayParameters.path)
                    appendIfNotBlank("host", this@V2RayParameters.host)
                    appendIfNotBlank("mode", this@V2RayParameters.mode)
                    appendIfNotBlank("extra", this@V2RayParameters.extra)
                }

                else -> throw ParseException("Unknown v2ray transport type: $type")
            }
            when (securityType) {
                "none" -> {}
                "tls" -> {
                    appendIfNotBlank("fp", this@V2RayParameters.fp)
                    appendIfNotBlank("sni", this@V2RayParameters.sni)
                    appendIfNotBlank("alpn", this@V2RayParameters.alpn)
                    appendIfNotBlank("ech", this@V2RayParameters.ech)
                    appendIfNotBlank("pcs", this@V2RayParameters.pcs)
                    appendIfNotBlank("vcn", this@V2RayParameters.vcn)
                }

                "reality" -> {
                    appendIfNotBlank("fp", this@V2RayParameters.fp)
                    appendIfNotBlank("sni", this@V2RayParameters.sni)
                    appendIfNotBlank("pbk", this@V2RayParameters.pbk)
                    appendIfNotBlank("sid", this@V2RayParameters.sid)
                    appendIfNotBlank("pqv", this@V2RayParameters.pqv)
                    appendIfNotBlank("spx", this@V2RayParameters.spx)
                }

                else -> throw ParseException("Unknown v2ray security type: $security")
            }
        }.build()
    }
}

private fun ParametersBuilder.appendIfNotBlank(name: String, value: String?) {
    value?.takeIf(String::isNotBlank)?.let { append(name, it) }
}

private fun String?.appendWebSocketEarlyData(ed: String?, eh: String?): String? {
    val rawEarlyData = ed?.trim()?.takeIf(String::isNotBlank) ?: return this
    val earlyData = rawEarlyData.toIntOrNull()
        ?: throw IllegalArgumentException("WebSocket early data must be integer")
    if (earlyData <= 0) return this
    if (!eh.isNullOrBlank() && !eh.equals(WebSocketEarlyDataHeader, ignoreCase = true)) {
        throw IllegalArgumentException("WebSocket early data header is not supported: $eh")
    }
    val path = this?.takeIf(String::isNotBlank) ?: "/"
    val separator = if ('?' in path) "&" else "?"
    return "$path${separator}ed=$earlyData"
}

private const val WebSocketEarlyDataHeader = "Sec-WebSocket-Protocol"
