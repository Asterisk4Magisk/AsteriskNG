// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.AsteriskApplication
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdateResult
import features.proxy.server.usecase.withUpdatedSubscriptionServers
import features.subscription.usecase.toSubscriptionFetchOptions
import features.subscription.usecase.updateSubscriptions

internal class SubscriptionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val groupId = inputData.getInt(SubscriptionGroupIdKey, MissingGroupId)
        if (groupId == MissingGroupId) return Result.success()
        val application = applicationContext as? AsteriskApplication ?: return Result.failure()
        val runner = SubscriptionWorkerRunner(
            stateProvider = { application.stateStore.state.value },
            update = { requestedGroupId -> application.updateSubscription(requestedGroupId) },
        )
        return when (runner.run(groupId)) {
            SubscriptionWorkerResult.SUCCESS -> Result.success()
            SubscriptionWorkerResult.RETRY -> Result.retry()
            SubscriptionWorkerResult.FAILURE -> Result.failure()
        }
    }

    private suspend fun AsteriskApplication.updateSubscription(
        groupId: Int,
    ): ProxyServerListSubscriptionUpdateResult {
        val group = stateStore.state.value.subscriptionGroups.firstOrNull { it.id == groupId }
            ?: return ProxyServerListSubscriptionUpdateResult(
                updates = emptyList(),
                failures = emptyList(),
                updatedAtMillis = 0L,
            )
        val result = updateSubscriptions(
            groups = listOf(group),
            subscriptionFetcher = subscriptionFetcher,
            fetchOptions = { currentGroup ->
                stateStore.state.value.toSubscriptionFetchOptions(currentGroup)
            },
        )
        if (result.updates.isNotEmpty()) {
            stateStore.update { state ->
                state.withUpdatedSubscriptionServers(
                    updates = result.updates,
                    updatedAtMillis = result.updatedAtMillis,
                )
            }
        }
        return result
    }

    private companion object {
        const val MissingGroupId = Int.MIN_VALUE
    }
}
