// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.usecase

import android.content.Context
import app.AppState
import engine.proxy.ProxyEngineStartRequest
import engine.tproxy.TproxyConfigFactory
import engine.tproxy.TproxyRootRunner
import system.AndroidRootShellGateway

internal class TproxyBootScriptUseCase(
    context: Context,
    private val rootAccess: AndroidRootShellGateway,
) {
    private val appContext = context.applicationContext
    private val rootRunner = TproxyRootRunner(rootAccess)

    suspend fun setEnabled(
        state: AppState,
        enabled: Boolean,
    ): TproxyBootScriptResult {
        if (!rootAccess.hasRootAccess()) {
            return TproxyBootScriptResult.RootUnavailable
        }
        return if (enabled) {
            install(state)
        } else {
            uninstall()
        }
    }

    suspend fun refresh(state: AppState): TproxyBootScriptResult {
        if (!state.enableTproxyBootScript) {
            return TproxyBootScriptResult.Success
        }
        if (!rootAccess.hasRootAccess()) {
            return TproxyBootScriptResult.RootUnavailable
        }
        return install(state)
    }

    suspend fun uninstall(): TproxyBootScriptResult {
        if (!rootAccess.hasRootAccess()) {
            return TproxyBootScriptResult.RootUnavailable
        }
        return runCatching {
            rootRunner.uninstallBootScript()
        }.fold(
            onSuccess = { TproxyBootScriptResult.Success },
            onFailure = TproxyBootScriptResult::Failed,
        )
    }

    private suspend fun install(state: AppState): TproxyBootScriptResult {
        val selectedServer = state.proxyServers.firstOrNull { server -> server.id == state.selectedProxyServerId }
            ?: return TproxyBootScriptResult.MissingServer
        return runCatching {
            val config = TproxyConfigFactory.create(
                context = appContext,
                request = ProxyEngineStartRequest(state, selectedServer),
            )
            rootRunner.installBootScript(config)
        }.fold(
            onSuccess = { TproxyBootScriptResult.Success },
            onFailure = TproxyBootScriptResult::Failed,
        )
    }
}

internal sealed interface TproxyBootScriptResult {
    data object Success : TproxyBootScriptResult

    data object MissingServer : TproxyBootScriptResult

    data object RootUnavailable : TproxyBootScriptResult

    data class Failed(val error: Throwable) : TproxyBootScriptResult
}
