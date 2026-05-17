package features.subscription.usecase

import app.AppState
import app.SubscriptionGroupState
import app.modes.RunModeVpnService
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdateResult
import features.proxy.server.usecase.importProxyServersFromSubscriptionText
import features.subscription.SubscriptionFetchOptions
import features.subscription.SubscriptionFetchUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import ui.text.formatTemplate
import kotlin.time.Clock

internal suspend fun updateSubscriptions(
    groups: List<SubscriptionGroupState>,
    subscriptionFetchUseCase: SubscriptionFetchUseCase,
    fetchOptions: (SubscriptionGroupState) -> SubscriptionFetchOptions,
): ProxyServerListSubscriptionUpdateResult = supervisorScope {
    val results = groups.map { group ->
        async {
            runCatching {
                val text = subscriptionFetchUseCase.fetch(
                    url = group.url,
                    userAgent = group.userAgent,
                    options = fetchOptions(group),
                )
                importProxyServersFromSubscriptionText(
                    text = text,
                    groupId = group.id,
                )
            }.getOrNull()
        }
    }.awaitAll()
    val updates = results
        .filterNotNull()
        .filter { update -> update.servers.isNotEmpty() }
    ProxyServerListSubscriptionUpdateResult(
        updates = updates,
        failedGroupCount = results.size - updates.size,
        updatedAtMillis = Clock.System.now().toEpochMilliseconds(),
    )
}

internal fun AppState.toSubscriptionFetchOptions(group: SubscriptionGroupState): SubscriptionFetchOptions {
    return SubscriptionFetchOptions(
        useRunningProxy = group.updateViaProxy && proxyRunning && runMode == RunModeVpnService,
        fallbackProxyPort = localProxyPort.toIntOrNull(),
        fallbackProxyUsername = localProxyUsername,
        fallbackProxyPassword = localProxyPassword,
    )
}

internal fun subscriptionUpdateMessage(
    result: ProxyServerListSubscriptionUpdateResult,
    successTemplate: String,
    failedTemplate: String,
): String {
    val template = if (result.failedGroupCount > 0) failedTemplate else successTemplate
    return template.formatTemplate(
        "groupCount" to result.updatedGroupCount,
        "failedCount" to result.failedGroupCount,
        "serverCount" to result.importedServerCount,
    )
}

