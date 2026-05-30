// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import app.AppState
import app.SubscriptionGroupState
import data.AndroidAppStateStore
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdateResult
import features.proxy.server.usecase.withUpdatedSubscriptionServers
import features.subscription.runtime.AndroidSubscriptionFetcher
import features.subscription.usecase.toSubscriptionFetchOptions
import features.subscription.usecase.updateSubscriptions

internal data class SubscriptionInstallConfig(
    val name: String,
    val url: String,
    val userAgent: String,
)

internal class SubscriptionInstallConfigUseCase(
    private val stateStore: AndroidAppStateStore,
    private val subscriptionFetcher: AndroidSubscriptionFetcher,
) {
    suspend fun install(config: SubscriptionInstallConfig): ProxyServerListSubscriptionUpdateResult {
        val group = stateStore.addSubscriptionGroup(config)
        val result = updateSubscriptions(
            groups = listOf(group),
            subscriptionFetcher = subscriptionFetcher,
            fetchOptions = { stateStore.state.value.toSubscriptionFetchOptions(it) },
        )
        if (result.updates.isNotEmpty()) {
            stateStore.update { state ->
                state.withUpdatedSubscriptionServers(
                    updates = result.updates,
                    updatedAtMillis = result.updatedAtMillis,
                )
            }
        }
        return result
    }
}

internal fun Intent.toSubscriptionInstallConfigOrNull(): SubscriptionInstallConfig? {
    if (action != Intent.ACTION_VIEW) return null
    return data?.toSubscriptionInstallConfigOrNull()
}

internal fun String.toSubscriptionInstallConfigOrNull(): SubscriptionInstallConfig? {
    val uri = runCatching { trim().toUri() }.getOrNull() ?: return null
    return uri.toSubscriptionInstallConfigOrNull()
}

internal fun Uri.isSubscriptionInstallConfigUri(): Boolean {
    return installConfigSource() != null &&
        isHierarchical &&
        host.equals(InstallConfigHost, ignoreCase = true)
}

internal fun Uri.toSubscriptionInstallConfigOrNull(): SubscriptionInstallConfig? {
    val source = installConfigSource() ?: return null
    if (!isHierarchical || !host.equals(InstallConfigHost, ignoreCase = true)) return null
    val name = getQueryParameter("name")?.trim().orEmpty()
        .ifBlank { source.defaultName.orEmpty() }
    val url = getQueryParameter("url")?.trim().orEmpty()
    if (name.isBlank() || !url.isValidSubscriptionUrl()) return null
    return SubscriptionInstallConfig(
        name = name,
        url = url,
        userAgent = source.userAgent,
    )
}

private fun AndroidAppStateStore.addSubscriptionGroup(config: SubscriptionInstallConfig): SubscriptionGroupState {
    var savedGroup: SubscriptionGroupState? = null
    update { state ->
        val group = state.newSubscriptionGroup(config)
        savedGroup = group
        state.copy(
            subscriptionGroups = state.subscriptionGroups + group,
            nextSubscriptionGroupId = state.nextSubscriptionGroupId + 1,
        )
    }
    return checkNotNull(savedGroup)
}

private fun AppState.newSubscriptionGroup(config: SubscriptionInstallConfig): SubscriptionGroupState {
    return SubscriptionGroupState(
        id = nextSubscriptionGroupId,
        name = config.name,
        url = config.url,
        userAgent = config.userAgent,
        updateInterval = "",
        updateViaProxy = false,
        enabled = true,
    )
}

private fun String.isValidSubscriptionUrl(): Boolean {
    val uri = runCatching { toUri() }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    return uri.isHierarchical &&
        scheme in SubscriptionUrlSchemes &&
        uri.host?.isNotBlank() == true
}

private enum class InstallConfigSource(
    val scheme: String,
    val userAgent: String,
    val defaultName: String? = null,
) {
    V2rayNg(scheme = "v2rayng", userAgent = DefaultSubscriptionUserAgent),
    ClashMeta(scheme = "clashmeta", userAgent = ClashMetaSubscriptionUserAgent),
    FlClashX(
        scheme = "flclashx",
        userAgent = FlClashXSubscriptionUserAgent,
        defaultName = "clashsub",
    ),
}

private fun Uri.installConfigSource(): InstallConfigSource? {
    val uriScheme = scheme ?: return null
    return InstallConfigSource.entries.firstOrNull { source ->
        source.scheme.equals(uriScheme, ignoreCase = true)
    }
}

private const val InstallConfigHost = "install-config"
private val SubscriptionUrlSchemes = setOf("http", "https")
