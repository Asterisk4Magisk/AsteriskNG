// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidLogFileCreator(
    private val missingLauncherMessage: () -> String,
) {
    private val mutex = Mutex()
    private var launcher: ((String) -> Unit)? = null
    private var pendingResult: CompletableDeferred<Uri?>? = null

    fun registerLauncher(launcher: ((String) -> Unit)?) {
        this.launcher = launcher
    }

    fun complete(uri: Uri?) {
        pendingResult?.complete(uri)
        pendingResult = null
    }

    suspend fun create(fileName: String): Uri? {
        return mutex.withLock {
            val currentLauncher = launcher ?: error(missingLauncherMessage())
            val result = CompletableDeferred<Uri?>()
            pendingResult = result
            try {
                withContext(Dispatchers.Main.immediate) {
                    currentLauncher(fileName)
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
