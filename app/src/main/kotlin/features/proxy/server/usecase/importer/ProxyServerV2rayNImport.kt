// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.logs.AndroidAppLogger
import features.proxy.server.model.ChainProxy
import features.proxy.server.model.HTTP
import features.proxy.server.model.Hysteria2
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import features.proxy.server.model.StrategyGroup
import features.proxy.server.model.StrategyGroupConstants
import features.proxy.server.model.Trojan
import features.proxy.server.model.V2RayParameters
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import features.proxy.server.model.Wireguard
import features.proxy.server.model.isCompositeProxyServer
import features.proxy.server.model.toCanonicalV2RayTransportType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import utils.decodeFlexibleBase64OrNull

internal const val V2rayNSharePrefix = "v2rayn://"
internal const val ImportedSubscriptionGroup = -1
internal const val ImportedExplicitGroup = -2

internal data class ProxyUrlImportCandidate(
    val server: ProxyServer<*>,
    val share: JsonObject? = null,
)

/** v2rayNG 2.3.11 V2rayNShareItem: v2rayn://vless/<base64url JSON>, etc. */
internal fun parseV2rayNImportCandidate(url: String, index: Int): ProxyUrlImportCandidate {
    val payload = url.substringAfterLast('/')
        .decodeFlexibleBase64OrNull()?.decodeToString()
        ?: error("Invalid v2rayN share encoding")
    val item = ProxyServer.json.parseToJsonElement(payload) as? JsonObject
        ?: error("v2rayN share must be an object")
    val server = item.toV2rayNProxyServer(index)
    require(server.validateFull().isEmpty()) { "Invalid v2rayN proxy settings" }
    return ProxyUrlImportCandidate(server, item)
}

/** Resolve IndexId references before IDs are assigned by either manual or subscription import. */
internal fun resolveV2rayNImportCandidates(candidates: List<ProxyUrlImportCandidate>): List<ProxyServer<*>> {
    val byId = linkedMapOf<String, ProxyUrlImportCandidate>()
    val unique = candidates.filter { candidate ->
        val id = candidate.share?.text("IndexId")
        id.isNullOrBlank() || byId.putIfAbsent(id, candidate) == null
    }
    val resolved = unique.mapNotNull { candidate ->
        runCatching {
            val item = candidate.share ?: return@runCatching candidate
            if (!candidate.server.isCompositeProxyServer()) return@runCatching candidate
            val extra = item.objectValue("ProtoExtraObj")
            val children = extra.text("ChildItems").orEmpty().split(',')
                .map(String::trim).filter(String::isNotEmpty)
            val members = children.map { id ->
                val member = byId[id] ?: error("Missing v2rayN child")
                require(!member.server.isCompositeProxyServer()) { "Nested v2rayN groups are not supported" }
                member
            }
            when (val server = candidate.server) {
                is StrategyGroup -> {
                    val source = extra.text("SubChildItems").orEmpty()
                    require(source.isBlank() || source == "self") { "External subscription reference is not available" }
                    require(source == "self" || members.isNotEmpty() || !extra.text("Filter").isNullOrBlank()) {
                        "A v2rayN strategy group requires a member source"
                    }
                    val filter = if (members.isNotEmpty()) {
                        val selectedNames = members.map { it.server.getInfo().remarks }.toSet()
                        require(unique.filter { !it.server.isCompositeProxyServer() }
                            .groupingBy { it.server.getInfo().remarks }.eachCount()
                            .none { (name, count) -> name in selectedNames && count > 1 }) {
                            "Ambiguous v2rayN strategy member names"
                        }
                        members.joinToString("|", "^(?:", ")$") { Regex.escape(it.server.getInfo().remarks) }
                    } else {
                        extra.text("Filter").orEmpty().also { if (it.isNotBlank()) Regex(it) }
                    }
                    candidate.copy(server = server.copy(
                        subscriptionGroupId = when {
                            members.isNotEmpty() -> ImportedExplicitGroup
                            source == "self" -> ImportedSubscriptionGroup
                            else -> null
                        },
                        filter = filter,
                    ))
                }
                is ChainProxy -> {
                    require(members.size >= 2) { "A v2rayN chain requires at least two available nodes" }
                    candidate
                }
                else -> candidate
            }
        }.getOrElse {
            AndroidAppLogger.warn("ProxyServerV2rayNImport", "Skipped v2rayN share with unresolved group members")
            null
        }
    }
    return resolved.map { candidate ->
        val server = candidate.server
        if (server !is ChainProxy) return@map server
        val ids = candidate.share!!.objectValue("ProtoExtraObj").text("ChildItems").orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty)
        server.copy(proxyServerIds = ids.map { id ->
            // Negative IDs are batch-local indexes, resolved before persistence.
            val member = byId.getValue(id)
            val index = resolved.indexOfFirst { it === member || it.server === member.server }
            check(index >= 0) { "Missing resolved v2rayN child" }
            -index - 1
        })
    }
}

private fun JsonObject.toV2rayNProxyServer(index: Int): ProxyServer<*> {
    val type = number("ConfigType") ?: error("Missing v2rayN ConfigType")
    val remarks = text("Remarks").orEmpty().ifBlank { "v2rayN ${index + 1}" }
    val server = text("Address").orEmpty()
    val port = number("Port")?.toString().orEmpty()
    val password = text("Password").orEmpty()
    val extra = objectValue("ProtoExtraObj")
    require(text("AllowInsecure")?.lowercase() !in setOf("true", "1")) { "Insecure TLS is not supported" }
    if (type == 101) return StrategyGroup(
        remarks = remarks,
        strategy = when (extra.number("MultipleLoad")) {
            2 -> StrategyGroupConstants.TYPE_RANDOM
            3 -> StrategyGroupConstants.TYPE_ROUND_ROBIN
            4 -> StrategyGroupConstants.TYPE_LEAST_LOAD
            else -> StrategyGroupConstants.TYPE_LEAST_PING
        },
    )
    // Placeholder members pass structural validation; real references are resolved as a batch.
    if (type == 102) return ChainProxy(remarks, listOf(-1, -1))
    val parameters = v2rayNParameters(if (type == 6) "tls" else "none")
    return when (type) {
        1 -> {
            require((extra.number("AlterId") ?: 0) == 0) { "Legacy VMess alterId is not supported" }
            VMess(remarks, password, server, port, extra.text("VmessSecurity") ?: "auto", parameters)
        }
        3 -> Shadowsocks(remarks, server, port, extra.text("SsMethod").orEmpty(), password, parameters)
        4, 10 -> {
            require(parameters.type == "raw" && parameters.security == "none" && parameters.fm.isNullOrBlank() &&
                parameters.headerType.orEmpty() in setOf("", "none")) { "HTTP/SOCKS transport settings cannot be represented" }
            if (type == 4) Socks(remarks, server, port, text("Username"), text("Password"))
            else HTTP(remarks, server, port, text("Username"), text("Password"))
        }
        5 -> VLESS(remarks, password, server, port, extra.text("VlessEncryption") ?: "none", extra.text("Flow").orEmpty(), parameters)
        6 -> Trojan(remarks, password, server, port, parameters)
        7 -> {
            require(listOf("Alpn", "Fingerprint", "EchConfigList", "VerifyPeerCertByName", "Finalmask")
                .all { text(it).isNullOrBlank() }) { "Hysteria2 TLS options cannot be represented" }
            Hysteria2(
                remarks = remarks, server = server, port = port, auth = password,
                obfs = if (extra.text("SalamanderPass").isNullOrBlank()) "" else "salamander",
                obfsPassword = extra.text("SalamanderPass").orEmpty(),
                sni = text("Sni").orEmpty(), pinSHA256 = text("CertSha").orEmpty(),
                mport = extra.text("Ports").orEmpty(), mportHopInt = extra.text("HopInterval").orEmpty(),
                up = extra.positiveBandwidth("UpMbps"), down = extra.positiveBandwidth("DownMbps"), security = "tls",
            )
        }
        9 -> Wireguard(
            remarks = remarks, server = server, port = port, secretKey = password,
            publicKey = extra.text("WgPublicKey") ?: text("PublicKey").orEmpty(),
            preSharedKey = extra.text("WgPresharedKey").orEmpty(),
            reserved = extra.text("WgReserved") ?: "0,0,0",
            address = extra.text("WgInterfaceAddress") ?: "172.16.0.2/32",
            mtu = extra.number("WgMtu")?.toString() ?: "1420",
            finalMask = text("Finalmask").orEmpty(),
        )
        else -> error("Unsupported v2rayN ConfigType")
    }
}

private fun JsonObject.v2rayNParameters(defaultSecurity: String): V2RayParameters {
    val transport = objectValue("TransportExtraObj")
    val network = text("Network").toCanonicalV2RayTransportType()
    return V2RayParameters(
        type = network,
        security = text("StreamSecurity")?.takeIf(String::isNotBlank) ?: defaultSecurity,
        path = transport.text("Path"), host = transport.text("Host"),
        headerType = if (network == "mkcp") transport.text("KcpHeaderType") ?: "none"
            else transport.text("RawHeaderType") ?: "none",
        mtu = transport.number("KcpMtu")?.toString(), seed = transport.text("KcpSeed"),
        serviceName = transport.text("GrpcServiceName"), authority = transport.text("GrpcAuthority"),
        mode = if (network == "xhttp") transport.text("XhttpMode") else transport.text("GrpcMode"),
        extra = transport.text("XhttpExtra"), fm = text("Finalmask"),
        fp = text("Fingerprint"), sni = text("Sni"), alpn = text("Alpn"),
        ech = text("EchConfigList"), pcs = text("CertSha"), vcn = text("VerifyPeerCertByName"),
        pbk = text("PublicKey"), sid = text("ShortId"), pqv = text("Mldsa65Verify"), spx = text("SpiderX"),
    )
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.let { value ->
    if (value.contentOrNull == null) null else requireNotNull(value.intOrNull) { "Invalid v2rayN integer field" }
}
private fun JsonObject.objectValue(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.positiveBandwidth(key: String): String = number(key)?.takeIf { it > 0 }?.let { "${it}Mbps" }.orEmpty()
