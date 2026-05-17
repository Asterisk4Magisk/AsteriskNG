// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.usecase

import app.AppState
import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import engine.proxy.AndroidProxyEngine
import system.AndroidRootShellGateway

internal class SwitchRunModeUseCase(
    private val proxyEngine: AndroidProxyEngine,
    private val rootAccess: AndroidRootShellGateway,
    private val tproxyBootScriptUseCase: TproxyBootScriptUseCase,
) {
    suspend fun switchRunMode(
        currentState: AppState,
        targetRunMode: Int,
    ): SwitchRunModeResult {
        val normalizedTargetMode = when (targetRunMode) {
            RunModeTproxy -> RunModeTproxy
            else -> RunModeVpnService
        }
        if (currentState.runMode == normalizedTargetMode) {
            return SwitchRunModeResult.Success(
                runMode = currentState.runMode,
                proxyRunning = currentState.proxyRunning,
            )
        }

        val stoppedRunning = runCatching { proxyEngine.stop() }
            .getOrElse { error -> return SwitchRunModeResult.StopFailed(error) }
            .running

        when (val result = tproxyBootScriptUseCase.uninstall()) {
            TproxyBootScriptResult.Success,
            TproxyBootScriptResult.MissingServer -> Unit

            TproxyBootScriptResult.RootUnavailable -> {
                return SwitchRunModeResult.RootUnavailable(proxyRunning = stoppedRunning)
            }

            is TproxyBootScriptResult.Failed -> {
                return SwitchRunModeResult.StopFailed(result.error)
            }
        }

        if (normalizedTargetMode == RunModeTproxy && !rootAccess.hasRootAccess()) {
            return SwitchRunModeResult.RootUnavailable(proxyRunning = stoppedRunning)
        }

        return SwitchRunModeResult.Success(
            runMode = normalizedTargetMode,
            proxyRunning = stoppedRunning,
        )
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
