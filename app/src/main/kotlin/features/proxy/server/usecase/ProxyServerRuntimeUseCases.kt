// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import app.AppState
import app.ProxyServerState
import features.proxy.server.model.strategyGroupMembers
import features.proxy.server.model.StrategyGroup
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
import engine.proxy.VpnPermissionRequester
import data.AndroidAppStateStore
import ui.feedback.AndroidToastTipNotifier
import ui.text.formatTemplate
import kotlin.coroutines.cancellation.CancellationException

private const val HandshakeLatencyTestConcurrency = 16
private const val RealConnectionLatencyTestConcurrency = 8

internal fun restartProxyServiceAfterSelection(
    serverId: Int,
    scope: CoroutineScope,
    serviceRestartMutex: Mutex,
    stateStore: AndroidAppStateStore,
    proxyEngine: AndroidProxyEngine,
    requestVpnPermission: VpnPermissionRequester,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    scope.launch {
        serviceRestartMutex.withLock {
            val stateSnapshot = stateStore.state.value
            if (stateSnapshot.selectedProxyServerId != serverId) {
                return@withLock
            }
            val status = try {
                proxyEngine.status(stateSnapshot.runMode, stateSnapshot)
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
                    requestVpnPermission,
                )
                updateAppState { state ->
                    if (state.selectedProxyServerId == serverId) {
                        state.copy(
                            proxyRunning = restartedStatus.running,
                            localProxyPort = restartedStatus.appState?.localProxyPort ?: state.localProxyPort,
                        )
                    } else {
                        state
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
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
    latencyUnsupportedMessage: String,
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
        // A strategy group has no single outbound to measure: test its members and report the best one.
        val groupMembers = targetServers.mapNotNull { server ->
            (server.server as? StrategyGroup)?.let { group -> server.id to stateSnapshot.strategyGroupMembers(group) }
        }.toMap()
        val testedServers = (targetServers.filter { server -> server.id !in groupMembers } + groupMembers.values.flatten())
            .distinctBy(ProxyServerState::id)
        val clearedIds = targetIds + testedServers.map(ProxyServerState::id)
        updateAppState { state ->
            state.copy(
                proxyServers = state.proxyServers.map { server ->
                    if (server.id in clearedIds) server.copy(latency = "") else server
                },
            )
        }

        val concurrency = when (mode) {
            ProxyServerLatencyTestMode.Handshake -> HandshakeLatencyTestConcurrency
            ProxyServerLatencyTestMode.RealConnection -> RealConnectionLatencyTestConcurrency
        }
        val semaphore = Semaphore(concurrency)

        supervisorScope {
            val results = testedServers.associate { server ->
                server.id to async {
                    semaphore.withPermit {
                        runCatching {
                            proxyLatencyTester.test(
                                appState = stateSnapshot,
                                server = server,
                                mode = mode,
                            )
                        }.getOrElse {
                            ProxyServerLatencyTestResult.Failed
                        }
                    }
                }
            }
            val reportedServers = (testedServers + targetServers).distinctBy(ProxyServerState::id)
            reportedServers.map { server ->
                async {
                    val result = groupMembers[server.id]
                        ?.map { member -> results.getValue(member.id).await() }
                        ?.bestStrategyGroupLatencyResult()
                        ?: results.getValue(server.id).await()
                    val latency = result.toLatencyText(latencyFailedMessage, latencyUnsupportedMessage)
                    updateAppState { state ->
                        state.copy(
                            proxyServers = state.proxyServers.map {
                                if (it.id == server.id) it.copy(latency = latency) else it
                            },
                        )
                    }
                    if (showSingleResult && server.id in targetIds) {
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

private fun List<ProxyServerLatencyTestResult>.bestStrategyGroupLatencyResult(): ProxyServerLatencyTestResult {
    filterIsInstance<ProxyServerLatencyTestResult.Success>()
        .minByOrNull(ProxyServerLatencyTestResult.Success::elapsedMillis)
        ?.let { return it }
    return if (isNotEmpty() && all { result -> result == ProxyServerLatencyTestResult.Unsupported }) {
        ProxyServerLatencyTestResult.Unsupported
    } else {
        ProxyServerLatencyTestResult.Failed
    }
}

private fun ProxyServerLatencyTestResult.toLatencyText(
    failedMessage: String,
    unsupportedMessage: String,
): String {
    return when (this) {
        is ProxyServerLatencyTestResult.Success -> "$elapsedMillis ms"
        ProxyServerLatencyTestResult.Failed -> failedMessage
        ProxyServerLatencyTestResult.Unsupported -> unsupportedMessage
    }
}
