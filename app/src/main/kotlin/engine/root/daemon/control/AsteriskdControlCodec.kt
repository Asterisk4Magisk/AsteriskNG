// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon.control

import engine.root.daemon.config.AsteriskdCoreType
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdOwner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import system.ShellExecResult

internal object AsteriskdControlCodec {
    fun decodeResponse(payload: String): AsteriskdControlResponse {
        val root = parseClosedPayload(payload)
        root.requireExactKeys("protocolVersion", "requestId", "result")
        require(root.requiredInt("protocolVersion") == ProtocolVersion)
        val requestId = root.requiredString("requestId")
        require(RequestIdRegex.matches(requestId))
        val resultObject = root.requiredObject("result")
        resultObject.requireExactKeys("code", "snapshot", "message")
        val code = enumWire<AsteriskdResultCode>(resultObject.requiredString("code"))
        val snapshot = resultObject["snapshot"].toNullableObject()?.toSnapshot()
        val message = resultObject["message"].toNullableString()
        validateResultNullability(code, snapshot, message)
        return AsteriskdControlResponse(requestId, AsteriskdControlResult(code, snapshot, message))
    }

    fun decodeShellResponse(
        expectedRequestId: String,
        result: ShellExecResult,
    ): AsteriskdControlResponse {
        val response = decodeShellResponse(result)
        require(response.requestId == expectedRequestId)
        return response
    }

    fun decodeShellResponse(result: ShellExecResult): AsteriskdControlResponse {
        require('\n' !in result.stdout && '\r' !in result.stdout)
        val response = decodeResponse(result.stdout)
        val expectedExitCode = response.result.code.exitCode
        require(
            result.errno == expectedExitCode ||
                (result.errno == NormalizedNonZeroExitCode && expectedExitCode != 0),
        ) { "Shell exit ${result.errno} does not match protocol exit $expectedExitCode" }
        return response
    }

    fun decodeRecoveryResponse(payload: String): AsteriskdRecoveryResult {
        val root = parseClosedPayload(payload)
        root.requireExactKeys("protocolVersion", "requestId", "recoveryResult")
        require(root.requiredInt("protocolVersion") == ProtocolVersion)
        require(root.requiredString("requestId") == "recover")
        val value = root.requiredObject("recoveryResult")
        value.requireExactKeys(
            "code", "owner", "coreType", "mode", "coreOwnedEbpfBoundary", "message",
        )
        val owner = value.getValue("owner").toNullableString()?.let { wire ->
            enumWire<AsteriskdOwner>(wire)
        }
        val coreType = value.getValue("coreType").toNullableString()?.let { wire ->
            enumWire<AsteriskdCoreType>(wire)
        }
        val mode = value.getValue("mode").toNullableString()?.let(AsteriskdMode::fromWire)
        require(listOf(owner, coreType, mode).all { it == null } || listOf(owner, coreType, mode).all { it != null })
        if (owner != null) requireOwnerCore(owner, requireNotNull(coreType))
        val result = AsteriskdRecoveryResult(
            code = enumWire(value.requiredString("code")),
            owner = owner,
            coreType = coreType,
            mode = mode,
            coreOwnedEbpfBoundary = value.getValue("coreOwnedEbpfBoundary").toNullableBoolean(),
            message = value.getValue("message").toNullableString(),
        )
        result.validate()
        return result
    }

    fun decodeEvent(payload: String): AsteriskdControlEvent {
        val root = parseClosedPayload(payload)
        root.requireExactKeys("protocolVersion", "event")
        require(root.requiredInt("protocolVersion") == ProtocolVersion)
        val value = root.requiredObject("event")
        value.requireExactKeys("sequence", "type", "snapshot", "details")
        val event = AsteriskdControlEvent(
            sequence = value.requiredLong("sequence"),
            type = enumWire(value.requiredString("type")),
            snapshot = value.requiredObject("snapshot").toSnapshot(),
            details = value.getValue("details").toNullableObject()?.toControlError(),
        )
        require(event.sequence > 0)
        return event
    }

    private fun JsonObject.toSnapshot(): AsteriskdSnapshot {
        requireExactKeys(
            "phase", "owner", "coreType", "mode", "supervisorPid", "corePid",
            "helperType", "helperPid", "matcherConfigured", "matcherActive", "rules",
            "network", "error",
        )
        val rulesObject = requiredObject("rules")
        rulesObject.requireExactKeys("active", "generation", "categories")
        val categories = rulesObject.requiredArray("categories").map { element ->
            enumWire<AsteriskdRuleCategory>(element.requiredStringValue())
        }
        require(categories.distinct() == categories && categories == categories.sortedBy { it.ordinal })
        val rules = AsteriskdRulesSnapshot(
            active = rulesObject.requiredBoolean("active"),
            generation = rulesObject.requiredLong("generation"),
            categories = categories,
        )
        require(if (rules.active) rules.generation > 0 && rules.categories.isNotEmpty() else rules.generation == 0L && rules.categories.isEmpty())

        val networkObject = requiredObject("network")
        networkObject.requireExactKeys("ipv4Ready", "ipv6Enabled", "ipv6Ready")
        val network = AsteriskdNetworkSnapshot(
            ipv4Ready = networkObject.requiredBoolean("ipv4Ready"),
            ipv6Enabled = networkObject.requiredBoolean("ipv6Enabled"),
            ipv6Ready = networkObject.requiredBoolean("ipv6Ready"),
        )
        val snapshot = AsteriskdSnapshot(
            phase = enumWire(requiredString("phase")),
            owner = enumWire(requiredString("owner")),
            coreType = enumWire(requiredString("coreType")),
            mode = AsteriskdMode.fromWire(requiredString("mode")),
            supervisorPid = requiredInt("supervisorPid"),
            corePid = getValue("corePid").toNullableInt(),
            helperType = getValue("helperType").toNullableString()?.let(::enumWire),
            helperPid = getValue("helperPid").toNullableInt(),
            matcherConfigured = requiredBoolean("matcherConfigured"),
            matcherActive = requiredBoolean("matcherActive"),
            rules = rules,
            network = network,
            error = getValue("error").toNullableObject()?.toControlError(),
        )
        snapshot.validate()
        return snapshot
    }
}

internal val AsteriskdResultCode.exitCode: Int
    get() = when (this) {
        AsteriskdResultCode.Ok -> 0
        AsteriskdResultCode.AlreadyRunning -> 4
        AsteriskdResultCode.NotRunning -> 3
        AsteriskdResultCode.PermissionDenied -> 77
        AsteriskdResultCode.InvalidRequest,
        AsteriskdResultCode.ConfigInvalid,
        AsteriskdResultCode.UnsupportedCombination,
        -> 64
        AsteriskdResultCode.StartFailed,
        AsteriskdResultCode.StopFailed,
        AsteriskdResultCode.InternalError,
        -> 1
    }

private fun AsteriskdRecoveryResult.validate() {
    val hasIdentity = owner != null
    val hasBoundary = coreOwnedEbpfBoundary != null
    val hasMessage = !message.isNullOrEmpty()
    require(message == null || hasMessage)
    when (code) {
        AsteriskdRecoveryCode.Clean -> require(hasBoundary && coreOwnedEbpfBoundary == false && !hasMessage)
        AsteriskdRecoveryCode.Recovered -> require(hasIdentity && hasBoundary && coreOwnedEbpfBoundary == false && !hasMessage)
        AsteriskdRecoveryCode.RecoveryRequired -> require(hasMessage && hasIdentity == hasBoundary)
        AsteriskdRecoveryCode.AlreadyRunning -> require(hasIdentity && !hasBoundary && hasMessage)
        AsteriskdRecoveryCode.PermissionDenied,
        AsteriskdRecoveryCode.InternalError,
        -> require(!hasIdentity && !hasBoundary && hasMessage)
    }
}

private fun AsteriskdSnapshot.validate() {
    require(supervisorPid > 0 && (corePid == null || corePid > 0) && (helperPid == null || helperPid > 0))
    requireOwnerCore(owner, coreType)
    val expectedHelper = when (mode) {
        AsteriskdMode.Tun2Socks -> AsteriskdHelperType.HevSocks5Tunnel
        AsteriskdMode.Bpf2Socks -> AsteriskdHelperType.Bpf2Socks
        AsteriskdMode.Tproxy, AsteriskdMode.Tun, AsteriskdMode.Ebpf -> null
    }
    require(helperType == expectedHelper)
    require(helperType != null || helperPid == null)
    require(!matcherActive || matcherConfigured)
    if (mode == AsteriskdMode.Ebpf) require(!rules.active && rules.generation == 0L && rules.categories.isEmpty())
    if (phase == AsteriskdPhase.Running) {
        require(network.ipv4Ready && network.ipv6Ready && corePid != null && error == null)
        require(helperType == null || helperPid != null)
        require(!matcherConfigured || matcherActive)
        require(mode == AsteriskdMode.Ebpf || rules.active)
    } else {
        require(!network.ipv4Ready && !network.ipv6Ready)
    }
    require(phase != AsteriskdPhase.Failed || error != null)
}

private fun requireOwnerCore(
    owner: AsteriskdOwner,
    coreType: AsteriskdCoreType,
) {
    require(
        (owner == AsteriskdOwner.AsteriskNg && coreType == AsteriskdCoreType.Xray) ||
            (owner == AsteriskdOwner.AsteriskBox && coreType == AsteriskdCoreType.SingBox) ||
            (owner == AsteriskdOwner.AsteriskMeta && coreType == AsteriskdCoreType.Mihomo),
    )
}

private fun JsonObject.toControlError(): AsteriskdControlError {
    requireExactKeys("code", "component", "message", "exitCode", "signal")
    val error = AsteriskdControlError(
        code = enumWire(requiredString("code")),
        component = enumWire(requiredString("component")),
        message = requiredString("message"),
        exitCode = getValue("exitCode").toNullableInt(),
        signal = getValue("signal").toNullableInt(),
    )
    require(error.message.isNotEmpty())
    if (error.code == AsteriskdFailureCode.ChildExited) {
        require((error.exitCode != null) xor (error.signal != null))
    } else {
        require(error.exitCode == null && error.signal == null)
    }
    return error
}

private fun validateResultNullability(code: AsteriskdResultCode, snapshot: AsteriskdSnapshot?, message: String?) {
    when (code) {
        AsteriskdResultCode.Ok -> require(snapshot != null && message == null)
        AsteriskdResultCode.AlreadyRunning -> require(snapshot != null && !message.isNullOrEmpty())
        AsteriskdResultCode.StopFailed -> require(snapshot?.phase == AsteriskdPhase.Failed && !message.isNullOrEmpty())
        AsteriskdResultCode.InternalError -> require(!message.isNullOrEmpty())
        AsteriskdResultCode.NotRunning,
        AsteriskdResultCode.PermissionDenied,
        AsteriskdResultCode.InvalidRequest,
        AsteriskdResultCode.ConfigInvalid,
        AsteriskdResultCode.UnsupportedCombination,
        AsteriskdResultCode.StartFailed,
        -> require(snapshot == null && !message.isNullOrEmpty())
    }
}

private fun JsonObject.requireExactKeys(vararg keys: String) {
    require(this.keys == keys.toSet())
}

private fun JsonObject.requiredString(key: String): String = getValue(key).requiredStringValue()

private fun JsonElement.requiredStringValue(): String {
    require(this is JsonPrimitive && isString)
    return content
}

private fun JsonObject.requiredInt(key: String): Int = getValue(key).jsonPrimitive.content.toInt()
private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive.content.toLong()
private fun JsonObject.requiredBoolean(key: String): Boolean =
    requireNotNull(getValue(key).jsonPrimitive.booleanOrNull)
private fun JsonObject.requiredObject(key: String): JsonObject = getValue(key).jsonObject
private fun JsonObject.requiredArray(key: String): JsonArray = getValue(key).jsonArray

private fun JsonElement?.toNullableObject(): JsonObject? = when (this) {
    null, JsonNull -> null
    else -> jsonObject
}

private fun JsonElement?.toNullableString(): String? = when (this) {
    null, JsonNull -> null
    else -> jsonPrimitive.contentOrNull.also { require(jsonPrimitive.isString) }
}

private fun JsonElement.toNullableInt(): Int? = if (this === JsonNull) null else jsonPrimitive.content.toInt()
private fun JsonElement.toNullableBoolean(): Boolean? = if (this === JsonNull) {
    null
} else {
    requireNotNull(jsonPrimitive.booleanOrNull)
}

private inline fun <reified T : Enum<T>> enumWire(value: String): T = enumValues<T>().firstOrNull { entry ->
    val wire = when (entry) {
        is AsteriskdOwner -> entry.wireValue
        is AsteriskdCoreType -> entry.wireValue
        is AsteriskdPhase -> entry.wireValue
        is AsteriskdResultCode -> entry.wireValue
        is AsteriskdRecoveryCode -> entry.wireValue
        is AsteriskdEventType -> entry.wireValue
        is AsteriskdHelperType -> entry.wireValue
        is AsteriskdRuleCategory -> entry.wireValue
        is AsteriskdFailureCode -> entry.wireValue
        is AsteriskdComponent -> entry.wireValue
        else -> error("Unsupported wire enum")
    }
    wire == value
} ?: throw IllegalArgumentException("Unknown protocol token")

private val RequestIdRegex = Regex("[A-Za-z0-9._-]{1,64}")
private const val ProtocolVersion = 1
private const val NormalizedNonZeroExitCode = 1
