// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import android.content.Context
import android.net.Uri
import app.ProxyServerState
import app.SubscriptionGroupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Actions of the subscription group card's share menu. */
internal enum class SubscriptionGroupShareAction {
    SubscriptionQrCode,
    SubscriptionUrl,
    ServerUrls,
    ServerUrlsFile,
}

/**
 * Share actions available for [group]. Only the subscription URL itself is shared: the group's
 * User-Agent, HWID and Age secret key stay local.
 */
internal fun subscriptionGroupShareActions(
    group: SubscriptionGroupState,
    serverCount: Int,
): List<SubscriptionGroupShareAction> = buildList {
    if (group.url.isNotBlank()) {
        add(SubscriptionGroupShareAction.SubscriptionQrCode)
        add(SubscriptionGroupShareAction.SubscriptionUrl)
    }
    if (serverCount > 0) {
        add(SubscriptionGroupShareAction.ServerUrls)
        add(SubscriptionGroupShareAction.ServerUrlsFile)
    }
}

internal fun List<ProxyServerState>.inSubscriptionGroup(groupId: Int): List<ProxyServerState> =
    filter { server -> server.groupId == groupId }

/**
 * Suggested document name for [groupName]. Letters and digits survive, so a localized group
 * name stays readable; everything a document provider may reject collapses into a dash. The
 * sanitized part is bounded because CJK names reach a provider's byte limit long before their
 * character count looks large.
 */
internal fun subscriptionGroupShareFileName(groupName: String): String {
    val safeName = groupName.trim()
        .map { char -> if (char.isLetterOrDigit() || char == '-' || char == '_') char else '-' }
        .joinToString("")
        .trim('-')
        .take(SubscriptionGroupShareFileNameMaxLength)
        .trimEnd('-')
        .ifBlank { "servers" }
    return "$safeName.$SubscriptionGroupShareFileExtension"
}

/** Writes a group's share URLs into the document the user picked. */
internal suspend fun Context.writeSubscriptionGroupShareDocument(uri: Uri, content: String) {
    withContext(Dispatchers.IO) {
        val stream = contentResolver.openOutputStream(uri)
            ?: error("Unable to open the subscription group share document")
        stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(content) }
    }
}

private const val SubscriptionGroupShareFileExtension = "txt"
private const val SubscriptionGroupShareFileNameMaxLength = 64
