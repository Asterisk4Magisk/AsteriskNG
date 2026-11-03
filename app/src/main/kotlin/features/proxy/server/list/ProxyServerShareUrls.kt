// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.list

import androidx.compose.ui.platform.Clipboard
import app.ProxyServerState
import features.proxy.server.model.getUrlOrNull
import ui.clipboard.setPlainText
import ui.feedback.AndroidToastTipNotifier

/** Share URLs of a set of servers, one per line, and how many servers could not produce one. */
internal data class ProxyServerShareUrls(
    val text: String,
    val invalidCount: Int,
)

internal fun proxyServerShareUrls(servers: List<ProxyServerState>): ProxyServerShareUrls {
    var invalidCount = 0
    val text = servers.mapNotNull { server ->
        if (server.server.validateBasic().isNotEmpty()) {
            invalidCount++
            return@mapNotNull null
        }
        runCatching { server.server.getUrlOrNull() }.fold(
            onSuccess = { url -> url },
            onFailure = {
                invalidCount++
                null
            },
        )
    }
        .joinToString("\n")
    return ProxyServerShareUrls(text = text, invalidCount = invalidCount)
}

/** The tip shown instead of sharing when [ProxyServerShareUrls.text] is blank. */
internal fun ProxyServerShareUrls.emptyMessage(
    unsupportedMessage: String,
    configInvalidMessage: String,
): String = if (invalidCount > 0) configInvalidMessage else unsupportedMessage

/**
 * Copies the share URLs of [servers]. The proxy server list's "copy all" tool and the
 * subscription group card's share menu both copy through here.
 */
internal suspend fun copyProxyServerShareUrls(
    servers: List<ProxyServerState>,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    copiedMessage: String,
    unsupportedMessage: String,
    configInvalidMessage: String,
) {
    val urls = proxyServerShareUrls(servers)
    if (urls.text.isBlank()) {
        tipNotifier.show(urls.emptyMessage(unsupportedMessage, configInvalidMessage))
    } else {
        clipboard.setPlainText(urls.text)
        tipNotifier.show(copiedMessage)
    }
}
