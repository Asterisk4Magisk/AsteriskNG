// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.usecase

import app.AppState
import app.SubscriptionGroupState
import data.AndroidAppStateStore
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdateResult
import features.proxy.server.usecase.withUpdatedSubscriptionServers
import features.subscription.DefaultSubscriptionGroupId
import features.subscription.SubscriptionInstallConfig
import features.subscription.runtime.AndroidSubscriptionFetcher
import ui.text.formatTemplate
import utils.nameFromSubscriptionUrl

internal data class SubscriptionInstallResult(
    val updateResult: ProxyServerListSubscriptionUpdateResult,
    val existingGroupName: String?,
)

internal class SubscriptionInstallConfigUseCase(
    private val stateStore: AndroidAppStateStore,
    private val subscriptionFetcher: AndroidSubscriptionFetcher,
    private val duplicateNameTemplate: String,
) {
    suspend fun install(config: SubscriptionInstallConfig): SubscriptionInstallResult {
        val preparedGroup = stateStore.prepareSubscriptionInstallGroup(config)
        val result = updateSubscriptions(
            groups = listOf(preparedGroup.group),
            subscriptionFetcher = subscriptionFetcher,
            fetchOptions = { stateStore.state.value.toSubscriptionFetchOptions(it) },
        )
        if (result.updates.isNotEmpty()) {
            stateStore.update { state ->
                state.withUpdatedSubscriptionServers(
                    updates = result.updates,
                    updatedAtMillis = result.updatedAtMillis,
                    duplicateNameTemplate = duplicateNameTemplate,
                )
            }
        }
        return SubscriptionInstallResult(
            updateResult = result,
            existingGroupName = preparedGroup.existingGroupName,
        )
    }
}

internal fun subscriptionInstallMessage(
    result: SubscriptionInstallResult,
    existingUrlTemplate: String,
    successTemplate: String,
    failedTemplate: String,
): String {
    val updateMessage = subscriptionUpdateMessage(
        result = result.updateResult,
        successTemplate = successTemplate,
        failedTemplate = failedTemplate,
    )
    val existingGroupName = result.existingGroupName ?: return updateMessage
    return listOf(
        existingUrlTemplate.formatTemplate("name" to existingGroupName),
        updateMessage,
    ).joinToString(separator = "\n")
}

private fun AndroidAppStateStore.prepareSubscriptionInstallGroup(
    config: SubscriptionInstallConfig,
): PreparedSubscriptionInstallGroup {
    var preparedGroup: PreparedSubscriptionInstallGroup? = null
    update { state ->
        val existingGroup = state.existingSubscriptionGroupByUrl(config.url)
        if (existingGroup != null) {
            preparedGroup = PreparedSubscriptionInstallGroup(
                group = existingGroup,
                existingGroupName = existingGroup.name.ifBlank { nameFromSubscriptionUrl(existingGroup.url).orEmpty() },
            )
            return@update state
        }
        val reusableGroup = state.reusableDefaultSubscriptionGroup()
        val group = reusableGroup?.copy(
            url = config.url,
            userAgent = config.userAgent,
        ) ?: state.newSubscriptionGroup(config)
        preparedGroup = PreparedSubscriptionInstallGroup(
            group = group,
            existingGroupName = null,
        )
        if (reusableGroup == null) {
            state.copy(
                subscriptionGroups = state.subscriptionGroups + group,
                nextSubscriptionGroupId = state.nextSubscriptionGroupId + 1,
            )
        } else {
            state.copy(
                subscriptionGroups = state.subscriptionGroups.map { existingGroup ->
                    if (existingGroup.id == group.id) group else existingGroup
                },
            )
        }
    }
    return checkNotNull(preparedGroup)
}

private data class PreparedSubscriptionInstallGroup(
    val group: SubscriptionGroupState,
    val existingGroupName: String?,
)

private fun AppState.existingSubscriptionGroupByUrl(url: String): SubscriptionGroupState? {
    return subscriptionGroups.firstOrNull { group -> group.url == url }
}

private fun AppState.reusableDefaultSubscriptionGroup(): SubscriptionGroupState? {
    return subscriptionGroups.firstOrNull { group -> group.id == DefaultSubscriptionGroupId }
        ?.takeIf {
            proxyServers.none { server -> server.groupId == DefaultSubscriptionGroupId }
        }
}

private fun AppState.newSubscriptionGroup(config: SubscriptionInstallConfig): SubscriptionGroupState {
    return SubscriptionGroupState(
        id = nextSubscriptionGroupId,
        name = config.name,
        url = config.url,
        userAgent = config.userAgent,
        updateInterval = "",
        updateViaProxy = false,
        enabled = true,
    )
}
