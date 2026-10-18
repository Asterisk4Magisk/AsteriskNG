// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import engine.network.NetworkLimits
import engine.network.isPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import utils.decodeUrlSafeBase64NoPaddingOrNull
import utils.decodeUrlSafeBase64OptionalPaddingOrNull
import utils.toIntInRangeOrNull

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

internal fun MutableList<ProxyServerValidationIssue>.addIssue(
    error: ProxyServerValidationError,
    vararg values: Any?,
) {
    add(proxyValidationIssue(error, *values))
}

internal fun MutableList<ProxyServerValidationIssue>.validateRequired(
    value: String?,
    fieldName: String,
): Boolean {
    if (!value.isNullOrBlank()) return true
    addIssue(ProxyServerValidationError.RequiredField, fieldName)
    return false
}

internal fun MutableList<ProxyServerValidationIssue>.validateRemarks(remarks: String) {
    validateRequired(remarks, "remarks")
}

internal fun MutableList<ProxyServerValidationIssue>.validateServer(server: String) {
    val value = server.trim()
    if (!validateRequired(value, "server address")) return
    if (
        value.contains("://") ||
        value.any { it.isWhitespace() } ||
        value.any { it == '/' || it == '?' || it == '#' || it == '@' }
    ) {
        addIssue(ProxyServerValidationError.ServerAddressContainsInvalidContent)
        return
    }

    val host = if (value.startsWith("[") && value.endsWith("]")) {
        value.substring(1, value.length - 1)
    } else {
        value
    }
    if (!isValidIpv4(host) && !isValidIpv6(host) && !isValidDomain(host)) {
        addIssue(ProxyServerValidationError.InvalidServerAddress)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validatePort(
    port: String,
    fieldName: String = "server port",
) {
    val value = port.trim()
    if (!validateRequired(value, fieldName)) return
    val number = value.toIntOrNull()
    if (number == null) {
        addIssue(ProxyServerValidationError.NumberRequired, fieldName)
        return
    }
    if (!number.isPort()) {
        addIssue(
            ProxyServerValidationError.PortOutOfRange,
            NetworkLimits.PORT_MIN,
            NetworkLimits.PORT_MAX,
        )
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateAllowed(
    value: String,
    fieldName: String,
    allowed: Set<String>,
    allowBlank: Boolean = false,
) {
    val normalized = value.trim()
    if (normalized.isBlank()) {
        if (!allowBlank) {
            addIssue(ProxyServerValidationError.RequiredField, fieldName)
        }
        return
    }
    if (normalized !in allowed) {
        addIssue(ProxyServerValidationError.UnsupportedValue, normalized)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateCommonServerFields(
    remarks: String,
    server: String,
    port: String,
) {
    validateRemarks(remarks)
    validateServer(server)
    validatePort(port)
}

internal fun MutableList<ProxyServerValidationIssue>.validateOptionalUserPassword(
    user: String?,
    password: String?,
) {
    if (user.isNullOrBlank() && !password.isNullOrBlank()) {
        addIssue(ProxyServerValidationError.UsernameRequiredForPassword)
    }
    if (!user.isNullOrBlank() && password.isNullOrBlank()) {
        addIssue(ProxyServerValidationError.PasswordRequiredForUsername)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateV2RayParameters(params: V2RayParameters) {
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

        "ws", "websocket", "httpupgrade", "xhttp", "splithttp" -> validateOptionalPath(params.path, "$type path")
        "grpc" -> validateAllowed(params.mode ?: "gun", "gRPC mode", setOf("gun", "multi"))
    }
    if (type in setOf("ws", "websocket", "httpupgrade")) {
        validateOptionalJsonObject(params.headers, "$type headers")
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
                addIssue(ProxyServerValidationError.RealityTransportUnsupported)
            }
            validateRealityPublicKey(params.pbk.orEmpty())
            validateRequired(params.fp, "TLS fingerprint")
            validateOptionalFingerprint(params.fp)
            validateOptionalRealityMldsa65Verify(params.pqv)
            validateOptionalPath(params.spx, "SpiderX")
            val shortId = params.sid.orEmpty()
            if (shortId.isNotBlank() && (shortId.length > 16 || shortId.length % 2 != 0 || !HexRegex.matches(shortId))) {
                addIssue(ProxyServerValidationError.RealityShortIdInvalid)
            }
        }
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateWireguardKey(
    value: String,
    fieldName: String,
    required: Boolean = true,
) {
    if (value.isBlank()) {
        if (required) {
            addIssue(ProxyServerValidationError.RequiredField, fieldName)
        }
        return
    }
    if (!WireguardKeyRegex.matches(value.trim())) {
        addIssue(ProxyServerValidationError.WireguardKeyInvalid, fieldName)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateWireguardReserved(reserved: String) {
    if (reserved.isBlank()) return
    val parts = reserved.split(",")
    if (parts.size != 3) {
        addIssue(ProxyServerValidationError.WireguardReservedCountInvalid)
        return
    }
    parts.forEach { part ->
        if (part.toIntInRangeOrNull(NetworkLimits.IPV4_OCTET_MIN..NetworkLimits.IPV4_OCTET_MAX) == null) {
            addIssue(
                ProxyServerValidationError.WireguardReservedValueInvalid,
                NetworkLimits.IPV4_OCTET_MIN,
                NetworkLimits.IPV4_OCTET_MAX,
            )
        }
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateWireguardAddresses(addresses: String) {
    if (addresses.isBlank()) return
    addresses.split(",").forEach { address ->
        val value = address.trim()
        val slashIndex = value.lastIndexOf('/')
        if (value.isBlank() || slashIndex <= 0 || slashIndex == value.lastIndex) {
            addIssue(ProxyServerValidationError.LocalAddressCidrRequired)
            return@forEach
        }
        val host = value.substring(0, slashIndex)
        val prefix = value.substring(slashIndex + 1).toIntOrNull()
        if (prefix == null) {
            addIssue(ProxyServerValidationError.LocalAddressPrefixNumberRequired)
            return@forEach
        }
        val validPrefix = when {
            isValidIpv4(host) -> prefix in 0..32
            isValidIpv6(host) -> prefix in 0..128
            else -> false
        }
        if (!validPrefix) {
            addIssue(ProxyServerValidationError.InvalidLocalAddressCidr)
        }
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateMtu(mtu: String) {
    if (mtu.isBlank()) return
    if (mtu.toIntOrNull() == null) {
        addIssue(ProxyServerValidationError.MtuNumberRequired)
        return
    }
    if (mtu.toIntInRangeOrNull(WireguardMtuMin..WireguardMtuMax) == null) {
        addIssue(ProxyServerValidationError.MtuOutOfRange, WireguardMtuMin, WireguardMtuMax)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateHysteriaMultiPorts(mport: String) {
    if (mport.isBlank()) return
    mport.split(",").forEach { part ->
        val range = part.trim().split("-")
        if (range.size !in 1..2) {
            addIssue(ProxyServerValidationError.InvalidPortHoppingFormat)
            return@forEach
        }
        val before = size
        validatePort(range[0], "port hopping")
        if (before != size) return@forEach
        val start = range[0].trim().toInt()
        val end = if (range.size == 2) {
            val beforeEnd = size
            validatePort(range[1], "port hopping")
            if (beforeEnd != size) return@forEach
            range[1].trim().toInt()
        } else {
            start
        }
        if (start > end) {
            addIssue(ProxyServerValidationError.PortHoppingRangeInvalid)
        }
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateOptionalBandwidth(value: String, fieldName: String) {
    if (value.isBlank()) return
    if (!HysteriaBandwidthRegex.matches(value.trim())) {
        addIssue(ProxyServerValidationError.InvalidBandwidthFormat, fieldName)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateOptionalSha256(value: String?, fieldName: String) {
    val hash = value.orEmpty()
    if (hash.isBlank()) return
    hash.split(",").forEach { item ->
        val normalized = item.trim().replace(":", "")
        if (normalized.length != 64 || !HexRegex.matches(normalized)) {
            addIssue(ProxyServerValidationError.Sha256Invalid, fieldName)
        }
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateOptionalJsonObject(value: String?, fieldName: String) {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return
    val jsonObject = runCatching {
        Json.parseToJsonElement(text)
    }.getOrNull() as? JsonObject
    if (jsonObject == null) {
        addIssue(ProxyServerValidationError.JsonObjectRequired, fieldName)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateRealityPublicKey(value: String) {
    val key = value.trim()
    if (!validateRequired(key, "Reality PublicKey")) return
    val decoded = if (UrlSafeBase64Regex.matches(key)) {
        key.decodeUrlSafeBase64OptionalPaddingOrNull()
    } else {
        null
    }
    if (decoded?.size != RealityPublicKeyBytes) {
        addIssue(ProxyServerValidationError.RealityPublicKeyInvalid)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateOptionalXrayUserId(value: String) {
    val normalized = value.trim()
    if (normalized.isBlank()) return
    if (UuidRegex.matches(normalized)) return
    if (normalized.encodeToByteArray().size >= XrayUserIdMaxBytes) {
        addIssue(ProxyServerValidationError.XrayUserIdInvalid, XrayUserIdMaxBytes)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateVlessEncryption(value: String) {
    val encryption = value.ifBlank { "none" }
    if (encryption == "none") return

    val blocks = encryption.split(".")
    if (blocks.size < 4) {
        addIssue(ProxyServerValidationError.InvalidVlessEncryption)
        return
    }
    validateAllowed(blocks[0], "VLESS handshake", setOf("mlkem768x25519plus"))
    validateAllowed(blocks[1], "VLESS encryption method", setOf("native", "xorpub", "random"))
    validateAllowed(blocks[2], "VLESS session mode", setOf("0rtt", "1rtt"))

    val paddingBlocks = blocks.drop(3).dropLast(1)
    if (paddingBlocks.isNotEmpty()) {
        if (paddingBlocks.size % 2 == 0) {
            addIssue(ProxyServerValidationError.VlessPaddingBoundaryInvalid)
        }
        paddingBlocks.forEachIndexed { index, block ->
            validateVlessPaddingBlock(block, if (index % 2 == 0) "padding" else "delay", firstPadding = index == 0)
        }
    }
    validateVlessEncryptionVerifier(blocks.last())
}

internal fun MutableList<ProxyServerValidationIssue>.validateOptionalPositivePortInterval(value: String) {
    if (value.isBlank()) return
    val seconds = value.toIntOrNull()
    if (seconds == null) {
        addIssue(ProxyServerValidationError.PortHoppingIntervalNumberRequired)
        return
    }
    if (seconds < 5) {
        addIssue(ProxyServerValidationError.PortHoppingIntervalTooSmall)
    }
}

private fun MutableList<ProxyServerValidationIssue>.validateVlessEncryptionVerifier(value: String) {
    val verifier = value.trim()
    if (!validateRequired(verifier, "VLESS encryption verifier")) return
    if (decodeRawUrlSafeBase64(verifier) == null) {
        addIssue(ProxyServerValidationError.VlessEncryptionVerifierInvalid)
    }
}

private fun MutableList<ProxyServerValidationIssue>.validateVlessPaddingBlock(
    block: String,
    fieldName: String,
    firstPadding: Boolean,
) {
    val parts = block.split("-")
    if (parts.size != 3) {
        addIssue(ProxyServerValidationError.VlessPaddingFormatInvalid)
        return
    }
    val probability = parts[0].toIntInRangeOrNull(0..100)
    val min = parts[1].toIntInRangeOrNull(0..Int.MAX_VALUE)
    val max = parts[2].toIntOrNull()
    if (probability == null || min == null || max == null || max < min) {
        addIssue(ProxyServerValidationError.VlessPaddingRangeInvalid)
        return
    }
    if (firstPadding && (probability != 100 || min <= 0)) {
        addIssue(ProxyServerValidationError.VlessFirstPaddingInvalid)
    }
}

private fun MutableList<ProxyServerValidationIssue>.validateOptionalIntRange(
    value: String?,
    fieldName: String,
    min: Int,
    max: Int,
) {
    if (value.isNullOrBlank()) return
    if (value.toIntOrNull() == null) {
        addIssue(ProxyServerValidationError.NumberRequired, fieldName)
        return
    }
    if (value.toIntInRangeOrNull(min..max) == null) {
        addIssue(ProxyServerValidationError.ValueOutOfRange, min, max)
    }
}

internal fun MutableList<ProxyServerValidationIssue>.validateOptionalPath(value: String?, fieldName: String) {
    val path = value.orEmpty()
    if (path.isNotBlank() && !path.startsWith("/")) {
        addIssue(ProxyServerValidationError.PathMustStartWithSlash)
    }
}

private fun MutableList<ProxyServerValidationIssue>.validateOptionalRealityMldsa65Verify(value: String?) {
    val verify = value?.trim().orEmpty()
    if (verify.isBlank()) return
    val decoded = decodeRawUrlSafeBase64(verify)
    if (decoded?.size != RealityMldsa65VerifyBytes) {
        addIssue(ProxyServerValidationError.RealityMldsa65VerifyInvalid)
    }
}

private fun decodeRawUrlSafeBase64(value: String): ByteArray? {
    if (!RawUrlSafeBase64Regex.matches(value)) return null
    return value.decodeUrlSafeBase64NoPaddingOrNull()
}

internal fun MutableList<ProxyServerValidationIssue>.validateOptionalFingerprint(value: String?) {
    if (value.isNullOrBlank()) return
    validateAllowed(
        value,
        "TLS fingerprint",
        setOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"),
    )
}

private fun MutableList<ProxyServerValidationIssue>.validateOptionalAlpn(value: String?) {
    if (value.isNullOrBlank()) return
    val allowed = setOf("h3", "h2", "http/1.1")
    value.split(",").forEach { item ->
        val normalized = item.trim()
        if (normalized !in allowed) {
            addIssue(ProxyServerValidationError.UnsupportedAlpn, normalized)
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
