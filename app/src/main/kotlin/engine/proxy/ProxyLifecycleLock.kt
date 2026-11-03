// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

/**
 * Serializes work outside [AndroidProxyEngine] with its start, restart and stop operations.
 * ROOT boot publication rewrites the same runtime configuration files a launch writes, so it
 * must never interleave with one. The lock is not reentrant.
 */
internal interface ProxyLifecycleLock {
    suspend fun <T> withLifecycleLock(action: suspend () -> T): T
}
