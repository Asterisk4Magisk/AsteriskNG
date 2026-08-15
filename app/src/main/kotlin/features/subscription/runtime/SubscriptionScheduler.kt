// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import app.SubscriptionGroupState
import features.subscription.SubscriptionSchedule
import features.subscription.parseSubscriptionSchedule

internal enum class SubscriptionExistingWorkPolicy {
    UPDATE,
}

internal data class SubscriptionWorkSpec(
    val groupId: Int,
    val uniqueName: String,
    val repeatIntervalMillis: Long,
    val requiresConnectedNetwork: Boolean,
    val policy: SubscriptionExistingWorkPolicy,
    val backoffMillis: Long,
)

internal interface SubscriptionScheduleGateway {
    fun scheduledGroupIds(): Set<Int>

    fun enqueue(spec: SubscriptionWorkSpec)

    fun cancel(groupId: Int)

    fun storeScheduledGroupIds(groupIds: Set<Int>)
}

internal class SubscriptionScheduler(
    private val gateway: SubscriptionScheduleGateway,
) {
    fun reconcile(groups: List<SubscriptionGroupState>) {
        val desired = groups.mapNotNull { group ->
            val schedule = parseSubscriptionSchedule(group.updateInterval)
            if (!group.enabled || group.url.isBlank() || schedule !is SubscriptionSchedule.Enabled) {
                null
            } else {
                SubscriptionWorkSpec(
                    groupId = group.id,
                    uniqueName = subscriptionWorkName(group.id),
                    repeatIntervalMillis = schedule.repeatIntervalMillis,
                    requiresConnectedNetwork = true,
                    policy = SubscriptionExistingWorkPolicy.UPDATE,
                    backoffMillis = MinimumSubscriptionBackoffMillis,
                )
            }
        }
        val desiredIds = desired.mapTo(mutableSetOf()) { it.groupId }
        (gateway.scheduledGroupIds() - desiredIds).forEach(gateway::cancel)
        desired.forEach(gateway::enqueue)
        gateway.storeScheduledGroupIds(desiredIds)
    }
}

internal fun subscriptionWorkName(groupId: Int): String = "subscription-update-$groupId"

private const val MinimumSubscriptionBackoffMillis = 15 * 60 * 1_000L
