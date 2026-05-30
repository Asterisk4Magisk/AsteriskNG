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
    var insecure: Int = 0,
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
                    if (insecure == 1) {
                        put("allowInsecure", true)
                    }
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
        this.remarks = url.fragment
        this.server = url.host
        this.port = url.port.toString()
        this.auth = url.user ?: ""
        this.obfs = url.parameters["obfs"] ?: ""
        this.obfsPassword = url.parameters["obfs-password"] ?: ""
        this.sni = url.parameters["sni"] ?: ""
        this.insecure = url.parameters["insecure"]?.toIntOrNull() ?: 0
        this.pinSHA256 = url.parameters["pinSHA256"] ?: ""
        //v2rayNg
        this.mport = url.parameters["mport"] ?: url.parameters["ports"] ?: ""
        this.mportHopInt = url.parameters["mportHopInt"] ?: url.parameters["hop-interval"] ?: ""
        this.up = url.parameters["up"] ?: ""
        this.down = url.parameters["down"] ?: ""
        this.security = url.parameters["security"] ?: ""
        return this
    }

    override fun getUrl(): String {
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_HYSTERIA2)
            host = this@Hysteria2.server
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
                parameters.append("insecure", this@Hysteria2.insecure.toString())
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
            insecure = other.insecure
            pinSHA256 = other.pinSHA256
            mport = other.mport
            mportHopInt = other.mportHopInt
            up = other.up
            down = other.down
            security = other.security
        }
    }

    override fun check() {
        validateCommonServerFields(remarks, server, port)
        validateRequired(auth, "password")
        if (obfs.isBlank() && obfsPassword.isNotBlank()) {
            proxyValidationError(ProxyServerValidationError.HysteriaObfsTypeRequired)
        }
        if (obfs.isNotBlank() && obfs != "salamander") {
            proxyValidationError(ProxyServerValidationError.HysteriaObfsUnsupported)
        }
        validateHysteriaMultiPorts(mport)
        validateOptionalPositivePortInterval(mportHopInt)
        validateOptionalBandwidth(up, "up bandwidth")
        validateOptionalBandwidth(down, "down bandwidth")
        validateAllowed(security.ifBlank { "none" }, "transport security", setOf("none", "tls"))
        if (insecure !in 0..1) {
            proxyValidationError(ProxyServerValidationError.BooleanRequired)
        }
        validateOptionalSha256(pinSHA256, "certificate fingerprint")
    }
}

private fun Hysteria2.toXrayFinalMask(): JsonObject {
    return buildJsonObject {
        if (up.isNotBlank() || down.isNotBlank() || mport.isNotBlank()) {
            putJsonObject("quicParams") {
                if (up.isNotBlank() || down.isNotBlank()) {
                    put("congestion", "brutal")
                }
                putIfNotBlank("brutalUp", up)
                putIfNotBlank("brutalDown", down)
                if (mport.isNotBlank()) {
                    putJsonObject("udpHop") {
                        put("ports", mport)
                        mportHopInt.toIntOrNull()?.let { put("interval", it) }
                    }
                }
            }
        }
        if (obfs == "salamander" && obfsPassword.isNotBlank()) {
            putJsonArray("udp") {
                add(
                    buildJsonObject {
                        put("type", "salamander")
                        putJsonObject("settings") {
                            put("password", obfsPassword)
                        }
                    },
                )
            }
        }
    }
}

private fun validateOptionalPositivePortInterval(value: String) {
    if (value.isBlank()) return
    val seconds = value.toIntOrNull()
        ?: proxyValidationError(ProxyServerValidationError.PortHoppingIntervalNumberRequired)
    if (seconds < 5) {
        proxyValidationError(ProxyServerValidationError.PortHoppingIntervalTooSmall)
    }
}
