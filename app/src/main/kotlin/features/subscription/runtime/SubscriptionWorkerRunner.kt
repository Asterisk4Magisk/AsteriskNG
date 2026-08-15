// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import app.AppState
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdateResult
import features.subscription.isTransientSubscriptionFailure

internal enum class SubscriptionWorkerResult {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal class SubscriptionWorkerRunner(
    private val stateProvider: () -> AppState,
    private val update: suspend (Int) -> ProxyServerListSubscriptionUpdateResult,
) {
    suspend fun run(groupId: Int): SubscriptionWorkerResult {
        val group = stateProvider().subscriptionGroups.firstOrNull { it.id == groupId }
            ?: return SubscriptionWorkerResult.SUCCESS
        if (!group.enabled || group.url.isBlank()) return SubscriptionWorkerResult.SUCCESS
        val result = update(groupId)
        if (result.failures.isEmpty()) return SubscriptionWorkerResult.SUCCESS
        val hasTransientFailure = result.failures.any { failure ->
            isTransientSubscriptionFailure(failure.error)
        }
        return if (hasTransientFailure) {
            SubscriptionWorkerResult.RETRY
        } else {
            SubscriptionWorkerResult.FAILURE
        }
    }
}
