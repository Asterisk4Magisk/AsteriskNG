// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import data.AndroidAppStateStore
import engine.proxy.AndroidProxyEngine
import features.logs.AndroidAppLogger
import features.proxy.server.usecase.ProxyServiceResult
import features.proxy.server.usecase.ProxyServiceUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import system.AndroidRootShellGateway
import kotlin.coroutines.cancellation.CancellationException

class BroadcastControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.action.toProxyControlCommand(context) ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        operationScope.launch {
            try {
                operationMutex.withLock {
                    BroadcastControlHandler(appContext).handle(command)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                AndroidAppLogger.error(LogTag, "Broadcast control ${command.actionName} failed unexpectedly", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_START = ".action.PROXY_START"
        const val ACTION_STOP = ".action.PROXY_STOP"
        const val ACTION_TOGGLE = ".action.PROXY_TOGGLE"

        private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val operationMutex = Mutex()
    }
}

private class BroadcastControlHandler(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val stateStore by lazy { AndroidAppStateStore.get(appContext) }
    private val rootAccess by lazy { AndroidRootShellGateway() }
    private val proxyEngine by lazy {
        AndroidProxyEngine(
            context = appContext,
            rootAccess = rootAccess,
            requestVpnPermission = {
                AndroidAppLogger.warn(LogTag, "Broadcast control cannot request VPN permission in the background")
                false
            },
        )
    }
    private val proxyServiceUseCase by lazy { ProxyServiceUseCase(proxyEngine) }

    suspend fun handle(command: ProxyControlCommand) {
        val configuredState = stateStore.state.value
        if (!configuredState.enableBroadcastControl) {
            AndroidAppLogger.warn(LogTag, "Ignored ${command.actionName} because broadcast control is disabled")
            return
        }

        val running = syncProxyRunningState(configuredState.runMode)
        val state = stateStore.state.value.copy(proxyRunning = running)
        val selectedServer = state.proxyServers.firstOrNull { server -> server.id == state.selectedProxyServerId }

        val result = when (command) {
            ProxyControlCommand.Start -> {
                if (running) {
                    ProxyServiceResult.Success(proxyRunning = true)
                } else {
                    proxyServiceUseCase.toggle(state.copy(proxyRunning = false), selectedServer)
                }
            }

            ProxyControlCommand.Stop -> {
                if (!running) {
                    ProxyServiceResult.Success(proxyRunning = false)
                } else {
                    proxyServiceUseCase.stop(state.runMode)
                }
            }

            ProxyControlCommand.Toggle -> proxyServiceUseCase.toggle(state, selectedServer)
        }

        applyResult(command, result)
    }

    private suspend fun syncProxyRunningState(runMode: Int): Boolean {
        val currentState = stateStore.state.value
        val running = runCatching { proxyEngine.status(runMode, currentState).running }
            .onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to read proxy status for external control", error)
            }
            .getOrElse { currentState.proxyRunning }
        if (currentState.proxyRunning != running) {
            stateStore.update { state -> state.copy(proxyRunning = running) }
        }
        return running
    }

    private fun applyResult(
        command: ProxyControlCommand,
        result: ProxyServiceResult,
    ) {
        when (result) {
            is ProxyServiceResult.Success -> {
                stateStore.update { state ->
                    state.copy(
                        proxyRunning = result.proxyRunning,
                        localProxyPort = result.appState?.localProxyPort ?: state.localProxyPort,
                    )
                }
                AndroidAppLogger.info(LogTag, "Broadcast control ${command.actionName} completed: running=${result.proxyRunning}")
            }

            ProxyServiceResult.MissingServer -> {
                AndroidAppLogger.warn(LogTag, "Broadcast control ${command.actionName} ignored because no proxy server is selected")
            }

            is ProxyServiceResult.Failed -> {
                if (result.error is CancellationException) {
                    throw result.error
                }
                stateStore.update { state -> state.copy(proxyRunning = false) }
                AndroidAppLogger.error(LogTag, "Broadcast control ${command.actionName} failed", result.error)
            }
        }
    }
}

private enum class ProxyControlCommand(
    val actionName: String,
) {
    Start("start"),
    Stop("stop"),
    Toggle("toggle"),
}

private fun String?.toProxyControlCommand(context: Context): ProxyControlCommand? {
    return when (this) {
        context.packageName + BroadcastControlReceiver.ACTION_START -> ProxyControlCommand.Start
        context.packageName + BroadcastControlReceiver.ACTION_STOP -> ProxyControlCommand.Stop
        context.packageName + BroadcastControlReceiver.ACTION_TOGGLE -> ProxyControlCommand.Toggle
        else -> null
    }
}

private const val LogTag = "BroadcastControl"
