// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.parsing.ParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import utils.encodeUrlSafeBase64OptionalPadding

@Serializable
data class LegacyVMess(
    @SerialName("ps") var remarks: String = "",
    @SerialName("add") var server: String = "",
    @SerialName("port") @Serializable(with = IntAsStringSerializer::class) var port: Int = 0,
    @SerialName("id") var id: String = "",
    @SerialName("aid") var alterId: String = "",
    @SerialName("scy") var encryption: String = "",
    @SerialName("net") var network: String = "",
    @SerialName("type") var type: String = "",
    @SerialName("host") var host: String = "",
    @SerialName("path") var path: String = "",
    @SerialName("tls") var security: String = "",
    @SerialName("sni") var sni: String = "",
    @SerialName("fp") var fingerprint: String = "",
    @SerialName("alpn") var alpn: String = "",
    @SerialName("v") @Serializable(with = IntAsStringSerializer::class) var version: Int = 2,
) : UrlProxyServer<LegacyVMess> {
    private class IntAsStringSerializer : KSerializer<Int> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("IntAsString", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Int) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): Int {
            return decoder.decodeString().toIntOrNull() ?: 0
        }
    }

    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "VMess")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return convertToAEAD().toXrayOutbound(tag)
    }

    override fun parse(url: Url): LegacyVMess {
        val originJson = url.host.decodeProxyUrlBase64().decodeToString()
        ProxyServer.json.decodeFromString<LegacyVMess>(originJson).let { this.update(it) }
        return this
    }

    override fun getUrl(): String {
        return "${ProxyServerConstants.PROTOCOL_VMESS}://${
            ProxyServer.json.encodeToString(this).encodeToByteArray().encodeUrlSafeBase64OptionalPadding()
        }"
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is LegacyVMess) {
            proxyServerTypeMismatch()
        }
        this.apply {
            remarks = other.remarks
            server = other.server
            port = other.port
            id = other.id
            alterId = other.alterId
            encryption = other.encryption
            network = other.network
            type = other.type
            host = other.host
            path = other.path
            security = other.security
            sni = other.sni
            fingerprint = other.fingerprint
            alpn = other.alpn
            version = other.version
        }
    }

    override fun check() {
        throw IllegalStateException("Legacy VMess url format not use in this program, only for parse, consider using convertToAEAD()")
    }

    fun convertToAEAD(): VMess {
        return VMess(
            remarks = this.remarks,
            id = this.id,
            server = this.server,
            port = this.port.toString(),
            encryption = this.encryption,
            parms = V2RayParameters(
                type = this.network,
                security = this.security,
                path = this.path,
                host = this.host,
                serviceName = this.path,
                mode = this.type,
                authority = this.host,
                fp = this.fingerprint,
                sni = this.sni,
                alpn = this.alpn,
                headerType = this.type,
            )
        )
    }
}

@Serializable
data class VMess(
    var remarks: String = "",
    var id: String = "",
    var server: String = "",
    var port: String = "443",
    var encryption: String = "auto",
    var parms: V2RayParameters = V2RayParameters(),
) : UrlProxyServer<VMess> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "VMess")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return OutboundObject(
            tag = tag,
            protocol = ProxyServerConstants.PROTOCOL_VMESS,
            settings = buildJsonObject {
                put("address", server)
                put("port", port.toXrayPort())
                put("id", id)
                put("security", encryption.ifBlank { "auto" })
            },
            streamSettings = parms.toXrayStreamSettings(),
        )
    }

    override fun parse(url: Url): VMess {
        this.remarks = url.fragment
        this.id = url.user ?: throw ParseException("Invalid VMessAEAD url")
        this.server = url.host
        this.port = url.port.toString()
        this.encryption = url.parameters["encryption"] ?: "auto"
        this.parms = this.parms.parse(url, "raw", "none")
        return this
    }

    override fun getUrl(): String {
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_VMESS)
            host = this@VMess.server
            this@VMess.port.toIntOrNull()?.let { port = it }
            user = this@VMess.id
            parameters.append("encryption", this@VMess.encryption)
            this@VMess.parms.let {
                parameters.appendAll(it.get())
            }
            fragment = this@VMess.remarks
        }.buildString()
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is VMess) {
            proxyServerTypeMismatch()
        }
        this.apply {
            remarks = other.remarks
            id = other.id
            server = other.server
            port = other.port
            encryption = other.encryption
            parms = other.parms.copy()
        }
    }

    override fun check() {
        validateCommonServerFields(remarks, server, port)
        validateXrayUserId(id)
        validateAllowed(
            encryption.ifBlank { "auto" },
            "encryption method",
            setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"),
        )
        validateV2RayParameters(parms)
    }
}
