// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.usecase

import app.AppState
import app.SubscriptionGroupState
import features.logs.AndroidAppLogger
import features.proxy.server.usecase.ProxyServerImportSource
import features.proxy.server.usecase.ProxyServerListSubscriptionFailure
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdate
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdateResult
import features.proxy.server.usecase.importProxyServersFromText
import features.proxy.server.usecase.subscriptionFetchIdentity
import features.subscription.runtime.AndroidSubscriptionFetchOptions
import features.subscription.runtime.AndroidSubscriptionFetcher
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ui.text.formatTemplate
import kotlin.time.Clock

private const val LogTag = "SubscriptionUpdateUseCase"

internal suspend fun updateSubscriptions(
    groups: List<SubscriptionGroupState>,
    subscriptionFetcher: AndroidSubscriptionFetcher,
    fetchOptions: (SubscriptionGroupState) -> AndroidSubscriptionFetchOptions,
): ProxyServerListSubscriptionUpdateResult = updateSubscriptions(
    groups = groups,
    fetchOptions = fetchOptions,
    fetchText = { url, userAgent, options ->
        subscriptionFetcher.fetch(url, userAgent, options)
    },
)

internal suspend fun updateSubscriptions(
    groups: List<SubscriptionGroupState>,
    fetchOptions: (SubscriptionGroupState) -> AndroidSubscriptionFetchOptions,
    fetchText: suspend (String, String, AndroidSubscriptionFetchOptions) -> String,
    coordinator: SubscriptionUpdateCoordinator = DefaultSubscriptionUpdateCoordinator,
): ProxyServerListSubscriptionUpdateResult = supervisorScope {
    val results = groups.map { group ->
        async {
            group to coordinator.withGroup(group.id) {
                updateSubscriptionGroup(
                    group = group,
                    fetchText = fetchText,
                    fetchOptions = fetchOptions(group),
                )
            }
        }
    }.awaitAll()
    val updates = results
        .mapNotNull { (_, result) -> result.getOrNull() }
    val failures = results.mapNotNull { (group, result) ->
        result.exceptionOrNull()?.let { error ->
            ProxyServerListSubscriptionFailure(groupId = group.id, error = error)
        }
    }
    ProxyServerListSubscriptionUpdateResult(
        updates = updates,
        failures = failures,
        updatedAtMillis = Clock.System.now().toEpochMilliseconds(),
    )
}

private suspend fun updateSubscriptionGroup(
    group: SubscriptionGroupState,
    fetchText: suspend (String, String, AndroidSubscriptionFetchOptions) -> String,
    fetchOptions: AndroidSubscriptionFetchOptions,
): Result<ProxyServerListSubscriptionUpdate> {
    return runCatching {
        val text = fetchText(group.url, group.userAgent, fetchOptions)
        val importResult = importProxyServersFromText(
            text = text,
            source = ProxyServerImportSource.SubscriptionUrl,
            providerUrlFetcher = { providerUrl ->
                fetchText(providerUrl, group.userAgent, fetchOptions)
            },
        )
        ProxyServerListSubscriptionUpdate(
            groupId = group.id,
            sourceIdentity = group.subscriptionFetchIdentity(),
            urlCount = importResult.urlCount,
            servers = importResult.servers,
        ).also { update ->
            if (update.servers.isEmpty()) {
                AndroidAppLogger.warn(
                    LogTag,
                    "Subscription update imported no proxy servers ${group.logIdentity()} " +
                        "parsedProxyServerCount=${update.urlCount} responseLength=${text.length}",
                )
            }
            require(update.servers.isNotEmpty()) {
                "Subscription update imported no proxy servers"
            }
        }
    }.onFailure { error ->
        AndroidAppLogger.warn(
            LogTag,
            "Subscription update failed ${group.logIdentity()}",
            error,
        )
    }
}

internal class SubscriptionUpdateCoordinator {
    private val mutexes = ConcurrentHashMap<Int, Mutex>()

    suspend fun <T> withGroup(groupId: Int, block: suspend () -> T): T {
        return mutexes.computeIfAbsent(groupId) { Mutex() }.withLock { block() }
    }
}

private val DefaultSubscriptionUpdateCoordinator = SubscriptionUpdateCoordinator()

internal fun AppState.toSubscriptionFetchOptions(group: SubscriptionGroupState): AndroidSubscriptionFetchOptions {
    return AndroidSubscriptionFetchOptions(
        useRunningProxy = group.updateViaProxy && proxyRunning,
        hwid = group.hwid,
        ageSecretKey = group.ageSecretKey,
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
