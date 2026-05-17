// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import app.AppState
import app.ProxyServerState
import engine.proxy.AndroidProxyEngine
import engine.proxy.ProxyEngineStartRequest

internal class ProxyServiceUseCase(
    private val proxyEngine: AndroidProxyEngine,
) {
    suspend fun toggle(
        state: AppState,
        selectedServer: ProxyServerState?,
    ): ProxyServiceResult {
        return if (state.proxyRunning) {
            stop()
        } else {
            start(state, selectedServer)
        }
    }

    suspend fun restart(
        state: AppState,
        selectedServer: ProxyServerState?,
    ): ProxyServiceResult {
        val server = selectedServer ?: return ProxyServiceResult.MissingServer
        return runCatching {
            proxyEngine.restart(ProxyEngineStartRequest(state, server))
        }.fold(
            onSuccess = { status -> ProxyServiceResult.Success(proxyRunning = status.running) },
            onFailure = { error -> ProxyServiceResult.Failed(error) },
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
            onSuccess = { status -> ProxyServiceResult.Success(proxyRunning = status.running) },
            onFailure = { error -> ProxyServiceResult.Failed(error) },
        )
    }

    private suspend fun stop(): ProxyServiceResult {
        return runCatching { proxyEngine.stop() }.fold(
            onSuccess = { status -> ProxyServiceResult.Success(proxyRunning = status.running) },
            onFailure = { error -> ProxyServiceResult.Failed(error) },
        )
    }
}

internal sealed interface ProxyServiceResult {
    data class Success(val proxyRunning: Boolean) : ProxyServiceResult

    data object MissingServer : ProxyServiceResult

    data class Failed(val error: Throwable) : ProxyServiceResult
}
