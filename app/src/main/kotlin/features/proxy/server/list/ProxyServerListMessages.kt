// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.list

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.R

internal data class ProxyServerListMessages(
    val savedTemplate: String,
    val joinedTemplate: String,
    val deletedTemplate: String,
    val serviceRestarted: String,
    val noTestableServers: String,
    val latencyDoneTemplate: String,
    val realConnectionDoneTemplate: String,
    val sortDone: String,
    val subscriptionUpdateResultTemplate: String,
    val subscriptionUpdateResultWithFailedTemplate: String,
    val noSubscriptionUpdates: String,
    val serviceStarted: String,
    val serviceStopped: String,
    val selectServerFirst: String,
    val deleteSelectedBlocked: String,
    val latencyResultTemplate: String,
    val latencyFailed: String,
    val importResultTemplate: String,
    val copied: String,
    val unsupported: String,
    val duplicatesDeletedTemplate: String,
    val noDuplicates: String,
)

@Composable
internal fun proxyServerListMessages(): ProxyServerListMessages {
    return ProxyServerListMessages(
        savedTemplate = stringResource(R.string.proxy_server_list_saved),
        joinedTemplate = stringResource(R.string.proxy_server_list_joined),
        deletedTemplate = stringResource(R.string.proxy_server_list_deleted),
        serviceRestarted = stringResource(R.string.proxy_server_list_service_restarted),
        noTestableServers = stringResource(R.string.proxy_server_list_no_testable),
        latencyDoneTemplate = stringResource(R.string.proxy_server_list_latency_done),
        realConnectionDoneTemplate = stringResource(R.string.proxy_server_list_real_connection_done),
        sortDone = stringResource(R.string.common_complete),
        subscriptionUpdateResultTemplate = stringResource(R.string.proxy_server_list_subscription_update_result),
        subscriptionUpdateResultWithFailedTemplate =
            stringResource(R.string.proxy_server_list_subscription_update_result_with_failed),
        noSubscriptionUpdates = stringResource(R.string.proxy_server_list_no_subscription_updates),
        serviceStarted = stringResource(R.string.proxy_server_list_service_started),
        serviceStopped = stringResource(R.string.proxy_server_list_service_stopped),
        selectServerFirst = stringResource(R.string.proxy_server_list_select_first),
        deleteSelectedBlocked = stringResource(R.string.proxy_server_list_delete_selected_blocked),
        latencyResultTemplate = stringResource(R.string.proxy_server_list_latency_result),
        latencyFailed = stringResource(R.string.proxy_server_list_latency_failed),
        importResultTemplate = stringResource(R.string.proxy_server_list_import_result),
        copied = stringResource(R.string.common_copied),
        unsupported = stringResource(R.string.common_unsupported),
        duplicatesDeletedTemplate = stringResource(R.string.proxy_server_list_duplicates_deleted),
        noDuplicates = stringResource(R.string.proxy_server_list_no_duplicates),
    )
}
