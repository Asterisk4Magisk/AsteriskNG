package engine.proxy

import android.content.Context
import android.content.Intent
import app.modes.RunModeTproxy
import engine.proxy.mode.AndroidModeProxyEngine
import engine.tproxy.TproxyEngine
import engine.vpn.VpnXrayEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import system.AndroidRootShellGateway

class AndroidProxyEngine(
    context: Context,
    rootAccess: AndroidRootShellGateway,
    requestVpnPermission: suspend (Intent) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val vpnXrayEngine = VpnXrayEngine(appContext, requestVpnPermission)
    private val tproxyEngine = TproxyEngine(appContext, rootAccess)
    private var activeEngine: AndroidModeProxyEngine? = null

    suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val nextEngine = when (request.appState.runMode) {
            RunModeTproxy -> tproxyEngine
            else -> vpnXrayEngine
        }
        if (activeEngine != null && activeEngine !== nextEngine) {
            activeEngine?.stop()
        }
        activeEngine = nextEngine
        nextEngine.start(request)
    }

    suspend fun stop(): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val engine = activeEngine ?: tproxyEngine.takeIf { it.status().running }
        val stoppedMode = engine?.runMode
        engine?.stop()
        activeEngine = null
        ProxyEngineStatus(running = false, runMode = stoppedMode)
    }

    suspend fun restart(request: ProxyEngineStartRequest): ProxyEngineStatus {
        stop()
        return start(request)
    }

    suspend fun status(): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val activeStatus = activeEngine?.status()
        if (activeStatus?.running == true) {
            return@withContext activeStatus
        }
        val tproxyStatus = tproxyEngine.status()
        if (tproxyStatus.running) {
            activeEngine = tproxyEngine
            return@withContext tproxyStatus
        }
        activeEngine = activeEngine?.takeUnless { it === tproxyEngine }
        activeStatus ?: ProxyEngineStatus(running = false)
    }
}
