// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import kotlinx.serialization.Serializable

@Serializable
data class ChainProxy(
    var remarks: String = "",
    var proxyServerIds: List<Int> = emptyList(),
) : ProxyServer<ChainProxy> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(remarks, "${proxyServerIds.size} hops", "Chain")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        throw UnsupportedOperationException("Chain proxies are converted by XrayConfigFactory")
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is ChainProxy) {
            proxyServerTypeMismatch()
        }
        remarks = other.remarks
        proxyServerIds = other.proxyServerIds
    }

    override fun check() {
        validateRemarks(remarks)
        if (proxyServerIds.size < 2) {
            proxyValidationError(ProxyServerValidationError.ChainProxyMemberCountInvalid)
        }
    }
}
