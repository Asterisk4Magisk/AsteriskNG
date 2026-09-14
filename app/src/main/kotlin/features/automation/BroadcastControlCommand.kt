// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.automation

internal enum class BroadcastUpdateKind { Subscription, Resource }

internal enum class BroadcastControlCommand(
    val actionName: String,
    val updateKind: BroadcastUpdateKind? = null,
    val cancelsUpdate: Boolean = false,
) {
    Start("PROXY_START"),
    Stop("PROXY_STOP"),
    Toggle("PROXY_TOGGLE"),
    UpdateSubscriptions("SUBSCRIPTION_UPDATE", BroadcastUpdateKind.Subscription),
    CancelSubscriptionUpdate("SUBSCRIPTION_UPDATE_CANCEL", BroadcastUpdateKind.Subscription, true),
    UpdateResources("RESOURCE_UPDATE", BroadcastUpdateKind.Resource),
    CancelResourceUpdate("RESOURCE_UPDATE_CANCEL", BroadcastUpdateKind.Resource, true),
}

internal fun parseBroadcastControlCommand(action: String?, packageName: String): BroadcastControlCommand? =
    BroadcastControlCommand.entries.firstOrNull { action == "$packageName.action.${it.actionName}" }

internal interface BroadcastUpdateGateway {
    suspend fun start(kind: BroadcastUpdateKind)
    suspend fun cancel(kind: BroadcastUpdateKind)
}

internal suspend fun dispatchBroadcastUpdate(
    command: BroadcastControlCommand,
    enabled: Boolean,
    gateway: BroadcastUpdateGateway,
): Boolean {
    val kind = command.updateKind ?: return false
    if (enabled) {
        if (command.cancelsUpdate) gateway.cancel(kind) else gateway.start(kind)
    }
    return true
}
