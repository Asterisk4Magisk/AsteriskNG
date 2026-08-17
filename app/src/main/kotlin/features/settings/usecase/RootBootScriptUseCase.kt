// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.usecase

import android.content.Context
import app.AppState
import app.modes.isRootRunMode
import engine.proxy.ProxyEngineStartRequest
import engine.root.runtime.RootFailureKind
import engine.root.runtime.RootOperationResult
import engine.root.runtime.RootRequestedAction
import engine.root.runtime.toAppLogMessage
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
            install(state)
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
        return install(state)
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

    private suspend fun install(state: AppState): RootBootScriptResult {
        val selectedServer = state.proxyServers.firstOrNull { server -> server.id == state.selectedProxyServerId }
            ?: return RootBootScriptResult.MissingServer
        return runCatching {
            val request = ProxyEngineStartRequest(state, selectedServer)
            if (state.runMode.isRootRunMode()) {
                installRootBootScript(state.runMode, request)
            }
        }.fold(
            onSuccess = { RootBootScriptResult.Success },
            onFailure = Throwable::toRootBootScriptResult,
        )
    }

    private suspend fun installRootBootScript(
        runMode: Int,
        request: ProxyEngineStartRequest,
    ) {
        val config = RootModeEngine.prepareConfig(appContext, runMode, request)
        controller.publishBoot(config.root, config.asteriskdConfig)
    }
}

private fun Throwable.toRootBootScriptResult(): RootBootScriptResult {
    if (this is CancellationException) {
        throw this
    }
    val operationResult = RootOperationResult.Failure(RootFailureKind.InternalFailure)
    AndroidAppLogger.error(
        RootBootLogTag,
        operationResult.toAppLogMessage(RootRequestedAction.BootRefresh),
    )
    return RootBootScriptResult.Failed(this)
}

internal sealed interface RootBootScriptResult {
    data object Success : RootBootScriptResult

    data object MissingServer : RootBootScriptResult

    data object RootUnavailable : RootBootScriptResult

    data class Failed(val error: Throwable) : RootBootScriptResult
}

private const val RootBootLogTag = "RootBootScript"
