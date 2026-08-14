// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.proxy.ProxyEngineStatus
import engine.root.runtime.model.RootRuntimeIdentity
import engine.root.runtime.model.RootRuntimeOwner
import engine.root.runtime.model.RootRuntimePhase
import engine.root.runtime.model.RootRuntimeSnapshot

enum class RootRequestedAction(val wireValue: String) {
    OrdinaryStart("ordinary_start"),
    RestartSameOwner("restart_same_owner"),
    StopOwn("stop_own"),
    Toggle("toggle"),
    BootRefresh("boot_refresh"),
}

enum class RootConflictStage(val wireValue: String) {
    InitialStatus("initial_status"),
    ConditionalStop("conditional_stop"),
    PublicationRecheck("publication_recheck"),
    PostRecovery("post_recovery"),
    Bind("bind"),
}

enum class RootFailureKind(val wireValue: String) {
    StatusUnavailable("status_unavailable"),
    ProtocolFailure("protocol_failure"),
    PermissionDenied("permission_denied"),
    ValidationFailure("validation_failure"),
    StartFailure("start_failure"),
    InternalFailure("internal_failure"),
}

sealed interface RootOperationResult {
    data class Success(
        val localRunning: Boolean,
        val snapshot: RootRuntimeSnapshot? = null,
    ) : RootOperationResult

    data class ForeignOwnerConflict(
        val owner: RootRuntimeOwner,
        val action: RootRequestedAction,
        val stage: RootConflictStage,
    ) : RootOperationResult

    data class RecoveryRequired(
        val identity: RootRuntimeIdentity?,
        val coreOwnedEbpfBoundary: Boolean?,
    ) : RootOperationResult

    data class StopFailed(val snapshot: RootRuntimeSnapshot) : RootOperationResult

    data class SocketReleaseTimeout(
        val expectedOwner: RootRuntimeOwner,
        val lastSnapshot: RootRuntimeSnapshot?,
    ) : RootOperationResult

    data class Busy(val owner: RootRuntimeOwner?) : RootOperationResult

    data class Failure(
        val kind: RootFailureKind,
        val cause: Throwable? = null,
    ) : RootOperationResult
}

data class RootOperationLogRecord(
    val code: String,
    val action: RootRequestedAction,
    val owner: RootRuntimeOwner?,
) {
    fun asLogMessage(): String =
        "root_result code=$code action=${action.wireValue} owner=${owner?.wireValue ?: "none"}"
}

class RootOperationBlockedException(
    val result: RootOperationResult,
) : IllegalStateException()

fun RootOperationResult.toSanitizedLogRecord(
    requestedAction: RootRequestedAction,
): RootOperationLogRecord? = when (this) {
    is RootOperationResult.Success -> null
    is RootOperationResult.ForeignOwnerConflict -> RootOperationLogRecord(
        code = "foreign_owner_conflict",
        action = action,
        owner = owner,
    )
    is RootOperationResult.RecoveryRequired -> RootOperationLogRecord(
        code = "recovery_required",
        action = requestedAction,
        owner = identity?.owner,
    )
    is RootOperationResult.StopFailed -> RootOperationLogRecord(
        code = "stop_failed",
        action = requestedAction,
        owner = snapshot.owner,
    )
    is RootOperationResult.SocketReleaseTimeout -> RootOperationLogRecord(
        code = "socket_release_timeout",
        action = requestedAction,
        owner = expectedOwner,
    )
    is RootOperationResult.Busy -> RootOperationLogRecord(
        code = "runtime_busy",
        action = requestedAction,
        owner = owner,
    )
    is RootOperationResult.Failure -> RootOperationLogRecord(
        code = kind.wireValue,
        action = requestedAction,
        owner = null,
    )
}

fun RootOperationResult.toAppLogMessage(
    requestedAction: RootRequestedAction,
): String? = toSanitizedLogRecord(requestedAction)?.asLogMessage()

sealed interface RootToggleDecision {
    data object OrdinaryStart : RootToggleDecision

    data object StopOwn : RootToggleDecision

    data class Blocked(val result: RootOperationResult) : RootToggleDecision
}

fun decideRootSafeToggle(
    localOwner: RootRuntimeOwner,
    status: ProxyEngineStatus,
): RootToggleDecision {
    val snapshot = status.rootSnapshot
    if (snapshot == null) {
        return if (status.running) RootToggleDecision.StopOwn else RootToggleDecision.OrdinaryStart
    }
    if (snapshot.owner != localOwner) {
        return RootToggleDecision.Blocked(
            RootOperationResult.ForeignOwnerConflict(
                owner = snapshot.owner,
                action = RootRequestedAction.Toggle,
                stage = RootConflictStage.InitialStatus,
            ),
        )
    }
    return if (snapshot.phase == RootRuntimePhase.Running) {
        RootToggleDecision.StopOwn
    } else {
        RootToggleDecision.Blocked(RootOperationResult.Busy(snapshot.owner))
    }
}

fun classifyForeignRootConflict(
    localOwner: RootRuntimeOwner,
    status: ProxyEngineStatus,
    action: RootRequestedAction,
    stage: RootConflictStage,
): RootOperationResult.ForeignOwnerConflict? {
    val owner = status.rootSnapshot?.owner ?: return null
    if (owner == localOwner) return null
    return RootOperationResult.ForeignOwnerConflict(
        owner = owner,
        action = action,
        stage = stage,
    )
}
