// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import utils.putIfNotBlank
import engine.network.isIpv4Address
import engine.network.isIpv6Address
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import utils.encodeBase64
import utils.proxyUrlRemarks
import utils.toCsvValues

/**
 * IETF MASQUE CONNECT-IP (RFC 9484) outbound of Xray, optionally against Cloudflare WARP.
 *
 * Share links follow v2rayNG: user info carries the WARP private key and authentication uses headers.
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
    var headers: String = "",
    var remoteDNS: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fingerprint: String = "",
    var pinSHA256: String = "",
    var verifyPeerCertByName: String = "",
    var warpPrivateKey: String = "",
    var warpPublicKey: String = "",
    var warpAddress: String = "",
    var finalMask: String = "",
) : UrlProxyServer<Masque> {
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
                    requestHeaders()?.let { put("headers", it) }
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
                    putIfNotBlank("verifyPeerCertByName", verifyPeerCertByName)
                }
                finalMask.toXrayJsonObjectOrNull("FinalMask")?.let { put("finalmask", it) }
            },
        )
    }

    override fun parse(url: Url): Masque {
        require(url.password == null) { "MASQUE user info must be a WARP private key" }
        remarks = url.proxyUrlRemarks()
        server = url.proxyUrlHost()
        port = (url.port.takeIf { it > 0 } ?: 443).toString()
        user = ""
        pass = ""
        host = url.parameters["host"].orEmpty()
        path = url.parameters["path"].orEmpty()
        headers = url.parameters["headers"].orEmpty()
        remoteDNS = url.parameters["dns"].orEmpty()
        sni = url.parameters["sni"].orEmpty()
        alpn = url.parameters["alpn"]?.trim().orEmpty().ifBlank { "h3" }
        require(alpn == "h2" || alpn == "h3") { "MASQUE ALPN must be h2 or h3" }
        fingerprint = url.parameters["fp"].orEmpty()
        pinSHA256 = url.parameters["pcs"].orEmpty()
        verifyPeerCertByName = url.parameters["vcn"].orEmpty()
        warpPrivateKey = url.user.orEmpty()
        warpPublicKey = url.parameters["publickey"].orEmpty()
        warpAddress = url.parameters["address"].orEmpty()
        finalMask = (url.parameters["fm"] ?: url.parameters["finalmask"] ?: url.parameters["finalMask"])
            .orEmpty()
        requestHeaders()
        return this
    }

    override fun getUrl(): String {
        val shareHeaders = buildJsonObject {
            requestHeaders()?.forEach { (name, value) -> put(name, value) }
            // v2rayNG reserves URI user info for WARP keys, so carry Basic authentication in headers.
            if (user.isNotBlank() || pass.isNotBlank()) {
                put("Authorization", "Basic ${"$user:$pass".encodeBase64()}")
            }
        }
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_MASQUE)
            setProxyUrlHost(this@Masque.server)
            this@Masque.port.toIntOrNull()?.let { port = it }
            user = this@Masque.warpPrivateKey
            parameters.append("security", "tls")
            parameters.append("type", ProxyServerConstants.PROTOCOL_MASQUE)
            parameters.append("alpn", this@Masque.alpn.ifBlank { "h3" })
            mapOf(
                "host" to this@Masque.host,
                "path" to this@Masque.path,
                "dns" to this@Masque.remoteDNS,
                "sni" to this@Masque.sni,
                "fp" to this@Masque.fingerprint,
                "pcs" to this@Masque.pinSHA256,
                "vcn" to this@Masque.verifyPeerCertByName,
                "publickey" to this@Masque.warpPublicKey,
                "address" to this@Masque.warpAddress,
                "fm" to this@Masque.finalMask,
            ).forEach { (name, value) ->
                if (value.isNotBlank()) parameters.append(name, value)
            }
            if (shareHeaders.isNotEmpty()) parameters.append("headers", shareHeaders.toString())
            fragment = this@Masque.remarks
        }.buildString()
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
            headers = other.headers
            remoteDNS = other.remoteDNS
            sni = other.sni
            alpn = other.alpn
            fingerprint = other.fingerprint
            pinSHA256 = other.pinSHA256
            verifyPeerCertByName = other.verifyPeerCertByName
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
        if (runCatching { requestHeaders() }.isFailure) {
            addIssue(ProxyServerValidationError.UnsupportedValue, "MASQUE headers")
        }
        validateOptionalJsonObject(finalMask, "FinalMask")
    }

    private fun requestHeaders(): JsonObject? {
        val parsed = headers.toXrayJsonObjectOrNull("MASQUE headers") ?: return null
        require(parsed.values.all { it is JsonPrimitive && it.isString }) {
            "MASQUE header values must be strings"
        }
        require(parsed.keys.none {
            it.equals("Host", ignoreCase = true) || it.equals("Capsule-Protocol", ignoreCase = true)
        }) { "MASQUE headers cannot contain Host or Capsule-Protocol" }
        require((user.isBlank() && pass.isBlank()) || parsed.keys.none {
            it.equals("Authorization", ignoreCase = true)
        }) { "MASQUE Authorization header conflicts with username/password" }
        return parsed
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
