// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

const val DefaultSubscriptionGroupId = 1
const val DefaultSubscriptionUserAgent = "v2rayNG/2.3.8"
const val ClashMetaSubscriptionUserAgent = "clash.meta"
internal enum class SubscriptionUserAgentSelection {
    V2rayNg,
    ClashMeta,
    Custom,
}

internal val SubscriptionUserAgentSelections = listOf(
    SubscriptionUserAgentSelection.V2rayNg,
    SubscriptionUserAgentSelection.ClashMeta,
    SubscriptionUserAgentSelection.Custom,
)

internal fun SubscriptionUserAgentSelection.userAgentOrNull(): String? = when (this) {
    SubscriptionUserAgentSelection.V2rayNg -> DefaultSubscriptionUserAgent
    SubscriptionUserAgentSelection.ClashMeta -> ClashMetaSubscriptionUserAgent
    SubscriptionUserAgentSelection.Custom -> null
}

internal fun SubscriptionUserAgentSelection.resolveUserAgent(customUserAgent: String): String {
    return userAgentOrNull() ?: customUserAgent.trim().ifBlank { DefaultSubscriptionUserAgent }
}

internal fun subscriptionUserAgentSelectionFor(userAgent: String): SubscriptionUserAgentSelection {
    val trimmedUserAgent = userAgent.trim()
    return SubscriptionUserAgentSelections.firstOrNull { selection ->
        selection.userAgentOrNull() == trimmedUserAgent
    } ?: SubscriptionUserAgentSelection.Custom
}
