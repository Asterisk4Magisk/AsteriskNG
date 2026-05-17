package features.resources.runtime

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidResourceFilePicker(
    private val missingLauncherMessage: () -> String,
) {
    private val mutex = Mutex()
    private var launcher: ((Array<String>) -> Unit)? = null
    private var pendingResult: CompletableDeferred<Uri?>? = null

    fun registerLauncher(launcher: ((Array<String>) -> Unit)?) {
        this.launcher = launcher
    }

    fun complete(uri: Uri?) {
        pendingResult?.complete(uri)
        pendingResult = null
    }

    suspend fun pick(): Uri? {
        return mutex.withLock {
            val currentLauncher = launcher ?: error(missingLauncherMessage())
            val result = CompletableDeferred<Uri?>()
            pendingResult = result
            try {
                withContext(Dispatchers.Main.immediate) {
                    currentLauncher(arrayOf("*/*"))
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
