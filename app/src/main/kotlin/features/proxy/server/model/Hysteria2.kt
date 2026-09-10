// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import utils.proxyUrlRemarks

@Serializable
data class Hysteria2(
    //standard
    var remarks: String = "",
    var server: String = "",
    var port: String = "443",
    var auth: String = "",
    var obfs: String = "",
    var obfsPassword: String = "",
    var sni: String = "",
    var pinSHA256: String = "",
    //v2rayNg
    var mport: String = "",
    var mportHopInt: String = "",
    var up: String = "",
    var down: String = "",
    var security: String = "none",
) : UrlProxyServer<Hysteria2> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "Hysteria2")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return OutboundObject(
            tag = tag,
            protocol = "hysteria",
            settings = buildJsonObject {
                put("version", 2)
                put("address", server)
                put("port", port.toXrayPort())
            },
            streamSettings = buildJsonObject {
                put("network", "hysteria")
                putJsonObject("hysteriaSettings") {
                    put("version", 2)
                    put("auth", auth)
                }
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    putIfNotBlank("serverName", sni)
                    putIfNotBlank("pinnedPeerCertSha256", pinSHA256)
                }
                val finalMask = toXrayFinalMask()
                if (finalMask.isNotEmpty()) {
                    put("finalmask", finalMask)
                }
            },
        )
    }

    override fun parse(url: Url): Hysteria2 {
        //standard
        this.remarks = url.proxyUrlRemarks()
        this.server = url.proxyUrlHost()
        this.port = url.port.toString()
        this.auth = url.user ?: ""
        this.obfs = url.parameters["obfs"] ?: ""
        this.obfsPassword = url.parameters["obfs-password"] ?: ""
        this.sni = url.parameters["sni"] ?: ""
        if (url.parameters["insecure"].isEnabledFlag()) {
            throw IllegalArgumentException("Hysteria2 insecure is not supported")
        }
        this.pinSHA256 = url.parameters["pinSHA256"] ?: ""
        //v2rayNg
        this.mport = url.parameters["mport"] ?: url.parameters["ports"] ?: ""
        this.mportHopInt = url.parameters["mportHopInt"] ?: url.parameters["hop-interval"] ?: ""
        this.up = url.parameters["up"] ?: ""
        this.down = url.parameters["down"] ?: ""
        this.security = url.parameters["security"] ?: "tls"
        return this
    }

    override fun getUrl(): String {
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_HYSTERIA2)
            setProxyUrlHost(this@Hysteria2.server)
            this@Hysteria2.port.toIntOrNull()?.let { port = it }
            user = this@Hysteria2.auth

            if (this@Hysteria2.obfs.isNotBlank()) {
                parameters.append("obfs", this@Hysteria2.obfs)
            }
            if (this@Hysteria2.obfsPassword.isNotBlank()) {
                parameters.append("obfs-password", this@Hysteria2.obfsPassword)
            }
            if (this@Hysteria2.sni.isNotBlank()) {
                parameters.append("sni", this@Hysteria2.sni)
            }
            if (this@Hysteria2.pinSHA256.isNotBlank()) {
                parameters.append("pinSHA256", this@Hysteria2.pinSHA256)
            }

            if (this@Hysteria2.mport.isNotBlank()) {
                parameters.append("mport", this@Hysteria2.mport)
            }
            if (this@Hysteria2.mportHopInt.isNotBlank()) {
                parameters.append("mportHopInt", this@Hysteria2.mportHopInt)
            }
            if (this@Hysteria2.security.isNotBlank() && this@Hysteria2.security != "none") {
                parameters.append("security", this@Hysteria2.security)
            }

            fragment = this@Hysteria2.remarks
        }.buildString()
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is Hysteria2) {
            proxyServerTypeMismatch()
        }
        this.apply {
            remarks = other.remarks
            server = other.server
            port = other.port
            auth = other.auth
            obfs = other.obfs
            obfsPassword = other.obfsPassword
            sni = other.sni
            pinSHA256 = other.pinSHA256
            mport = other.mport
            mportHopInt = other.mportHopInt
            up = other.up
            down = other.down
            security = other.security
        }
    }

    override fun validateBasic(): List<ProxyServerValidationIssue> = buildList {
        validateCommonServerFields(remarks, server, port)
        validateRequired(auth, "password")
    }

    override fun validateFull(): List<ProxyServerValidationIssue> = buildList {
        addAll(validateBasic())
        if (obfs.isBlank() && obfsPassword.isNotBlank()) {
            addIssue(ProxyServerValidationError.HysteriaObfsTypeRequired)
        }
        if (obfs.isNotBlank() && obfs != "salamander") {
            addIssue(ProxyServerValidationError.HysteriaObfsUnsupported)
        }
        validateHysteriaMultiPorts(mport)
        validateOptionalPositivePortInterval(mportHopInt)
        validateOptionalBandwidth(up, "up bandwidth")
        validateOptionalBandwidth(down, "down bandwidth")
        validateAllowed(security.ifBlank { "none" }, "transport security", setOf("none", "tls"))
        validateOptionalSha256(pinSHA256, "certificate fingerprint")
    }
}

private fun String?.isEnabledFlag(): Boolean {
    return when (this?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }
}

private fun Hysteria2.toXrayFinalMask(): JsonObject {
    return buildJsonObject {
        if (up.isNotBlank() || down.isNotBlank()) {
            putJsonObject("quicParams") {
                put("congestion", "brutal")
                putIfNotBlank("brutalUp", up)
                putIfNotBlank("brutalDown", down)
            }
        }
        val useSalamander = obfs == "salamander" && obfsPassword.isNotBlank()
        if (useSalamander || mport.isNotBlank()) {
            putJsonArray("udp") {
                if (useSalamander) {
                    add(buildJsonObject {
                        put("type", "salamander")
                        putJsonObject("settings") {
                            put("password", obfsPassword)
                        }
                    })
                }
                if (mport.isNotBlank()) {
                    // Xray wraps masks in reverse order; udphop must be outermost.
                    add(buildJsonObject {
                        put("type", "udphop")
                        putJsonObject("settings") {
                            put("mode", "intervalLocal,intervalRemote")
                            put("remotePorts", mport)
                            put("interval", mportHopInt.toIntOrNull() ?: DefaultHopIntervalSeconds)
                        }
                    })
                }
            }
        }
    }
}

private const val DefaultHopIntervalSeconds = 30
