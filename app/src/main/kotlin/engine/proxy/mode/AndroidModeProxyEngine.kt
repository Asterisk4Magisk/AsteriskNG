package engine.proxy.mode

import engine.proxy.ProxyEngineStartRequest
import engine.proxy.ProxyEngineStatus

internal interface AndroidModeProxyEngine {
    val runMode: Int

    suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus

    suspend fun stop(): ProxyEngineStatus

    suspend fun status(): ProxyEngineStatus
}
