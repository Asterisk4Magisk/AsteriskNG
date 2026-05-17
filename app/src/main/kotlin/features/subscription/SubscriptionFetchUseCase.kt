// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import features.subscription.runtime.AndroidSubscriptionFetchOptions
import features.subscription.runtime.AndroidSubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubscriptionFetchUseCase {
    private val fetcher = AndroidSubscriptionFetcher()

    suspend fun fetch(
        url: String,
        userAgent: String,
        options: SubscriptionFetchOptions = SubscriptionFetchOptions(),
    ): String = withContext(Dispatchers.IO) {
        fetcher.fetch(
            url = url,
            userAgent = userAgent,
            options = AndroidSubscriptionFetchOptions(
                useRunningProxy = options.useRunningProxy,
                fallbackProxyPort = options.fallbackProxyPort,
                fallbackProxyUsername = options.fallbackProxyUsername,
                fallbackProxyPassword = options.fallbackProxyPassword,
            ),
        )
    }
}

data class SubscriptionFetchOptions(
    val useRunningProxy: Boolean = false,
    val fallbackProxyPort: Int? = null,
    val fallbackProxyUsername: String = "",
    val fallbackProxyPassword: String = "",
)
