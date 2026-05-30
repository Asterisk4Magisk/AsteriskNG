// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import features.subscription.DefaultSubscriptionGroupId
import app.navigation.Navigator
import app.navigation.ProxyServerEditResult
import features.proxy.server.usecase.withSavedProxyServer
import ui.feedback.AndroidToastTipNotifier
import ui.text.formatTemplate

@Composable
internal fun ProxyServerEditResultHandler(
    navigator: Navigator,
    resultKey: String,
    messages: ProxyServerListMessages,
    updateAppState: ((AppState) -> AppState) -> Unit,
    tipNotifier: AndroidToastTipNotifier,
    onSelectedGroupIdChange: (Int) -> Unit,
) {
    LaunchedEffect(navigator, tipNotifier, messages.savedTemplate, messages.joinedTemplate) {
        navigator.observeResult<ProxyServerEditResult>(resultKey).collect { result ->
            navigator.clearResult(resultKey)
            var existingGroupId = result.groupId
            var wasExisting = false
            updateAppState { state ->
                val applyResult = state.withSavedProxyServer(
                    serverId = result.serverId,
                    server = result.server,
                    groupId = result.groupId,
                )
                wasExisting = applyResult.wasExisting
                existingGroupId = applyResult.existingGroupId
                applyResult.state
            }
            if (wasExisting) {
                onSelectedGroupIdChange(result.returnGroupId ?: existingGroupId ?: DefaultSubscriptionGroupId)
                tipNotifier.show(messages.savedTemplate.formatTemplate("name" to result.server.getInfo().remarks))
            } else if (result.groupId != null) {
                onSelectedGroupIdChange(result.returnGroupId ?: result.groupId)
                tipNotifier.show(messages.joinedTemplate.formatTemplate("name" to result.server.getInfo().remarks))
            }
        }
    }
}
