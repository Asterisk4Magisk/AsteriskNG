// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import app.AppState
import app.ProxyServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import engine.proxy.latency.AndroidProxyLatencyTester
import engine.proxy.latency.ProxyServerLatencyTestMode
import engine.proxy.latency.ProxyServerLatencyTestResult
import engine.proxy.AndroidProxyEngine
import engine.proxy.ProxyEngineStartRequest
import data.AndroidAppStateStore
import ui.feedback.AndroidToastTipNotifier
import ui.text.formatTemplate
import kotlin.coroutines.cancellation.CancellationException

private const val TcpLatencyTestConcurrency = 16
private const val RealConnectionLatencyTestConcurrency = 8

internal fun restartProxyServiceAfterSelection(
    serverId: Int,
    scope: CoroutineScope,
    serviceRestartMutex: Mutex,
    stateStore: AndroidAppStateStore,
    proxyEngine: AndroidProxyEngine,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    scope.launch {
        serviceRestartMutex.withLock {
            val stateSnapshot = stateStore.state.value
            if (stateSnapshot.selectedProxyServerId != serverId) {
                return@withLock
            }
            val status = try {
                proxyEngine.status(stateSnapshot.runMode)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return@withLock
            }
            if (!status.running) return@withLock

            val server = stateSnapshot.proxyServers.firstOrNull { it.id == serverId } ?: return@withLock
            try {
                val restartedStatus = proxyEngine.restart(
                    ProxyEngineStartRequest(
                        appState = stateSnapshot,
                        selectedServer = server,
                    ),
                )
                updateAppState { state ->
                    if (state.selectedProxyServerId == serverId) {
                        state.copy(proxyRunning = restartedStatus.running)
                    } else {
                        state
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                updateAppState { state ->
                    if (state.selectedProxyServerId == serverId) {
                        state.copy(proxyRunning = false)
                    } else {
                        state
                    }
                }
            }
        }
    }
}

internal fun runProxyServerLatencyTest(
    targetServers: List<ProxyServerState>,
    mode: ProxyServerLatencyTestMode,
    doneTemplate: String,
    showSingleResult: Boolean,
    scope: CoroutineScope,
    stateStore: AndroidAppStateStore,
    updateAppState: ((AppState) -> AppState) -> Unit,
    proxyLatencyTester: AndroidProxyLatencyTester,
    tipNotifier: AndroidToastTipNotifier,
    noTestableServersMessage: String,
    latencyResultTemplate: String,
    latencyFailedMessage: String,
) {
    if (targetServers.isEmpty()) {
        scope.launch {
            tipNotifier.show(noTestableServersMessage)
        }
        return
    }

    scope.launch {
        val stateSnapshot = stateStore.state.value
        val targetIds = targetServers.map { server -> server.id }.toSet()
        updateAppState { state ->
            state.copy(
                proxyServers = state.proxyServers.map { server ->
                    if (server.id in targetIds) server.copy(latency = "") else server
                },
            )
        }

        val concurrency = when (mode) {
            ProxyServerLatencyTestMode.TcpConnect -> TcpLatencyTestConcurrency
            ProxyServerLatencyTestMode.RealConnection -> RealConnectionLatencyTestConcurrency
        }
        val semaphore = Semaphore(concurrency)

        supervisorScope {
            targetServers.map { server ->
                async {
                    val latency = semaphore.withPermit {
                        runCatching {
                            proxyLatencyTester.test(
                                appState = stateSnapshot,
                                server = server,
                                mode = mode,
                            )
                        }.getOrElse {
                            ProxyServerLatencyTestResult.Failed
                        }.toLatencyText(latencyFailedMessage)
                    }
                    updateAppState { state ->
                        state.copy(
                            proxyServers = state.proxyServers.map {
                                if (it.id == server.id) it.copy(latency = latency) else it
                            },
                        )
                    }
                    if (showSingleResult) {
                        tipNotifier.show(
                            latencyResultTemplate.formatTemplate(
                                "name" to server.server.getInfo().remarks,
                                "latency" to latency,
                            ),
                        )
                    }
                }
            }.awaitAll()
        }

        if (!showSingleResult) {
            tipNotifier.show(doneTemplate.formatTemplate("count" to targetServers.size))
        }
    }
}

private fun ProxyServerLatencyTestResult.toLatencyText(failedMessage: String): String {
    return if (elapsedMillis >= 0) "$elapsedMillis ms" else failedMessage
}
