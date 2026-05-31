// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class Socks(
    var remarks: String = "",
    var server: String = "",
    var port: String = "443",
    var user: String? = null,
    var password: String? = null,
) : UrlProxyServer<Socks> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "Socks")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return OutboundObject(
            tag = tag,
            protocol = ProxyServerConstants.PROTOCOL_SOCKS,
            settings = buildJsonObject {
                put("address", server)
                put("port", port.toXrayPort())
                putIfNotBlank("user", user)
                if (!user.isNullOrBlank()) {
                    put("pass", password.orEmpty())
                }
            },
        )
    }

    override fun parse(url: Url): Socks {
        this.remarks = url.fragment
        this.server = url.host
        this.port = url.port.toString()
        url.user?.let { str ->
            val info = str.decodeProxyUrlBase64().decodeToString()
            val pos = info.indexOfFirst { it == ':' }
            if (pos > -1) {
                this.user = info.substring(0, pos)
                this.password = info.substring(pos + 1)
            }
        }
        return this
    }

    override fun getUrl(): String {
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_SOCKS)
            host = this@Socks.server
            this@Socks.port.toIntOrNull()?.let { port = it }
            if (!this@Socks.user.isNullOrBlank())
                user = "${this@Socks.user}:${this@Socks.password.orEmpty()}".encodeToByteArray().encodeProxyUrlBase64()
            fragment = this@Socks.remarks
        }.buildString()
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is Socks) {
            proxyServerTypeMismatch()
        }
        this.apply {
            remarks = other.remarks
            server = other.server
            port = other.port
            user = other.user
            password = other.password
        }
    }

    override fun check() {
        validateCommonServerFields(remarks, server, port)
        validateOptionalUserPassword(user, password)
    }
}
