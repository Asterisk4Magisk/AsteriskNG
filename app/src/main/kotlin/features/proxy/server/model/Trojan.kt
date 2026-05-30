// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.parsing.ParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class Trojan(
    var remarks: String = "",
    var password: String = "",
    var server: String = "",
    var port: String = "443",
    var parms: V2RayParameters = V2RayParameters(),
) : UrlProxyServer<Trojan> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "Trojan")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return OutboundObject(
            tag = tag,
            protocol = ProxyServerConstants.PROTOCOL_TROJAN,
            settings = buildJsonObject {
                put("address", server)
                put("port", port.toXrayPort())
                put("password", password)
            },
            streamSettings = parms.toXrayStreamSettings(),
        )
    }

    override fun parse(url: Url): Trojan {
        this.remarks = url.fragment
        this.password = url.user ?: throw ParseException("Invalid Trojan url")
        this.server = url.host
        this.port = url.port.toString()
        this.parms = this.parms.parse(url, "raw", "none")
        return this
    }

    override fun getUrl(): String {
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_TROJAN)
            host = this@Trojan.server
            this@Trojan.port.toIntOrNull()?.let { port = it }
            user = this@Trojan.password
            this@Trojan.parms.let {
                parameters.appendAll(it.get())
            }
            fragment = this@Trojan.remarks
        }.buildString()
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is Trojan) {
            proxyServerTypeMismatch()
        }
        this.apply {
            this.remarks = other.remarks
            this.password = other.password
            this.server = other.server
            this.port = other.port
            this.parms = other.parms.copy()
        }
    }

    override fun check() {
        validateCommonServerFields(remarks, server, port)
        validateRequired(password, "password")
        validateV2RayParameters(parms)
    }
}
