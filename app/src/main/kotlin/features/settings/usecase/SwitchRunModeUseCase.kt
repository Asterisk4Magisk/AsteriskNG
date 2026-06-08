// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.usecase

import android.content.Context
import app.AppState
import app.modes.RunModeTun2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import engine.proxy.AndroidProxyEngine
import engine.tun2socks.deleteHevSocks5TunnelLogFile
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CancellationException
import system.AndroidRootShellGateway

internal class SwitchRunModeUseCase(
    context: Context,
    private val proxyEngine: AndroidProxyEngine,
    private val rootAccess: AndroidRootShellGateway,
    private val rootBootScriptUseCase: RootBootScriptUseCase,
) {
    private val appContext = context.applicationContext

    suspend fun switchRunMode(
        currentState: AppState,
        targetRunMode: Int,
    ): SwitchRunModeResult {
        val normalizedTargetMode = when (targetRunMode) {
            RunModeTproxy -> RunModeTproxy
            RunModeTun2Socks -> RunModeTun2Socks
            else -> RunModeVpnService
        }
        if (currentState.runMode == normalizedTargetMode) {
            return SwitchRunModeResult.Success(
                runMode = currentState.runMode,
                proxyRunning = currentState.proxyRunning,
            )
        }

        val targetRequiresRoot = normalizedTargetMode.isRootRunMode()
        val stopRequiresRoot = currentState.proxyRunning && currentState.runMode.isRootRunMode()
        val needsRootAccess = stopRequiresRoot || currentState.enableRootBootScript || targetRequiresRoot
        if (needsRootAccess && !rootAccess.hasRootAccess()) {
            return SwitchRunModeResult.RootUnavailable(proxyRunning = currentState.proxyRunning)
        }

        val stoppedRunning = if (currentState.proxyRunning) {
            runCatching { proxyEngine.stopCurrentRunMode(currentState.runMode) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    return SwitchRunModeResult.StopFailed(error)
                }
                .running
        } else {
            false
        }

        if (currentState.enableRootBootScript) {
            when (val result = rootBootScriptUseCase.uninstall(rootAccessVerified = true)) {
                RootBootScriptResult.Success,
                RootBootScriptResult.MissingServer -> Unit

                RootBootScriptResult.RootUnavailable -> {
                    return SwitchRunModeResult.RootUnavailable(proxyRunning = stoppedRunning)
                }

                is RootBootScriptResult.Failed -> {
                    return SwitchRunModeResult.StopFailed(result.error)
                }
            }
        }

        if (normalizedTargetMode != RunModeTun2Socks) {
            deleteHevSocks5TunnelLog()
        }

        return SwitchRunModeResult.Success(
            runMode = normalizedTargetMode,
            proxyRunning = stoppedRunning,
        )
    }

    private fun Int.isRootRunMode(): Boolean {
        return this == RunModeTproxy || this == RunModeTun2Socks
    }

    private fun deleteHevSocks5TunnelLog() {
        runCatching { appContext.deleteHevSocks5TunnelLogFile() }
            .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to delete tun2socks log", error) }
    }
}

internal sealed interface SwitchRunModeResult {
    data class Success(
        val runMode: Int,
        val proxyRunning: Boolean,
    ) : SwitchRunModeResult

    data class RootUnavailable(
        val proxyRunning: Boolean,
    ) : SwitchRunModeResult

    data class StopFailed(
        val error: Throwable,
    ) : SwitchRunModeResult
}

private const val LogTag = "SwitchRunMode"
