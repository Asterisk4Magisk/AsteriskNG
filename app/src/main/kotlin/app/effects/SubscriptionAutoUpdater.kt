// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import data.AndroidAppStateStore
import features.proxy.server.usecase.dueSubscriptionGroups
import features.proxy.server.usecase.withUpdatedSubscriptionServers
import features.subscription.AutoSubscriptionCheckIntervalMillis
import features.subscription.AutoSubscriptionRetryDelayMillis
import features.subscription.SubscriptionFetchUseCase
import features.subscription.usecase.toSubscriptionFetchOptions
import features.subscription.usecase.updateSubscriptions
import kotlinx.coroutines.delay
import kotlin.time.Clock

@Composable
internal fun SubscriptionAutoUpdater(
    stateStore: AndroidAppStateStore,
    subscriptionFetchUseCase: SubscriptionFetchUseCase,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    LaunchedEffect(stateStore, subscriptionFetchUseCase) {
        val lastAttemptMillisByGroupId = mutableMapOf<Int, Long>()
        while (true) {
            val currentState = stateStore.state.value
            val nowMillis = Clock.System.now().toEpochMilliseconds()
            val dueGroups = currentState.subscriptionGroups
                .dueSubscriptionGroups(nowMillis)
                .filter { group ->
                    nowMillis - (lastAttemptMillisByGroupId[group.id] ?: 0L) >= AutoSubscriptionRetryDelayMillis
                }
            if (dueGroups.isNotEmpty()) {
                dueGroups.forEach { group -> lastAttemptMillisByGroupId[group.id] = nowMillis }
                val result = updateSubscriptions(
                    groups = dueGroups,
                    subscriptionFetchUseCase = subscriptionFetchUseCase,
                    fetchOptions = { group -> currentState.toSubscriptionFetchOptions(group) },
                )
                if (result.updates.isNotEmpty()) {
                    updateAppState { state ->
                        state.withUpdatedSubscriptionServers(
                            updates = result.updates,
                            updatedAtMillis = result.updatedAtMillis,
                        )
                    }
                }
            }
            delay(AutoSubscriptionCheckIntervalMillis)
        }
    }
}
