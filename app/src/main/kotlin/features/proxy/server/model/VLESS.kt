package features.proxy.server.model

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.parsing.ParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class VLESS(
    var remarks: String = "",
    var id: String = "",
    var server: String = "",
    var port: String = "",
    var encryption: String = "",
    var flow: String = "",
    var parms: V2RayParameters = V2RayParameters(),
) : UrlProxyServer<VLESS> {
    override fun getInfo(): ProxyServerInfo {
        return ProxyServerInfo(this.remarks, "${this.server}:${this.port}", "VLESS")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        return OutboundObject(
            tag = tag,
            protocol = ProxyServerConstants.PROTOCOL_VLESS,
            settings = buildJsonObject {
                put("address", server)
                put("port", port.toXrayPort())
                put("id", id)
                put("encryption", encryption.ifBlank { "none" })
                putIfNotBlank("flow", flow)
            },
            streamSettings = parms.toXrayStreamSettings(),
        )
    }

    override fun parse(url: Url): VLESS {
        this.remarks = url.fragment
        this.id = url.user ?: throw ParseException("Invalid VLESS url")
        this.server = url.host
        this.port = url.port.toString()
        this.encryption = url.parameters["encryption"] ?: "none"
        this.flow = url.parameters["flow"] ?: ""
        this.parms = this.parms.parse(url, "raw", "none")
        return this
    }

    override fun getUrl(): String {
        return URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(ProxyServerConstants.PROTOCOL_VLESS)
            host = this@VLESS.server
            this@VLESS.port.toIntOrNull()?.let { port = it }
            user = this@VLESS.id
            if (this@VLESS.encryption.isNotBlank()) {
                parameters.append("encryption", this@VLESS.encryption)
            }
            if (this@VLESS.flow.isNotBlank()) {
                parameters.append("flow", this@VLESS.flow)
            }
            this@VLESS.parms.let {
                parameters.appendAll(it.get())
            }
            fragment = this@VLESS.remarks
        }.buildString()
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is VLESS) {
            proxyServerTypeMismatch()
        }
        this.apply {
            remarks = other.remarks
            id = other.id
            server = other.server
            port = other.port
            encryption = other.encryption
            flow = other.flow
            parms = other.parms.copy()
        }
    }

    override fun check() {
        validateCommonServerFields(remarks, server, port)
        validateXrayUserId(id)
        validateVlessEncryption(encryption)
        if (flow.isNotBlank()) {
            val usesVlessEncryption = encryption.isNotBlank() && encryption != "none"
            val transport = parms.type.ifBlank { "raw" }
            if (!usesVlessEncryption && (transport !in setOf("tcp", "raw") || parms.security !in setOf("tls", "reality"))) {
                proxyValidationError(ProxyServerValidationError.VlessVisionFlowUnsupported)
            }
        }
        validateAllowed(flow, "flow", setOf("xtls-rprx-vision", "xtls-rprx-vision-udp443"), allowBlank = true)
        validateV2RayParameters(parms)
    }
}
