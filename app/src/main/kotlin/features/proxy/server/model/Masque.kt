// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import utils.putIfNotBlank
import engine.network.isIpv4Address
import engine.network.isIpv6Address
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import utils.toCsvValues

/**
 * IETF MASQUE CONNECT-IP (RFC 9484) outbound of Xray, optionally against Cloudflare WARP.
 *
 * There is no share-link convention for MASQUE yet, so this is not a [UrlProxyServer]; nodes are
 * created in the editor or through a Custom Xray JSON config.
 */
@Serializable
data class Masque(
    var remarks: String = "",
    var server: String = "",
    var port: String = "443",
    var user: String = "",
    var pass: String = "",
    var host: String = "",
    var path: String = "",
    var remoteDNS: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fingerprint: String = "",
    var pinSHA256: String = "",
    var warpPrivateKey: String = "",
    var warpPublicKey: String = "",
    var warpAddress: String = "",
    var finalMask: String = "",
) : ProxyServer<Masque> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "MASQUE")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return OutboundObject(
            tag = tag,
            protocol = ProxyServerConstants.PROTOCOL_MASQUE,
            settings = buildJsonObject {
                put("address", server)
                put("port", port.toXrayPort())
                val dns = remoteDNS.toCsvValues()
                if (dns.isNotEmpty()) {
                    putJsonArray("remoteDNS") {
                        dns.forEach { add(it) }
                    }
                }
            },
            streamSettings = buildJsonObject {
                put("network", ProxyServerConstants.PROTOCOL_MASQUE)
                putJsonObject("masqueSettings") {
                    putIfNotBlank("host", host)
                    putIfNotBlank("path", path)
                    putIfNotBlank("user", user)
                    putIfNotBlank("pass", pass)
                    if (isWarp()) {
                        putJsonObject("warp") {
                            put("privateKey", warpPrivateKey.trim())
                            put("publicKey", warpPublicKey.trim())
                            putJsonArray("address") {
                                warpAddress.toCsvValues().forEach { add(it) }
                            }
                        }
                    }
                }
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    putIfNotBlank("serverName", sni)
                    val alpnValues = alpn.toCsvValues()
                    if (alpnValues.isNotEmpty()) {
                        putJsonArray("alpn") {
                            alpnValues.forEach { add(it) }
                        }
                    }
                    putIfNotBlank("fingerprint", fingerprint)
                    putIfNotBlank("pinnedPeerCertSha256", pinSHA256)
                }
                finalMask.toXrayJsonObjectOrNull("FinalMask")?.let { put("finalmask", it) }
            },
        )
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is Masque) {
            proxyServerTypeMismatch()
        }
        this.apply {
            remarks = other.remarks
            server = other.server
            port = other.port
            user = other.user
            pass = other.pass
            host = other.host
            path = other.path
            remoteDNS = other.remoteDNS
            sni = other.sni
            alpn = other.alpn
            fingerprint = other.fingerprint
            pinSHA256 = other.pinSHA256
            warpPrivateKey = other.warpPrivateKey
            warpPublicKey = other.warpPublicKey
            warpAddress = other.warpAddress
            finalMask = other.finalMask
        }
    }

    override fun validateBasic(): List<ProxyServerValidationIssue> = buildList {
        validateCommonServerFields(remarks, server, port)
        if (isWarp()) {
            validateRequired(warpPrivateKey, "WARP PrivateKey")
            validateRequired(warpPublicKey, "WARP PublicKey")
            validateRequired(warpAddress, "WARP address")
        }
    }

    override fun validateFull(): List<ProxyServerValidationIssue> = buildList {
        addAll(validateBasic())
        if (isWarp() && (user.isNotBlank() || pass.isNotBlank())) {
            addIssue(ProxyServerValidationError.MasqueWarpCredentialsConflict)
        }
        if (user.contains(':')) {
            addIssue(ProxyServerValidationError.UnsupportedValue, user)
        }
        validateOptionalPath(path, "path")
        validateAllowed(alpn, "ALPN", MasqueAlpnOptions.toSet() - "", allowBlank = true)
        validateOptionalFingerprint(fingerprint)
        validateOptionalSha256(pinSHA256, "certificate fingerprint")
        if (!remoteDNS.toCsvValues().all(::isStrictRemoteDnsAddress)) {
            addIssue(ProxyServerValidationError.MasqueRemoteDnsInvalid)
        }
        if (isWarp()) {
            validateMasqueWarpAddresses(warpAddress)
        }
        validateOptionalJsonObject(finalMask, "FinalMask")
    }

    fun isWarp(): Boolean {
        return warpPrivateKey.isNotBlank() || warpPublicKey.isNotBlank() || warpAddress.isNotBlank()
    }

    /** Xray picks HTTP/2 only when the ALPN lists h2 without h3; otherwise MASQUE runs over HTTP/3. */
    fun usesHttp2(): Boolean {
        val values = alpn.toCsvValues().map(String::lowercase)
        return "h2" in values && "h3" !in values
    }
}

internal val MasqueAlpnOptions = listOf("", "h3", "h2")

// Xray takes at most one IPv4 and one IPv6 device address for WARP.
private fun MutableList<ProxyServerValidationIssue>.validateMasqueWarpAddresses(addresses: String) {
    validateWireguardAddresses(addresses)
    val hosts = addresses.toCsvValues().map { it.substringBefore('/') }
    if (hosts.count(::isIpv4Address) > 1 || hosts.count(::isIpv6Address) > 1) {
        addIssue(ProxyServerValidationError.MasqueWarpAddressFamilyDuplicate)
    }
}
