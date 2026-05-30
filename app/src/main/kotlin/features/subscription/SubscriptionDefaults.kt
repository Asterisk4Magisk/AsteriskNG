// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

const val DefaultSubscriptionGroupId = 1
const val DefaultSubscriptionUserAgent = "v2rayNG/2.2.1"
const val ClashMetaSubscriptionUserAgent = "clash.meta"
const val FlClashXSubscriptionUserAgent = "FlClash X/v0.4.0-pre.12 Platform/android"
const val AutoSubscriptionCheckIntervalMillis = 60L * 1000L
const val AutoSubscriptionRetryDelayMillis = 15L * 60L * 1000L

internal enum class SubscriptionUserAgentSelection {
    V2rayNg,
    ClashMeta,
    FlClashX,
    Custom,
}

internal val SubscriptionUserAgentSelections = listOf(
    SubscriptionUserAgentSelection.V2rayNg,
    SubscriptionUserAgentSelection.ClashMeta,
    SubscriptionUserAgentSelection.FlClashX,
    SubscriptionUserAgentSelection.Custom,
)

internal fun SubscriptionUserAgentSelection.userAgentOrNull(): String? = when (this) {
    SubscriptionUserAgentSelection.V2rayNg -> DefaultSubscriptionUserAgent
    SubscriptionUserAgentSelection.ClashMeta -> ClashMetaSubscriptionUserAgent
    SubscriptionUserAgentSelection.FlClashX -> FlClashXSubscriptionUserAgent
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
