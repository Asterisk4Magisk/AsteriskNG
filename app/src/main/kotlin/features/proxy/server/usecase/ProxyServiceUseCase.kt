// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import app.AppState
import app.ProxyServerState
import engine.proxy.AndroidProxyEngine
import engine.proxy.ProxyEngineStartRequest
import engine.root.runtime.RootFailureKind
import engine.root.runtime.RootOperationBlockedException
import engine.root.runtime.RootOperationLogRecord
import engine.root.runtime.RootOperationResult
import engine.root.runtime.RootRequestedAction
import engine.root.runtime.RootToggleDecision
import engine.root.runtime.classifyForeignRootConflict
import engine.root.runtime.decideRootSafeToggle
import engine.root.runtime.toSanitizedLogRecord
import engine.root.runtime.model.RootRuntimeOwner
import engine.root.runtime.RootRuntimeBusyException
import engine.root.runtime.RootRuntimeConflictException
import features.logs.AndroidAppLogger
import kotlin.coroutines.cancellation.CancellationException

internal class ProxyServiceUseCase(
    private val proxyEngine: AndroidProxyEngine,
) {
    suspend fun toggle(
        state: AppState,
        selectedServer: ProxyServerState?,
    ): ProxyServiceResult {
        val live = try {
            proxyEngine.status(state.runMode, state)
        } catch (error: Throwable) {
            return error.toProxyServiceFailure(RootRequestedAction.Toggle)
        }
        return when (val decision = decideRootSafeToggle(LocalRootOwner, live)) {
            RootToggleDecision.OrdinaryStart -> start(state, selectedServer)
            RootToggleDecision.StopOwn -> stop(state.runMode)
            is RootToggleDecision.Blocked -> decision.result.toProxyServiceFailure(RootRequestedAction.Toggle)
        }
    }

    suspend fun restart(
        state: AppState,
        selectedServer: ProxyServerState?,
    ): ProxyServiceResult {
        val server = selectedServer ?: return ProxyServiceResult.MissingServer
        val live = try {
            proxyEngine.status(state.runMode, state)
        } catch (error: Throwable) {
            return error.toProxyServiceFailure(RootRequestedAction.RestartSameOwner)
        }
        classifyForeignRootConflict(
            LocalRootOwner,
            live,
        )?.let { conflict -> return conflict.toProxyServiceFailure(RootRequestedAction.RestartSameOwner) }
        return runCatching {
            proxyEngine.restart(ProxyEngineStartRequest(state, server))
        }.fold(
            onSuccess = { status -> ProxyServiceResult.Success(proxyRunning = status.running, appState = status.appState) },
            onFailure = { error -> error.toProxyServiceFailure(RootRequestedAction.RestartSameOwner) },
        )
    }

    private suspend fun start(
        state: AppState,
        selectedServer: ProxyServerState?,
    ): ProxyServiceResult {
        val server = selectedServer ?: return ProxyServiceResult.MissingServer
        return runCatching {
            proxyEngine.start(ProxyEngineStartRequest(state, server))
        }.fold(
            onSuccess = { status -> ProxyServiceResult.Success(proxyRunning = status.running, appState = status.appState) },
            onFailure = { error -> error.toProxyServiceFailure(RootRequestedAction.OrdinaryStart) },
        )
    }

    suspend fun stop(runMode: Int): ProxyServiceResult {
        val live = try {
            proxyEngine.status(runMode)
        } catch (error: Throwable) {
            return error.toProxyServiceFailure(RootRequestedAction.StopOwn)
        }
        classifyForeignRootConflict(
            LocalRootOwner,
            live,
        )?.let { conflict -> return conflict.toProxyServiceFailure(RootRequestedAction.StopOwn) }
        return runCatching { proxyEngine.stop(runMode) }.fold(
            onSuccess = { status -> ProxyServiceResult.Success(proxyRunning = status.running, appState = status.appState) },
            onFailure = { error -> error.toProxyServiceFailure(RootRequestedAction.StopOwn) },
        )
    }

    private fun RootOperationResult.toProxyServiceFailure(action: RootRequestedAction): ProxyServiceResult.Failed {
        logRootResult(toSanitizedLogRecord(action))
        return ProxyServiceResult.Failed(RootOperationBlockedException())
    }

    private fun Throwable.toProxyServiceFailure(action: RootRequestedAction): ProxyServiceResult.Failed {
        if (this is CancellationException) throw this
        val rootResult = when (this) {
            is RootRuntimeConflictException -> RootOperationResult.ForeignOwnerConflict(
                owner = RootRuntimeOwner.entries.single { owner -> owner.wireValue == snapshot.owner.wireValue },
            )
            is RootRuntimeBusyException -> RootOperationResult.Busy(
                RootRuntimeOwner.entries.single { owner -> owner.wireValue == snapshot.owner.wireValue },
            )
            else -> RootOperationResult.Failure(RootFailureKind.StartFailure)
        }
        logRootResult(rootResult.toSanitizedLogRecord(action))
        if (this !is RootRuntimeConflictException && this !is RootRuntimeBusyException) {
            AndroidAppLogger.error("RootFailureProbe", "unsanitized diagnostic", this)
        }
        return if (this is RootRuntimeConflictException || this is RootRuntimeBusyException) {
            ProxyServiceResult.Failed(RootOperationBlockedException())
        } else {
            ProxyServiceResult.Failed(this)
        }
    }

    private fun logRootResult(record: RootOperationLogRecord) {
        AndroidAppLogger.error(LogTag, record.asLogMessage())
    }
}

internal sealed interface ProxyServiceResult {
    data class Success(
        val proxyRunning: Boolean,
        val appState: AppState? = null,
    ) : ProxyServiceResult

    data object MissingServer : ProxyServiceResult

    data class Failed(val error: Throwable) : ProxyServiceResult
}

private val LocalRootOwner = RootRuntimeOwner.AsteriskNg
private const val LogTag = "ProxyServiceUseCase"
