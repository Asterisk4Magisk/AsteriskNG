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

    suspend fun stop(preferredRunMode: Int? = null): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val engine = activeEngine
            ?: preferredRunMode?.engine()?.takeIf { it.status().running }
            ?: tproxyEngine.takeIf { it.status().running }
            ?: vpnXrayEngine.takeIf { it.status().running }
        val stoppedMode = engine?.runMode
        engine?.stop()
        activeEngine = null
        ProxyEngineStatus(running = false, runMode = stoppedMode)
    }

    suspend fun restart(request: ProxyEngineStartRequest): ProxyEngineStatus {
        stop(request.appState.runMode)
        return start(request)
    }

    suspend fun status(preferredRunMode: Int? = null): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val activeStatus = activeEngine?.status()
        if (activeStatus?.running == true) {
            return@withContext activeStatus
        }

        preferredRunMode?.engine()?.let { preferredEngine ->
            val preferredStatus = preferredEngine.status()
            if (preferredStatus.running) {
                activeEngine = preferredEngine
                return@withContext preferredStatus
            }
            if (activeStatus == null) {
                return@withContext preferredStatus
            }
        }

        listOf(tproxyEngine, vpnXrayEngine)
            .filterNot { engine -> engine.runMode == preferredRunMode }
            .forEach { engine ->
                val status = engine.status()
                if (status.running) {
                    activeEngine = engine
                    return@withContext status
                }
            }

        activeEngine = null
        activeStatus ?: ProxyEngineStatus(running = false, runMode = preferredRunMode)
    }

    private fun Int.engine(): AndroidModeProxyEngine {
        return when (this) {
            RunModeTproxy -> tproxyEngine
            else -> vpnXrayEngine
        }
    }
}
