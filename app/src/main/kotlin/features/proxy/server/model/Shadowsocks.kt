package features.proxy.server.model

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

@Serializable
data class Shadowsocks(
    var remarks: String = "",
    var server: String = "",
    var port: String = "443",
    var method: String = "aes-256-gcm",
    var password: String = "",
    var plugin: String? = null,
    var pluginOpt: String? = null,
) : UrlProxyServer<Shadowsocks> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "Shadowsocks")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return OutboundObject(
            tag = tag,
            protocol = "shadowsocks",
            settings = buildJsonObject {
                put("address", server)
                put("port", port.toXrayPort())
                put("method", method)
                put("password", method.toXrayShadowsocksPassword(password))
            },
        )
    }

    override fun parse(url: Url): Shadowsocks {
        this.remarks = url.fragment
        this.server = url.host
        this.port = url.port.toString()
        if (url.port == 0) {
            val full =
                ProxyServer.base64.decode(url.host).decodeToString()
            val infoAndServer = full.split('@')
            if (infoAndServer.size == 2) {
                val methodAndPassword = infoAndServer[0].split(':')
                if (methodAndPassword.size == 2) {
                    this.method = methodAndPassword[0]
                    this.password = methodAndPassword[1]
                }
                val addressAndPort = infoAndServer[1].split(':')
                if (addressAndPort.size == 2) {
                    this.server = addressAndPort[0]
                    this.port = addressAndPort[1]
                }
            }
        } else {
            val info = url.user?.let {
                ProxyServer.base64.decode(it).decodeToString()
            } ?: throw IllegalArgumentException("Bad Shadowsocks url")
            val pos = info.indexOfFirst { it == ':' }
            if (pos > -1) {
                this.method = info.substring(0, pos)
                this.password = info.substring(pos + 1)
            }
            //parse Shadowsocks SIP003 plugin
            url.parameters["plugin"]?.let {
                val split = it.split(';')
                this.plugin = split[0]
                if (split.size > 1) {
                    this.pluginOpt = it.substring(split[0].length + 1)
                }
            }
        }
        return this
    }

    override fun getUrl(): String {
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_SS)
            host = this@Shadowsocks.server
            this@Shadowsocks.port.toIntOrNull()?.let { port = it }
            user = ProxyServer.base64
                .encode("${this@Shadowsocks.method}:${this@Shadowsocks.password}".toByteArray())
            if (!plugin.isNullOrBlank()) parameters.append(
                "plugin",
                "${this@Shadowsocks.plugin};${this@Shadowsocks.pluginOpt}"
            )
            fragment = this@Shadowsocks.remarks
        }.build().toString()
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is Shadowsocks) {
            proxyServerTypeMismatch()
        }
        this.apply {
            remarks = other.remarks
            server = other.server
            port = other.port
            method = other.method
            password = other.password
            plugin = other.plugin
            pluginOpt = other.pluginOpt
        }
    }

    override fun check() {
        validateCommonServerFields(remarks, server, port)
        validateAllowed(
            method,
            "encryption method",
            setOf(
                "aes-256-gcm",
                "aes-128-gcm",
                "chacha20-poly1305",
                "chacha20-ietf-poly1305",
                "xchacha20-poly1305",
                "xchacha20-ietf-poly1305",
                "none",
                "plain",
                "2022-blake3-aes-128-gcm",
                "2022-blake3-aes-256-gcm",
                "2022-blake3-chacha20-poly1305",
            ),
        )
        validateRequired(password, "password")
        method.toXrayShadowsocksPassword(password)
        if (plugin.isNullOrBlank() && !pluginOpt.isNullOrBlank()) {
            proxyValidationError(ProxyServerValidationError.PluginNameRequired)
        }
    }
}

private fun String.toXrayShadowsocksPassword(password: String): String {
    if (!isShadowsocks2022Method()) {
        return password
    }
    return password
        .split(':')
        .joinToString(":") { key -> normalizeShadowsocks2022Key(key, shadowsocks2022KeyLengths()) }
}

private fun String.isShadowsocks2022Method(): Boolean {
    return this in Shadowsocks2022KeyLengths
}

private fun String.shadowsocks2022KeyLengths(): Set<Int> {
    return Shadowsocks2022KeyLengths[this].orEmpty()
}

private fun normalizeShadowsocks2022Key(key: String, validLengths: Set<Int>): String {
    val trimmed = key.trim()
    val decodedKey = decodeShadowsocks2022Key(trimmed)
        ?: proxyValidationError(ProxyServerValidationError.Shadowsocks2022KeyBase64Invalid)
    if (decodedKey.size !in validLengths) {
        proxyValidationError(
            ProxyServerValidationError.Shadowsocks2022KeyLengthInvalid,
            validLengths.joinToString(" or "),
        )
    }
    return Base64.Default.encode(decodedKey)
}

private fun decodeShadowsocks2022Key(key: String): ByteArray? {
    if (key.isBlank()) {
        return null
    }
    return Shadowsocks2022KeyDecoders.firstNotNullOfOrNull { decoder ->
        runCatching { decoder.decode(key) }.getOrNull()
    }
}

private val Shadowsocks2022KeyLengths = mapOf(
    "2022-blake3-aes-128-gcm" to setOf(16, 32),
    "2022-blake3-aes-256-gcm" to setOf(32),
    "2022-blake3-chacha20-poly1305" to setOf(32),
)

private val Shadowsocks2022KeyDecoders = listOf(
    Base64.Default,
    Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
    Base64.UrlSafe,
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
)
