// SPDX-License-Identifier: GPL-3.0
package engine.root.dns

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface RootDnsMutationBackend {
    suspend fun status(): RootDnsEnhancementState
    suspend fun suspendDns(token: RootDnsEnhancementState): RootDnsEnhancementState
    suspend fun resumeDns(token: RootDnsEnhancementState): RootDnsEnhancementState
    suspend fun abortDns(token: RootDnsEnhancementState)
}

/** Serializes mutations against the live cycle, never a saved settings/capability snapshot. */
internal object RootDnsMutation {
    private val mutex = Mutex()

    suspend fun <T> run(
        usesDaemonDns: Boolean,
        backendFactory: () -> RootDnsMutationBackend,
        mutation: suspend () -> T,
    ): T {
        // In particular, constructing a root shell is forbidden on the VPN/core-managed path.
        if (!usesDaemonDns) return mutation()
        return mutex.withLock {
            val backend = backendFactory()
            val token = backend.status()
            if (!token.required) return@withLock mutation()
            check(token.active) { token.reason ?: "Application list enhancement is not active" }
            try {
                val suspended = backend.suspendDns(token)
                check(suspended.sameCycle(token) && suspended.generation == token.generation &&
                    suspended.phase == RootDnsEnhancementPhase.Inactive) {
                    "DNS withdrawal did not acknowledge the current cycle"
                }
                val result = mutation()
                val resumed = backend.resumeDns(suspended)
                check(resumed.sameCycle(token) && resumed.active &&
                    resumed.generation.toULong() > token.generation.toULong()) {
                    "DNS enhancement did not activate a fresh generation"
                }
                result
            } catch (failure: Throwable) {
                // The daemon compares this token atomically. An ordinary stop after status
                // would have a race with a newly started cycle belonging to another request.
                withContext(NonCancellable) {
                    runCatching { backend.abortDns(token) }.exceptionOrNull()?.let { cleanup ->
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }
    }

    private fun RootDnsEnhancementState.sameCycle(other: RootDnsEnhancementState): Boolean =
        required && instanceId == other.instanceId && cycleId == other.cycleId
}
