// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.proxy.ProxyEngineStatus
import engine.root.runtime.model.RootRuntimeMode
import engine.root.runtime.model.RootRuntimeOwner
import engine.root.runtime.model.RootRuntimeSnapshot
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdOwner
import engine.root.daemon.control.AsteriskdControlResponse
import engine.root.daemon.control.AsteriskdPhase
import engine.root.daemon.control.AsteriskdResultCode
import engine.root.daemon.control.AsteriskdSnapshot
import system.ShellExecResult

internal fun ShellExecResult.controlResponseOrNull(): AsteriskdControlResponse? =
    runCatching { engine.root.daemon.control.AsteriskdControlCodec.decodeShellResponse(this) }.getOrNull()

internal fun AsteriskdControlResponse.boundSnapshot(): AsteriskdSnapshot? = when (result.code) {
    AsteriskdResultCode.Ok,
    AsteriskdResultCode.AlreadyRunning,
    AsteriskdResultCode.StopFailed,
    -> result.snapshot
    AsteriskdResultCode.NotRunning -> null
    else -> error(result.message ?: "asteriskd control request failed")
}

internal fun AsteriskdSnapshot.requireOwner(owner: AsteriskdOwner) {
    if (this.owner != owner) throw RootRuntimeConflictException(this)
}

internal fun AsteriskdSnapshot.rejectBound(owner: AsteriskdOwner): Nothing {
    requireOwner(owner)
    throw RootRuntimeBusyException(this)
}

internal fun AsteriskdSnapshot.requireRunning(owner: AsteriskdOwner, expectedMode: AsteriskdMode) {
    requireOwner(owner)
    check(phase == AsteriskdPhase.Running && mode == expectedMode)
}

internal enum class RootOrdinaryStartDisposition(
    val shutdownBeforeLaunch: Boolean,
) {
    Reuse(shutdownBeforeLaunch = false),
    Relaunch(shutdownBeforeLaunch = true),
}

internal fun AsteriskdSnapshot.ordinaryStartDisposition(
    owner: AsteriskdOwner,
    expectedMode: AsteriskdMode,
): RootOrdinaryStartDisposition {
    requireOwner(owner)
    if (phase == AsteriskdPhase.Running && mode == expectedMode) {
        return RootOrdinaryStartDisposition.Reuse
    }
    if (phase == AsteriskdPhase.Stopped) {
        return RootOrdinaryStartDisposition.Relaunch
    }
    rejectBound(owner)
}

internal fun AsteriskdControlResponse.preflightStart(
    owner: AsteriskdOwner,
    expectedMode: AsteriskdMode,
    explicitRestart: Boolean,
): AsteriskdSnapshot? {
    val snapshot = boundSnapshot() ?: return null
    snapshot.requireOwner(owner)
    if (explicitRestart) return null
    return when (snapshot.ordinaryStartDisposition(owner, expectedMode)) {
        RootOrdinaryStartDisposition.Reuse -> snapshot
        RootOrdinaryStartDisposition.Relaunch -> null
    }
}

internal fun AsteriskdSnapshot.toProxyEngineStatus(
    runMode: Int,
    expectedMode: AsteriskdMode,
): ProxyEngineStatus {
    val rootSnapshot = RootRuntimeSnapshot(
        owner = RootRuntimeOwner.entries.single { candidate -> candidate.wireValue == owner.wireValue },
        mode = RootRuntimeMode.entries.single { candidate -> candidate.wireValue == mode.wireValue },
        running = phase == AsteriskdPhase.Running,
    )
    return ProxyEngineStatus.fromRootSnapshot(
        localOwner = RootRuntimeOwner.AsteriskNg,
        runMode = runMode,
        snapshot = rootSnapshot,
    ).copy(running = owner == AsteriskdOwner.AsteriskNg && phase == AsteriskdPhase.Running && mode == expectedMode)
}

internal fun AsteriskdSnapshot.toStableProxyEngineStatus(
    runMode: Int,
    expectedMode: AsteriskdMode,
): ProxyEngineStatus? = when (phase) {
    AsteriskdPhase.Running,
    AsteriskdPhase.Stopped,
    AsteriskdPhase.Failed,
    -> toProxyEngineStatus(runMode, expectedMode)
    else -> null
}
