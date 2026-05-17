package features.proxy.server.model

import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.Url
import io.ktor.http.parsing.ParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

object ProxyServerConstants {
    const val PROTOCOL_HTTP = "http"
    const val PROTOCOL_SOCKS = "socks"
    const val PROTOCOL_SS = "ss"
    const val PROTOCOL_VMESS = "vmess"
    const val PROTOCOL_VLESS = "vless"
    const val PROTOCOL_TROJAN = "trojan"
    const val PROTOCOL_HYSTERIA2 = "hysteria2"
    const val PROTOCOL_HY2 = "hy2"
    const val PROTOCOL_WIREGUARD = "wireguard"
    const val PROTOCOL_STRATEGY_GROUP = "strategy-group"
    const val PROTOCOL_CHAIN_PROXY = "chain-proxy"
}

@Serializable
data class ProxyServerInfo(
    val remarks: String,
    val address: String,
    val protocol: String,
)

interface ProxyServer<T : ProxyServer<T>> {
    companion object {
        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun parse(str: String): ProxyServer<*> {
            val url = Url(str)
            val server = when (url.protocol.name) {
                ProxyServerConstants.PROTOCOL_HTTP -> HTTP().parse(url)
                ProxyServerConstants.PROTOCOL_SOCKS -> Socks().parse(url)
                ProxyServerConstants.PROTOCOL_SS -> Shadowsocks().parse(url)
                ProxyServerConstants.PROTOCOL_VMESS -> {
                    if (url.user == null) {
                        val originJson = base64.decode(url.host).decodeToString()
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
            server.check()
            return server
        }
    }

    fun getInfo(): ProxyServerInfo
    fun toXrayOutbound(tag: String): OutboundObject
    fun update(other: ProxyServer<*>)
    fun check()
}

interface UrlProxyServer<T : UrlProxyServer<T>> : ProxyServer<T> {
    fun parse(url: Url): T
    fun getUrl(): String
}

fun ProxyServer<*>.isCompositeProxyServer(): Boolean {
    return this is StrategyGroup || this is ChainProxy
}

fun ProxyServer<*>.supportsUrl(): Boolean {
    return this is UrlProxyServer<*>
}

fun ProxyServer<*>.getUrlOrNull(): String? {
    return (this as? UrlProxyServer<*>)?.getUrl()
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
    var type: String = "tcp",
    var security: String = "none",
    var path: String? = null,
    var host: String? = null,
    var mtu: String? = null,
    var tti: String? = null,
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
        this.type = url.parameters["type"] ?: defaultType
        this.security = url.parameters["security"] ?: defaultSecurity
        this.fm = url.parameters["fm"]
        when (this.type) {
            "tcp", "raw" -> {
                this.type = "raw"
                this.headerType = url.parameters["headerType"] ?: "none"
                this.host = url.parameters["host"]
            }

            "kcp", "mkcp" -> {
                this.type = "mkcp"
                this.mtu = url.parameters["mtu"]
                this.tti = url.parameters["tti"]
            }

            "ws", "websocket" -> {
                this.type = "websocket"
                this.path = url.parameters["path"]
                this.host = url.parameters["host"]
            }

            "httpupgrade" -> {
                this.path = url.parameters["path"]
                this.host = url.parameters["host"]
            }

            "grpc" -> {
                this.mode = url.parameters["mode"] ?: "gun"
                this.authority = url.parameters["authority"]
                this.serviceName = url.parameters["serviceName"]
            }

            "xhttp", "splithttp" -> {
                this.type = "xhttp"
                this.path = url.parameters["path"]
                this.host = url.parameters["host"]
                this.mode = url.parameters["mode"]
                this.extra = url.parameters["extra"]
            }

            "http", "h2", "h3" -> throw ParseException("Unsupported xray transport type: ${this.type}")
            else -> throw ParseException("Unknown v2ray transport type: ${this.type}")
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

            else -> throw ParseException("Unknown v2ray security type: ${this.security}")
        }
        return this
    }

    fun get(): Parameters {
        return ParametersBuilder().apply {
            appendIfNotBlank("type", this@V2RayParameters.type)
            appendIfNotBlank("security", this@V2RayParameters.security)
            appendIfNotBlank("fm", this@V2RayParameters.fm)
            when (type) {
                "tcp", "raw" -> {
                    appendIfNotBlank("headerType", this@V2RayParameters.headerType)
                    appendIfNotBlank("host", this@V2RayParameters.host)
                }

                "kcp", "mkcp" -> {
                    appendIfNotBlank("mtu", this@V2RayParameters.mtu)
                    appendIfNotBlank("tti", this@V2RayParameters.tti)
                }

                "ws", "websocket", "httpupgrade" -> {
                    appendIfNotBlank("path", this@V2RayParameters.path)
                    appendIfNotBlank("host", this@V2RayParameters.host)
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
            when (security) {
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
