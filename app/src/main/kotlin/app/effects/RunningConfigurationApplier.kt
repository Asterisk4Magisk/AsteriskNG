// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Applies updated configuration to a running core without asking: successful resource and
 * subscription updates request it from every entry point (the UI, the scheduled workers and
 * the broadcast updates).
 *
 * Requests that arrive close together — a batch of resource files, several subscriptions —
 * settle into one apply, which waits until no resource update is queued or running. Requests
 * are conflated, so a change that lands while an apply runs gets exactly one more. [apply]
 * reads the current state itself and decides whether the core is running; an unexpected
 * error goes to [reportFailure] and never ends later requests.
 */
internal class RunningConfigurationApplier(
    scope: CoroutineScope,
    private val updatesBusy: Flow<Boolean>,
    private val apply: suspend () -> Unit,
    private val reportFailure: (Throwable) -> Unit,
    private val settleDelay: Duration = DefaultSettleDelay,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                requests.receive()
                do {
                    while (withTimeoutOrNull(settleDelay) { requests.receive() } != null) Unit
                    // Batches enqueue one file at a time, so idle right after a busy stretch may
                    // only be the gap before the next file; settle once more after waiting.
                    val wasBusy = updatesBusy.first()
                    if (wasBusy) updatesBusy.first { busy -> !busy }
                } while (wasBusy)
                // The apply below compiles the state as it is now, which covers these as well.
                requests.tryReceive()
                try {
                    apply()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    reportFailure(error)
                }
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }

    private companion object {
        val DefaultSettleDelay = 2.seconds
    }
}
