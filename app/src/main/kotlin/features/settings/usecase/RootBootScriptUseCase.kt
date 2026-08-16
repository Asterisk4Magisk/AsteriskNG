// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.usecase

import android.content.Context
import app.AppState
import app.modes.isRootRunMode
import engine.proxy.ProxyEngineStartRequest
import engine.root.runtime.RootFailureKind
import engine.root.runtime.RootOperationBlockedException
import engine.root.runtime.RootOperationResult
import engine.root.runtime.RootRequestedAction
import engine.root.runtime.toAppLogMessage
import engine.root.runtime.model.RootRuntimeOwner
import engine.root.runtime.RootRuntimeBusyException
import engine.root.runtime.RootRuntimeConflictException
import engine.root.runtime.RootSupervisorController
import engine.root.RootModeEngine
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CancellationException
import system.AndroidRootShellGateway

internal class RootBootScriptUseCase(
    context: Context,
    private val rootAccess: AndroidRootShellGateway,
) {
    private val appContext = context.applicationContext
    private val controller = RootSupervisorController(appContext, rootAccess)

    suspend fun setEnabled(
        state: AppState,
        enabled: Boolean,
    ): RootBootScriptResult {
        if (!rootAccess.hasRootAccess()) {
            return RootBootScriptResult.RootUnavailable
        }
        return if (enabled) {
            install(state, deferIfRuntimeBound = false)
        } else {
            uninstall(rootAccessVerified = true)
        }
    }

    suspend fun refresh(state: AppState): RootBootScriptResult {
        if (!state.enableRootBootScript) {
            return RootBootScriptResult.Success
        }
        if (!rootAccess.hasRootAccess()) {
            return RootBootScriptResult.RootUnavailable
        }
        return install(state, deferIfRuntimeBound = true)
    }

    suspend fun uninstall(rootAccessVerified: Boolean = false): RootBootScriptResult {
        if (!rootAccessVerified && !rootAccess.hasRootAccess()) {
            return RootBootScriptResult.RootUnavailable
        }
        return runCatching {
            controller.removeBoot()
        }.fold(
            onSuccess = { RootBootScriptResult.Success },
            onFailure = Throwable::toRootBootScriptResult,
        )
    }

    private suspend fun install(
        state: AppState,
        deferIfRuntimeBound: Boolean,
    ): RootBootScriptResult {
        val selectedServer = state.proxyServers.firstOrNull { server -> server.id == state.selectedProxyServerId }
            ?: return RootBootScriptResult.MissingServer
        return runCatching {
            val request = ProxyEngineStartRequest(state, selectedServer)
            if (state.runMode.isRootRunMode()) {
                installRootBootScript(state.runMode, request, deferIfRuntimeBound)
            }
        }.fold(
            onSuccess = { RootBootScriptResult.Success },
            onFailure = Throwable::toRootBootScriptResult,
        )
    }

    private suspend fun installRootBootScript(
        runMode: Int,
        request: ProxyEngineStartRequest,
        deferIfRuntimeBound: Boolean,
    ) {
        if (!controller.canPublishBoot(deferIfRuntimeBound)) return
        val config = RootModeEngine.prepareConfig(appContext, runMode, request)
        controller.publishBoot(config.root, config.asteriskdConfig)
    }
}

private fun Throwable.toRootBootScriptResult(): RootBootScriptResult {
    if (this is CancellationException) {
        throw this
    }
    val operationResult = toRootBootOperationResult()
    AndroidAppLogger.error(
        RootBootLogTag,
        operationResult.toAppLogMessage(RootRequestedAction.BootRefresh),
    )
    val reportedError = when (this) {
        is RootRuntimeConflictException, is RootRuntimeBusyException -> RootOperationBlockedException(operationResult)
        else -> this
    }
    return RootBootScriptResult.Failed(reportedError)
}

private fun Throwable.toRootBootOperationResult(): RootOperationResult = when (this) {
    is RootRuntimeConflictException -> RootOperationResult.ForeignOwnerConflict(
        owner = RootRuntimeOwner.entries.single { owner -> owner.wireValue == snapshot.owner.wireValue },
    )
    is RootRuntimeBusyException -> RootOperationResult.Busy(
        RootRuntimeOwner.entries.single { owner -> owner.wireValue == snapshot.owner.wireValue },
    )
    else -> RootOperationResult.Failure(RootFailureKind.InternalFailure)
}

internal sealed interface RootBootScriptResult {
    data object Success : RootBootScriptResult

    data object MissingServer : RootBootScriptResult

    data object RootUnavailable : RootBootScriptResult

    data class Failed(val error: Throwable) : RootBootScriptResult
}

private const val RootBootLogTag = "RootBootScript"
