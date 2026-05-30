// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import engine.network.NetworkLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64

private val DomainLabelRegex = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
private val HexRegex = Regex("[0-9a-fA-F]+")
private val RawUrlSafeBase64Regex = Regex("[A-Za-z0-9_-]+")
private val UrlSafeBase64Regex = Regex("[A-Za-z0-9_-]+={0,2}")
private val UuidRegex = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
)
private val WireguardKeyRegex = Regex("[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=")
private val HysteriaBandwidthRegex =
    Regex("0|[1-9][0-9]*(?:\\.[0-9]+)?(?:\\s*(?:[kKmMgGtT](?:[bB](?:[pP][sS])?)?|[bB](?:[pP][sS])?))?")
private const val XrayUserIdMaxBytes = 30
private const val KcpMtuMin = 576
private const val KcpMtuMax = 1460
private const val KcpTtiMin = 10
private const val KcpTtiMax = 100
private const val WireguardMtuMin = 576
private const val WireguardMtuMax = 9000
private const val RealityPublicKeyBytes = 32
private const val RealityMldsa65VerifyBytes = 1952

internal fun validateRequired(value: String?, fieldName: String) {
    if (value.isNullOrBlank()) {
        proxyValidationError(ProxyServerValidationError.RequiredField, fieldName)
    }
}

internal fun validateRemarks(remarks: String) {
    validateRequired(remarks, "remarks")
}

internal fun validateServer(server: String) {
    val value = server.trim()
    validateRequired(value, "server address")
    if (
        value.contains("://") ||
        value.any { it.isWhitespace() } ||
        value.any { it == '/' || it == '?' || it == '#' || it == '@' }
    ) {
        proxyValidationError(ProxyServerValidationError.ServerAddressContainsInvalidContent)
    }

    val host = if (value.startsWith("[") && value.endsWith("]")) {
        value.substring(1, value.length - 1)
    } else {
        value
    }
    if (!isValidIpv4(host) && !isValidIpv6(host) && !isValidDomain(host)) {
        proxyValidationError(ProxyServerValidationError.InvalidServerAddress)
    }
}

internal fun validatePort(port: String, fieldName: String = "server port"): Int {
    val value = port.trim()
    validateRequired(value, fieldName)
    val number = value.toIntOrNull() ?: proxyValidationError(ProxyServerValidationError.NumberRequired, fieldName)
    if (number !in NetworkLimits.PORT_MIN..NetworkLimits.PORT_MAX) {
        proxyValidationError(
            ProxyServerValidationError.PortOutOfRange,
            NetworkLimits.PORT_MIN,
            NetworkLimits.PORT_MAX,
        )
    }
    return number
}

internal fun validateAllowed(
    value: String,
    fieldName: String,
    allowed: Set<String>,
    allowBlank: Boolean = false,
) {
    val normalized = value.trim()
    if (normalized.isBlank()) {
        if (allowBlank) return
        proxyValidationError(ProxyServerValidationError.RequiredField, fieldName)
    }
    if (normalized !in allowed) {
        proxyValidationError(ProxyServerValidationError.UnsupportedValue, normalized)
    }
}

internal fun validateCommonServerFields(
    remarks: String,
    server: String,
    port: String,
) {
    validateRemarks(remarks)
    validateServer(server)
    validatePort(port)
}

internal fun validateOptionalUserPassword(user: String?, password: String?) {
    if (user.isNullOrBlank() && !password.isNullOrBlank()) {
        proxyValidationError(ProxyServerValidationError.UsernameRequiredForPassword)
    }
    if (!user.isNullOrBlank() && password.isNullOrBlank()) {
        proxyValidationError(ProxyServerValidationError.PasswordRequiredForUsername)
    }
}

internal fun validateV2RayParameters(params: V2RayParameters) {
    val type = params.type.ifBlank { "raw" }
    val security = params.security.ifBlank { "none" }
    validateOptionalJsonObject(params.fm, "FinalMask")
    validateAllowed(
        type,
        "transport protocol",
        setOf("tcp", "raw", "kcp", "mkcp", "ws", "websocket", "httpupgrade", "xhttp", "splithttp", "grpc"),
    )
    validateAllowed(security, "transport security", setOf("none", "tls", "reality"))

    when (type) {
        "tcp", "raw" -> validateAllowed(params.headerType ?: "none", "header type", setOf("none", "http"))
        "kcp", "mkcp" -> {
            validateOptionalIntRange(params.mtu, "mKCP MTU", KcpMtuMin, KcpMtuMax)
            validateOptionalIntRange(params.tti, "mKCP TTI", KcpTtiMin, KcpTtiMax)
        }

        "ws", "websocket", "httpupgrade", "xhttp", "splithttp" -> validateOptionalPath(params.path, "${type} path")
        "grpc" -> validateAllowed(params.mode ?: "gun", "gRPC mode", setOf("gun", "multi"))
    }
    if (type in setOf("xhttp", "splithttp")) {
        validateAllowed(params.mode ?: "auto", "XHTTP mode", setOf("auto", "packet-up", "stream-up", "stream-one"))
        validateOptionalJsonObject(params.extra, "XHTTP Extra")
    }

    when (security) {
        "none" -> Unit
        "tls" -> {
            validateOptionalFingerprint(params.fp)
            validateOptionalAlpn(params.alpn)
            validateOptionalSha256(params.pcs, "certificate fingerprint")
        }

        "reality" -> {
            if (type !in setOf("tcp", "raw", "xhttp", "splithttp", "grpc")) {
                proxyValidationError(ProxyServerValidationError.RealityTransportUnsupported)
            }
            validateRealityPublicKey(params.pbk.orEmpty())
            validateRequired(params.fp, "TLS fingerprint")
            validateOptionalFingerprint(params.fp)
            validateOptionalRealityMldsa65Verify(params.pqv)
            validateOptionalPath(params.spx, "SpiderX")
            val shortId = params.sid.orEmpty()
            if (shortId.isNotBlank() && (shortId.length > 16 || shortId.length % 2 != 0 || !HexRegex.matches(shortId))) {
                proxyValidationError(ProxyServerValidationError.RealityShortIdInvalid)
            }
        }
    }
}

internal fun validateWireguardKey(value: String, fieldName: String, required: Boolean = true) {
    if (value.isBlank()) {
        if (required) {
            proxyValidationError(ProxyServerValidationError.RequiredField, fieldName)
        }
        return
    }
    if (!WireguardKeyRegex.matches(value.trim())) {
        proxyValidationError(ProxyServerValidationError.WireguardKeyInvalid, fieldName)
    }
}

internal fun validateWireguardReserved(reserved: String) {
    if (reserved.isBlank()) return
    val parts = reserved.split(",")
    if (parts.size != 3) {
        proxyValidationError(ProxyServerValidationError.WireguardReservedCountInvalid)
    }
    parts.forEach { part ->
        val value = part.trim().toIntOrNull()
        if (value == null || value !in NetworkLimits.IPV4_OCTET_MIN..NetworkLimits.IPV4_OCTET_MAX) {
            proxyValidationError(
                ProxyServerValidationError.WireguardReservedValueInvalid,
                NetworkLimits.IPV4_OCTET_MIN,
                NetworkLimits.IPV4_OCTET_MAX,
            )
        }
    }
}

internal fun validateWireguardAddresses(addresses: String) {
    if (addresses.isBlank()) return
    addresses.split(",").forEach { address ->
        val value = address.trim()
        val slashIndex = value.lastIndexOf('/')
        if (value.isBlank() || slashIndex <= 0 || slashIndex == value.lastIndex) {
            proxyValidationError(ProxyServerValidationError.LocalAddressCidrRequired)
        }
        val host = value.substring(0, slashIndex)
        val prefix = value.substring(slashIndex + 1).toIntOrNull()
            ?: proxyValidationError(ProxyServerValidationError.LocalAddressPrefixNumberRequired)
        val validPrefix = when {
            isValidIpv4(host) -> prefix in 0..32
            isValidIpv6(host) -> prefix in 0..128
            else -> false
        }
        if (!validPrefix) {
            proxyValidationError(ProxyServerValidationError.InvalidLocalAddressCidr)
        }
    }
}

internal fun validateMtu(mtu: String) {
    if (mtu.isBlank()) return
    val value = mtu.toIntOrNull() ?: proxyValidationError(ProxyServerValidationError.MtuNumberRequired)
    if (value !in WireguardMtuMin..WireguardMtuMax) {
        proxyValidationError(ProxyServerValidationError.MtuOutOfRange, WireguardMtuMin, WireguardMtuMax)
    }
}

internal fun validateHysteriaMultiPorts(mport: String) {
    if (mport.isBlank()) return
    mport.split(",").forEach { part ->
        val range = part.trim().split("-")
        if (range.size !in 1..2) {
            proxyValidationError(ProxyServerValidationError.InvalidPortHoppingFormat)
        }
        val start = validatePort(range[0], "port hopping")
        val end = if (range.size == 2) validatePort(range[1], "port hopping") else start
        if (start > end) {
            proxyValidationError(ProxyServerValidationError.PortHoppingRangeInvalid)
        }
    }
}

internal fun validateOptionalBandwidth(value: String, fieldName: String) {
    if (value.isBlank()) return
    if (!HysteriaBandwidthRegex.matches(value.trim())) {
        proxyValidationError(ProxyServerValidationError.InvalidBandwidthFormat)
    }
}

internal fun validateOptionalSha256(value: String?, fieldName: String) {
    val hash = value.orEmpty()
    if (hash.isBlank()) return
    hash.split(",").forEach { item ->
        val normalized = item.trim().replace(":", "")
        if (normalized.length != 64 || !HexRegex.matches(normalized)) {
            proxyValidationError(ProxyServerValidationError.Sha256Invalid, fieldName)
        }
    }
}

internal fun validateOptionalJsonObject(value: String?, fieldName: String) {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return
    val jsonObject = runCatching {
        Json.parseToJsonElement(text)
    }.getOrNull() as? JsonObject
    if (jsonObject == null) {
        proxyValidationError(ProxyServerValidationError.JsonObjectRequired, fieldName)
    }
}

internal fun validateRealityPublicKey(value: String) {
    val key = value.trim()
    validateRequired(key, "Reality PublicKey")
    val decoded = if (UrlSafeBase64Regex.matches(key)) {
        runCatching {
            Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(key)
        }.getOrNull()
    } else {
        null
    }
    if (decoded?.size != RealityPublicKeyBytes) {
        proxyValidationError(ProxyServerValidationError.RealityPublicKeyInvalid)
    }
}

internal fun validateXrayUserId(value: String, fieldName: String = "user ID") {
    validateRequired(value, fieldName)
    val normalized = value.trim()
    if (UuidRegex.matches(normalized)) return
    if (normalized.encodeToByteArray().size >= XrayUserIdMaxBytes) {
        proxyValidationError(ProxyServerValidationError.XrayUserIdInvalid, XrayUserIdMaxBytes)
    }
}

internal fun validateVlessEncryption(value: String) {
    val encryption = value.ifBlank { "none" }
    if (encryption == "none") return

    val blocks = encryption.split(".")
    if (blocks.size < 4) {
        proxyValidationError(ProxyServerValidationError.InvalidVlessEncryption)
    }
    validateAllowed(blocks[0], "VLESS handshake", setOf("mlkem768x25519plus"))
    validateAllowed(blocks[1], "VLESS encryption method", setOf("native", "xorpub", "random"))
    validateAllowed(blocks[2], "VLESS session mode", setOf("0rtt", "1rtt"))

    val paddingBlocks = blocks.drop(3).dropLast(1)
    if (paddingBlocks.isNotEmpty()) {
        if (paddingBlocks.size % 2 == 0) {
            proxyValidationError(ProxyServerValidationError.VlessPaddingBoundaryInvalid)
        }
        paddingBlocks.forEachIndexed { index, block ->
            validateVlessPaddingBlock(block, if (index % 2 == 0) "padding" else "delay", firstPadding = index == 0)
        }
    }
    validateVlessEncryptionVerifier(blocks.last())
}

private fun validateVlessEncryptionVerifier(value: String) {
    val verifier = value.trim()
    validateRequired(verifier, "VLESS encryption verifier")
    if (decodeRawUrlSafeBase64(verifier) == null) {
        proxyValidationError(ProxyServerValidationError.VlessEncryptionVerifierInvalid)
    }
}

private fun validateVlessPaddingBlock(block: String, fieldName: String, firstPadding: Boolean) {
    val parts = block.split("-")
    if (parts.size != 3) {
        proxyValidationError(ProxyServerValidationError.VlessPaddingFormatInvalid)
    }
    val probability = parts[0].toIntOrNull()
    val min = parts[1].toIntOrNull()
    val max = parts[2].toIntOrNull()
    if (probability == null || probability !in 0..100 || min == null || max == null || min < 0 || max < min) {
        proxyValidationError(ProxyServerValidationError.VlessPaddingRangeInvalid)
    }
    if (firstPadding && (probability != 100 || min <= 0)) {
        proxyValidationError(ProxyServerValidationError.VlessFirstPaddingInvalid)
    }
}

private fun validateOptionalIntRange(value: String?, fieldName: String, min: Int, max: Int) {
    if (value.isNullOrBlank()) return
    val number = value.toIntOrNull() ?: proxyValidationError(ProxyServerValidationError.NumberRequired, fieldName)
    if (number !in min..max) {
        proxyValidationError(ProxyServerValidationError.ValueOutOfRange, min, max)
    }
}

private fun validateOptionalPath(value: String?, fieldName: String) {
    val path = value.orEmpty()
    if (path.isNotBlank() && !path.startsWith("/")) {
        proxyValidationError(ProxyServerValidationError.PathMustStartWithSlash)
    }
}

private fun validateOptionalRealityMldsa65Verify(value: String?) {
    val verify = value?.trim().orEmpty()
    if (verify.isBlank()) return
    val decoded = decodeRawUrlSafeBase64(verify)
    if (decoded?.size != RealityMldsa65VerifyBytes) {
        proxyValidationError(ProxyServerValidationError.RealityMldsa65VerifyInvalid)
    }
}

private fun decodeRawUrlSafeBase64(value: String): ByteArray? {
    if (!RawUrlSafeBase64Regex.matches(value)) return null
    return runCatching {
        Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .decode(value)
    }.getOrNull()
}

private fun validateOptionalFingerprint(value: String?) {
    if (value.isNullOrBlank()) return
    validateAllowed(
        value,
        "TLS fingerprint",
        setOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"),
    )
}

private fun validateOptionalAlpn(value: String?) {
    if (value.isNullOrBlank()) return
    val allowed = setOf("h3", "h2", "http/1.1")
    value.split(",").forEach { item ->
        if (item.trim() !in allowed) {
            proxyValidationError(ProxyServerValidationError.UnsupportedAlpn, item.trim())
        }
    }
}

private fun isValidDomain(value: String): Boolean {
    if (value == "localhost") return true
    if (value.length > 253 || value.startsWith(".") || value.endsWith(".")) return false
    return value.split(".").all { label ->
        label.length in 1..63 && DomainLabelRegex.matches(label)
    }
}

private fun isValidIpv4(value: String): Boolean {
    val parts = value.split(".")
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() &&
            part.all { it.isDigit() } &&
            part.toIntOrNull()?.let { it in NetworkLimits.IPV4_OCTET_MIN..NetworkLimits.IPV4_OCTET_MAX } == true
    }
}

private fun isValidIpv6(value: String): Boolean {
    if (!value.contains(":") || value.count { it == ':' } > 7 || value.contains(":::")) return false
    val halves = value.split("::")
    if (halves.size > 2) return false
    val segments = halves.flatMap { half -> half.split(":").filter { it.isNotEmpty() } }
    if (segments.isEmpty()) return value == "::"
    if (halves.size == 1 && segments.size != 8) return false
    if (halves.size == 2 && segments.size >= 8) return false
    return segments.all { segment ->
        if (segment.contains(".")) {
            isValidIpv4(segment)
        } else {
            segment.length in 1..4 && HexRegex.matches(segment)
        }
    }
}
