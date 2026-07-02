// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.usecase

import android.content.Context
import app.AppState
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeTun2Socks
import app.modes.isRootRunMode
import engine.proxy.ProxyEngineStartRequest
import engine.root.bpf2socks.Bpf2SocksRootRunner
import engine.root.bpf2socks.buildBpf2SocksStartConfig
import engine.root.prepareRootConfigBuildContext
import engine.root.prepareRootRuntimeLayout
import engine.root.removeRootBootScript
import engine.xray.prepareXrayCoreLogPaths
import engine.tproxy.TproxyRootRunner
import engine.tproxy.buildTproxyStartConfig
import engine.tun2socks.Tun2SocksRootRunner
import engine.tun2socks.buildTun2SocksStartConfig
import kotlinx.coroutines.CancellationException
import system.AndroidRootShellGateway

internal class RootBootScriptUseCase(
    context: Context,
    private val rootAccess: AndroidRootShellGateway,
) {
    private val appContext = context.applicationContext
    private val tproxyRootRunner = TproxyRootRunner(rootAccess)
    private val tun2SocksRootRunner = Tun2SocksRootRunner(rootAccess)
    private val bpf2SocksRootRunner = Bpf2SocksRootRunner(rootAccess)

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
            rootAccess.removeRootBootScript(
                runtimeLayout = appContext.prepareRootRuntimeLayout(),
                coreLogPaths = appContext.prepareXrayCoreLogPaths(),
                failureMessage = "Failed to remove ROOT boot script",
            )
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
        val rootContext = appContext.prepareRootConfigBuildContext(request)
        when (runMode) {
            RunModeTproxy -> tproxyRootRunner.installBootScript(rootContext.buildTproxyStartConfig())
            RunModeTun2Socks -> tun2SocksRootRunner.installBootScript(rootContext.buildTun2SocksStartConfig())
            RunModeBpf2Socks -> bpf2SocksRootRunner.installBootScript(rootContext.buildBpf2SocksStartConfig())
        }
    }
}

private fun Throwable.toRootBootScriptResult(): RootBootScriptResult {
    if (this is CancellationException) {
        throw this
    }
    return RootBootScriptResult.Failed(this)
}

internal sealed interface RootBootScriptResult {
    data object Success : RootBootScriptResult

    data object MissingServer : RootBootScriptResult

    data object RootUnavailable : RootBootScriptResult

    data class Failed(val error: Throwable) : RootBootScriptResult
}
