// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.net.Uri
import androidx.annotation.MainThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidDocumentLauncher<Input>(
    private val missingLauncherMessage: () -> String,
) {
    private val mutex = Mutex()
    private var launcher: ((Input) -> Unit)? = null
    private var pendingResult: CompletableDeferred<Uri?>? = null

    @MainThread
    fun registerLauncher(launcher: ((Input) -> Unit)?) {
        this.launcher = launcher
        if (launcher == null) complete(null)
    }

    @MainThread
    fun complete(uri: Uri?) {
        val result = pendingResult
        pendingResult = null
        result?.complete(uri)
    }

    suspend fun request(input: Input): Uri? = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            // A cancelled caller can leave the picker open. Wait for its callback
            // before launching a new request through the same activity result contract.
            pendingResult?.join()
            val launch = launcher ?: error(missingLauncherMessage())
            val result = CompletableDeferred<Uri?>()
            pendingResult = result
            try {
                launch(input)
            } catch (failure: Throwable) {
                complete(null)
                throw failure
            }
            result.await()
        }
    }
}
