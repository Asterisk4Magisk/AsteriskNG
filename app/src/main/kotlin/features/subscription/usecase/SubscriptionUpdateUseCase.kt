// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.usecase

import app.AppState
import app.SubscriptionGroupState
import app.modes.RunModeVpnService
import features.logs.AndroidAppLogger
import features.proxy.server.usecase.ProxyServerImportSource
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdate
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdateResult
import features.proxy.server.usecase.importProxyServersFromText
import features.subscription.runtime.AndroidSubscriptionFetchOptions
import features.subscription.runtime.AndroidSubscriptionFetcher
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import ui.text.formatTemplate
import kotlin.time.Clock

private const val LogTag = "SubscriptionUpdateUseCase"

internal suspend fun updateSubscriptions(
    groups: List<SubscriptionGroupState>,
    subscriptionFetcher: AndroidSubscriptionFetcher,
    fetchOptions: (SubscriptionGroupState) -> AndroidSubscriptionFetchOptions,
): ProxyServerListSubscriptionUpdateResult = supervisorScope {
    val results = groups.map { group ->
        async {
            updateSubscriptionGroup(
                group = group,
                subscriptionFetcher = subscriptionFetcher,
                fetchOptions = fetchOptions(group),
            )
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

private suspend fun updateSubscriptionGroup(
    group: SubscriptionGroupState,
    subscriptionFetcher: AndroidSubscriptionFetcher,
    fetchOptions: AndroidSubscriptionFetchOptions,
): ProxyServerListSubscriptionUpdate? {
    return runCatching {
        val text = subscriptionFetcher.fetch(
            url = group.url,
            userAgent = group.userAgent,
            options = fetchOptions,
        )
        val importResult = importProxyServersFromText(
            text = text,
            source = ProxyServerImportSource.SubscriptionUrl,
        )
        ProxyServerListSubscriptionUpdate(
            groupId = group.id,
            urlCount = importResult.urlCount,
            servers = importResult.servers,
        ).also { update ->
            if (update.servers.isEmpty()) {
                AndroidAppLogger.warn(
                    LogTag,
                    "Subscription update imported no proxy servers ${group.logIdentity()} " +
                        "parsedNodeCount=${update.urlCount} responseLength=${text.length}",
                )
            }
        }
    }.onFailure { error ->
        AndroidAppLogger.warn(
            LogTag,
            "Subscription update failed ${group.logIdentity()}",
            error,
        )
    }.getOrNull()
}

internal fun AppState.toSubscriptionFetchOptions(group: SubscriptionGroupState): AndroidSubscriptionFetchOptions {
    return AndroidSubscriptionFetchOptions(
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

private fun SubscriptionGroupState.logIdentity(): String {
    return "groupId=$id groupName=${name.ifBlank { "<blank>" }} " +
        "urlHost=${url.toLogHost()} userAgent=${userAgent.ifBlank { "<blank>" }}"
}

private fun String.toLogHost(): String {
    return runCatching { URI(this).host }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: "<unknown>"
}
