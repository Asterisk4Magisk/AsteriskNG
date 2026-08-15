// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.proxy.ProxyEngineStatus
import engine.root.runtime.model.RootRuntimeOwner

enum class RootRequestedAction(val wireValue: String) {
    OrdinaryStart("ordinary_start"),
    RestartSameOwner("restart_same_owner"),
    StopOwn("stop_own"),
    Toggle("toggle"),
    BootRefresh("boot_refresh"),
}

enum class RootFailureKind(val wireValue: String) {
    StartFailure("start_failure"),
    InternalFailure("internal_failure"),
}

sealed interface RootOperationResult {
    data class ForeignOwnerConflict(
        val owner: RootRuntimeOwner,
    ) : RootOperationResult

    data class Busy(val owner: RootRuntimeOwner?) : RootOperationResult

    data class Failure(val kind: RootFailureKind) : RootOperationResult
}

data class RootOperationLogRecord(
    val code: String,
    val action: RootRequestedAction,
    val owner: RootRuntimeOwner?,
) {
    fun asLogMessage(): String =
        "root_result code=$code action=${action.wireValue} owner=${owner?.wireValue ?: "none"}"
}

class RootOperationBlockedException : IllegalStateException()

fun RootOperationResult.toSanitizedLogRecord(
    requestedAction: RootRequestedAction,
): RootOperationLogRecord = when (this) {
    is RootOperationResult.ForeignOwnerConflict -> RootOperationLogRecord(
        code = "foreign_owner_conflict",
        action = requestedAction,
        owner = owner,
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
): String = toSanitizedLogRecord(requestedAction).asLogMessage()

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
            RootOperationResult.ForeignOwnerConflict(snapshot.owner),
        )
    }
    return if (snapshot.running) {
        RootToggleDecision.StopOwn
    } else {
        RootToggleDecision.Blocked(RootOperationResult.Busy(snapshot.owner))
    }
}

fun classifyForeignRootConflict(
    localOwner: RootRuntimeOwner,
    status: ProxyEngineStatus,
): RootOperationResult.ForeignOwnerConflict? {
    val owner = status.rootSnapshot?.owner ?: return null
    if (owner == localOwner) return null
    return RootOperationResult.ForeignOwnerConflict(owner)
}
