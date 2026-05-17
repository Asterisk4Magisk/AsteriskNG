package features.proxy.server.model

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
                val addresses = address.split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
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
                val reservedBytes = reserved.split(',')
                    .map(String::trim)
                    .mapNotNull(String::toIntOrNull)
                if (reservedBytes.isNotEmpty()) {
                    putJsonArray("reserved") {
                        reservedBytes.forEach { add(it) }
                    }
                }
            },
        )
    }

    override fun parse(url: Url): Wireguard {
        this.remarks = url.fragment
        this.server = url.host
        this.port = url.port.toString()
        this.secretKey = url.user ?: ""
        this.publicKey = url.parameters["publickey"] ?: ""
        this.preSharedKey = url.parameters["presharedkey"] ?: ""
        this.reserved = url.parameters["reserved"] ?: "0,0,0"
        this.address = url.parameters["address"] ?: "172.16.0.2/32"
        this.mtu = url.parameters["mtu"] ?: ""
        return this
    }

    override fun getUrl(): String {
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_WIREGUARD)
            host = this@Wireguard.server
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
        }
    }

    override fun check() {
        validateCommonServerFields(remarks, server, port)
        validateWireguardKey(secretKey, "SecretKey")
        validateWireguardKey(publicKey, "PublicKey")
        validateWireguardKey(preSharedKey, "PreSharedKey", required = false)
        validateWireguardReserved(reserved)
        validateWireguardAddresses(address)
        validateMtu(mtu)
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
