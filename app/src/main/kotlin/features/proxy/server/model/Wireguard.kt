// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import engine.network.isIpv4Address
import engine.network.isIpv6Address
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import utils.toCsvValues
import utils.proxyUrlRemarks

@Serializable
data class Wireguard(
    var remarks: String = "",
    var server: String = "",
    var port: String = "443",
    var secretKey: String = "",
    var publicKey: String = "",
    var preSharedKey: String = "",
    var reserved: String = "0,0,0",
    var address: String = "172.16.0.2/32",
    var mtu: String = "1420",
    var finalMask: String = "",
    var remoteDNS: String = "",
) : UrlProxyServer<Wireguard> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "Wireguard")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return OutboundObject(
            tag = tag,
            protocol = ProxyServerConstants.PROTOCOL_WIREGUARD,
            settings = buildJsonObject {
                put("secretKey", secretKey)
                putJsonArray("remoteDNS") {
                    remoteDnsAddresses().forEach { add(it) }
                }
                val addresses = address.toCsvValues()
                if (addresses.isNotEmpty()) {
                    putJsonArray("address") {
                        addresses.forEach { add(it) }
                    }
                }
                putJsonArray("peers") {
                    add(
                        buildJsonObject {
                            put("endpoint", toWireguardEndpoint())
                            put("publicKey", publicKey)
                            putIfNotBlank("preSharedKey", preSharedKey)
                        },
                    )
                }
                mtu.toIntOrNull()?.let { put("mtu", it) }
                val reservedBytes = reserved.toCsvValues()
                    .mapNotNull(String::toIntOrNull)
                if (reservedBytes.isNotEmpty()) {
                    putJsonArray("reserved") {
                        reservedBytes.forEach { add(it) }
                    }
                }
            },
            streamSettings = finalMask.toXrayJsonObjectOrNull("FinalMask")?.let { parsedFinalMask ->
                buildJsonObject {
                    put("finalmask", parsedFinalMask)
                }
            },
        )
    }

    override fun parse(url: Url): Wireguard {
        this.remarks = url.proxyUrlRemarks()
        this.server = url.proxyUrlHost()
        this.port = url.port.toString()
        this.secretKey = url.user ?: ""
        this.publicKey = url.parameters["publickey"] ?: ""
        this.preSharedKey = url.parameters["presharedkey"] ?: ""
        this.reserved = url.parameters["reserved"] ?: "0,0,0"
        this.address = url.parameters["address"] ?: "172.16.0.2/32"
        this.mtu = url.parameters["mtu"] ?: "1420"
        this.remoteDNS = url.parameters["dns"].orEmpty()
        this.finalMask = (url.parameters["fm"] ?: url.parameters["finalmask"] ?: url.parameters["finalMask"])
            ?.takeIf { it.isNotBlank() }.orEmpty()
        return this
    }

    override fun getUrl(): String {
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_WIREGUARD)
            setProxyUrlHost(this@Wireguard.server)
            this@Wireguard.port.toIntOrNull()?.let { port = it }
            user = this@Wireguard.secretKey

            if (this@Wireguard.publicKey.isNotBlank()) {
                parameters.append("publickey", this@Wireguard.publicKey)
            }
            if (this@Wireguard.preSharedKey.isNotBlank()) {
                parameters.append("presharedkey", this@Wireguard.preSharedKey)
            }
            if (this@Wireguard.reserved.isNotBlank()) {
                parameters.append("reserved", this@Wireguard.reserved)
            }
            if (this@Wireguard.address.isNotBlank()) {
                parameters.append("address", this@Wireguard.address)
            }
            parameters.append("mtu", this@Wireguard.mtu)
            if (this@Wireguard.remoteDNS.isNotBlank()) {
                parameters.append("dns", this@Wireguard.remoteDNS.toCsvValues().joinToString(","))
            }
            if (this@Wireguard.finalMask.isNotBlank()) {
                parameters.append("fm", this@Wireguard.finalMask)
            }

            fragment = this@Wireguard.remarks
        }.buildString()
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is Wireguard) {
            proxyServerTypeMismatch()
        }
        this.apply {
            remarks = other.remarks
            server = other.server
            port = other.port
            secretKey = other.secretKey
            publicKey = other.publicKey
            preSharedKey = other.preSharedKey
            reserved = other.reserved
            address = other.address
            mtu = other.mtu
            finalMask = other.finalMask
            remoteDNS = other.remoteDNS
        }
    }

    override fun validateBasic(): List<ProxyServerValidationIssue> = buildList {
        validateCommonServerFields(remarks, server, port)
        validateRequired(secretKey, "SecretKey")
        validateRequired(publicKey, "PublicKey")
    }

    override fun validateFull(): List<ProxyServerValidationIssue> = buildList {
        addAll(validateBasic())
        validateWireguardKey(secretKey, "SecretKey", required = false)
        validateWireguardKey(publicKey, "PublicKey", required = false)
        validateWireguardKey(preSharedKey, "PreSharedKey", required = false)
        validateWireguardReserved(reserved)
        validateWireguardAddresses(address)
        validateMtu(mtu)
        if (!remoteDNS.toCsvValues().isValidRemoteDns()) {
            add(proxyValidationIssue(ProxyServerValidationError.WireguardRemoteDnsInvalid))
        }
        validateOptionalJsonObject(finalMask, "FinalMask")
    }

    fun remoteDnsAddresses(enableIpv6: Boolean = true): List<String> {
        val defaults = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        val addresses = remoteDNS.toCsvValues().ifEmpty { defaults }
        // Xray uses MustParseAddr here; reject malformed input before handing it to the core.
        require(addresses.isValidRemoteDns()) { "WireGuard remote DNS must contain IP addresses or only local" }
        if (addresses == listOf("local") || enableIpv6) return addresses
        return addresses.filterNot { ':' in it }
            .ifEmpty { defaults.filterNot { ':' in it } }
    }

    private fun toWireguardEndpoint(): String {
        val host = if (server.contains(':') && !server.startsWith('[')) {
            "[$server]"
        } else {
            server
        }
        return "$host:$port"
    }
}

private fun List<String>.isValidRemoteDns(): Boolean = this == listOf("local") || all { address ->
    when {
        ':' !in address -> address.isStrictDnsIpv4()
        '.' !in address -> isIpv6Address(address)
        else -> address.substringAfterLast(':').isStrictDnsIpv4() &&
            isIpv6Address(address.substringBeforeLast(':') + ":0:0")
    }
}

private fun String.isStrictDnsIpv4(): Boolean = isIpv4Address(this) && split('.').all { octet ->
    octet.all { it in '0'..'9' } && (octet.length == 1 || !octet.startsWith('0'))
}
