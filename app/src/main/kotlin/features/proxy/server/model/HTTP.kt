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
data class HTTP(
    var remarks: String = "",
    var server: String = "",
    var port: String = "443",
    var user: String? = null,
    var password: String? = null,
) : UrlProxyServer<HTTP> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "HTTP")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return OutboundObject(
            tag = tag,
            protocol = ProxyServerConstants.PROTOCOL_HTTP,
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

    override fun parse(url: Url): HTTP {
        this.remarks = url.fragment
        this.server = url.host
        this.port = url.port.toString()
        url.user?.let { str ->
            val info = ProxyServer.base64.decode(str).decodeToString()
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
            protocol = URLProtocol.HTTP
            host = this@HTTP.server
            this@HTTP.port.toIntOrNull()?.let { port = it }
            if (!this@HTTP.user.isNullOrBlank())
                user = ProxyServer.base64.encode("${this@HTTP.user}:${this@HTTP.password.orEmpty()}".toByteArray())
            fragment = this@HTTP.remarks
        }.buildString()
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is HTTP) {
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
