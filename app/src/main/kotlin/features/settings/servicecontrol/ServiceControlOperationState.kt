// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.servicecontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Keep a save and its UI state alive across page removal and Activity recreation. */
internal object ServiceControlOperationState {
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val visibleSheets = AtomicInteger(0)

    fun clearError() {
        mutableError.value = null
    }

    /**
     * Registers an open sheet that shows errors inline. Closing the last observation clears the
     * error: a recreated page starts with the sheet closed, so an old error must not reappear.
     */
    fun observeSheet(): AutoCloseable {
        visibleSheets.incrementAndGet()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true) && visibleSheets.decrementAndGet() == 0) clearError()
        }
    }

    /** Returns false when no sheet can show [message]; the caller must notify the user elsewhere. */
    fun reportError(message: String): Boolean {
        if (visibleSheets.get() <= 0) return false
        mutableError.value = message
        // The sheet may close concurrently; never leave an error for a later opening.
        if (visibleSheets.get() > 0) return true
        mutableError.compareAndSet(message, null)
        return false
    }

    fun launch(scope: CoroutineScope, action: suspend () -> Unit): Job? {
        if (!mutableSaving.compareAndSet(false, true)) return null
        clearError()
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action()
            } finally {
                mutableSaving.value = false
            }
        }
    }
}
