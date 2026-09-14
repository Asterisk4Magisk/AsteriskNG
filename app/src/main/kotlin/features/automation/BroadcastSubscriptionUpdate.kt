// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.automation

import app.AsteriskApplication
import features.logs.AndroidAppLogger
import features.proxy.server.usecase.withUpdatedSubscriptionServers
import features.subscription.usecase.toSubscriptionFetchOptions
import features.subscription.usecase.updateSubscriptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun AsteriskApplication.updateBroadcastSubscriptions(progress: BroadcastUpdateProgress): Boolean {
    val ids = stateStore.state.value.subscriptionGroups.filter { it.url.isNotBlank() }.map { it.id }
    var failures = 0
    for (id in ids) {
        currentCoroutineContext().ensureActive()
        if (progress.completed(id.toString())) continue
        val group = stateStore.state.value.subscriptionGroups.firstOrNull { it.id == id } ?: continue
        if (group.url.isBlank()) continue
        val result = updateSubscriptions(
            groups = listOf(group),
            subscriptionFetcher = subscriptionFetcher,
            fetchOptions = { stateStore.state.value.toSubscriptionFetchOptions(it) },
        )
        currentCoroutineContext().ensureActive()
        if (result.updates.isNotEmpty()) {
            stateStore.update { it.withUpdatedSubscriptionServers(result.updates, result.updatedAtMillis) }
        }
        stateStore.awaitPersistence()
        progress.record(id.toString(), result.failures.isEmpty())
        failures += result.failedGroupCount
        AndroidAppLogger.info("BroadcastControl", "Subscription item=$id success=${result.failures.isEmpty()}")
    }
    return failures == 0 && progress.succeeded()
}
