// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon.control

import engine.root.daemon.config.AsteriskdCoreType
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdOwner

internal enum class AsteriskdPhase(val wireValue: String) {
    Validating("validating"), Acquiring("acquiring"), Recovering("recovering"),
    Starting("starting"), ApplyingRules("applying-rules"), Running("running"),
    Stopping("stopping"), Stopped("stopped"), Failed("failed"),
}

internal enum class AsteriskdResultCode(val wireValue: String) {
    Ok("ok"), AlreadyRunning("already_running"), NotRunning("not_running"),
    PermissionDenied("permission_denied"), InvalidRequest("invalid_request"),
    ConfigInvalid("config_invalid"), UnsupportedCombination("unsupported_combination"),
    StartFailed("start_failed"), StopFailed("stop_failed"), InternalError("internal_error"),
}

internal enum class AsteriskdRecoveryCode(val wireValue: String) {
    Clean("clean"), Recovered("recovered"), RecoveryRequired("recovery_required"),
    AlreadyRunning("already_running"), PermissionDenied("permission_denied"), InternalError("internal_error"),
}

internal enum class AsteriskdHelperType(val wireValue: String) {
    HevSocks5Tunnel("hev-socks5-tunnel"), Bpf2Socks("bpf2socks"),
}

internal enum class AsteriskdEventType(val wireValue: String) {
    Starting("starting"), Running("running"), RulesChanged("rules-changed"),
    Stopping("stopping"), Stopped("stopped"), CoreExited("core-exited"),
    HelperFailed("helper-failed"), Failed("failed"),
}

internal enum class AsteriskdRuleCategory(val wireValue: String) {
    Tproxy("tproxy"), Routing("routing"), Dns("dns"), FakeDns("fake-dns"),
    LocalBypass("local-bypass"), Hotspot("hotspot"), Tc("tc"), Bpf("bpf"), Ipv6Guard("ipv6-guard"),
}

internal enum class AsteriskdFailureCode(val wireValue: String) {
    StartFailed("start_failed"), ReadinessTimeout("readiness_timeout"), ChildExited("child_exited"),
    StateInvalid("state_invalid"), StateIncompatible("state_incompatible"),
    ResourceCollision("resource_collision"), IoError("io_error"),
    StopFailed("stop_failed"), InternalError("internal_error"),
}

internal enum class AsteriskdComponent(val wireValue: String) {
    Runtime("runtime"), Core("core"), Helper("helper"), Matcher("matcher"),
    Rules("rules"), Network("network"), State("state"), Log("log"), Control("control"),
}

internal data class AsteriskdControlError(
    val code: AsteriskdFailureCode,
    val component: AsteriskdComponent,
    val message: String,
    val exitCode: Int?,
    val signal: Int?,
)

internal data class AsteriskdRulesSnapshot(
    val active: Boolean,
    val generation: Long,
    val categories: List<AsteriskdRuleCategory>,
)

internal data class AsteriskdNetworkSnapshot(
    val ipv4Ready: Boolean,
    val ipv6Enabled: Boolean,
    val ipv6Ready: Boolean,
)

internal data class AsteriskdSnapshot(
    val phase: AsteriskdPhase,
    val owner: AsteriskdOwner,
    val coreType: AsteriskdCoreType,
    val mode: AsteriskdMode,
    val supervisorPid: Int,
    val corePid: Int?,
    val helperType: AsteriskdHelperType?,
    val helperPid: Int?,
    val matcherConfigured: Boolean,
    val matcherActive: Boolean,
    val rules: AsteriskdRulesSnapshot,
    val network: AsteriskdNetworkSnapshot,
    val error: AsteriskdControlError?,
)

internal data class AsteriskdControlResult(
    val code: AsteriskdResultCode,
    val snapshot: AsteriskdSnapshot?,
    val message: String?,
)

internal data class AsteriskdControlResponse(
    val requestId: String,
    val result: AsteriskdControlResult,
)

internal data class AsteriskdRecoveryResult(
    val code: AsteriskdRecoveryCode,
    val owner: AsteriskdOwner?,
    val coreType: AsteriskdCoreType?,
    val mode: AsteriskdMode?,
    val coreOwnedEbpfBoundary: Boolean?,
    val message: String?,
)

internal data class AsteriskdControlEvent(
    val sequence: Long,
    val type: AsteriskdEventType,
    val snapshot: AsteriskdSnapshot,
    val details: AsteriskdControlError?,
)
