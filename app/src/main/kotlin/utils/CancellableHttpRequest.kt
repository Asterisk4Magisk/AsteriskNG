// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Disconnect blocking HTTP I/O when its owning coroutine is cancelled. */
internal suspend fun <T> runCancellableHttpRequest(
    block: (track: (HttpURLConnection) -> Unit) -> T,
): T = withContext(Dispatchers.IO) {
    coroutineScope {
        val connection = AtomicReference<HttpURLConnection?>()
        val owner = coroutineContext
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                connection.getAndSet(null)?.disconnect()
            }
        }
        try {
            owner.ensureActive()
            block { active ->
                connection.set(active)
                if (!owner.isActive) {
                    connection.getAndSet(null)?.disconnect()
                    owner.ensureActive()
                }
            }
        } finally {
            cancellation.cancel()
        }
    }
}
