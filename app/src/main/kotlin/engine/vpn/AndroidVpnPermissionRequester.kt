package engine.vpn

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidVpnPermissionRequester(
    private val missingLauncherMessage: () -> String,
) {
    private val mutex = Mutex()
    private var launcher: ((Intent) -> Unit)? = null
    private var pendingResult: CompletableDeferred<Boolean>? = null

    fun registerLauncher(launcher: ((Intent) -> Unit)?) {
        this.launcher = launcher
    }

    fun complete(granted: Boolean) {
        pendingResult?.complete(granted)
        pendingResult = null
    }

    suspend fun request(intent: Intent): Boolean {
        return mutex.withLock {
            val launcher = launcher ?: error(missingLauncherMessage())
            val result = CompletableDeferred<Boolean>()
            pendingResult = result
            try {
                withContext(Dispatchers.Main.immediate) {
                    launcher(intent)
                }
                result.await()
            } finally {
                if (pendingResult === result) {
                    pendingResult = null
                }
            }
        }
    }
}
