// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import utils.proxyUrlRemarks

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
            settings = userPasswordOutboundSettings(server, port, user, password),
        )
    }

    override fun parse(url: Url): Socks {
        this.remarks = url.proxyUrlRemarks()
        this.server = url.proxyUrlHost()
        this.port = url.port.toString()
        url.userPasswordOrNull()?.let { (user, password) ->
            this.user = user
            this.password = password
        }
        return this
    }

    override fun getUrl(): String =
        userPasswordProxyUrl(
            URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_SOCKS),
            server,
            port,
            user,
            password,
            remarks,
        )

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

    override fun validateBasic(): List<ProxyServerValidationIssue> = buildList {
        validateCommonServerFields(remarks, server, port)
    }

    override fun validateFull(): List<ProxyServerValidationIssue> = buildList {
        addAll(validateBasic())
        validateOptionalUserPassword(user, password)
    }
}
