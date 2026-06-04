// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import android.content.Intent
import android.net.Uri
import app.AppState
import app.SubscriptionGroupState
import data.AndroidAppStateStore
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdateResult
import features.proxy.server.usecase.withUpdatedSubscriptionServers
import features.subscription.runtime.AndroidSubscriptionFetcher
import features.subscription.usecase.toSubscriptionFetchOptions
import features.subscription.usecase.updateSubscriptions
import io.ktor.http.Url
import utils.decodeUrlComponentPreservingPlus

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
        val group = stateStore.prepareSubscriptionInstallGroup(config)
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
    return data?.toString()?.toSubscriptionInstallConfigOrNull()
}

internal fun String.toSubscriptionInstallConfigOrNull(): SubscriptionInstallConfig? {
    val value = trim()
    if (value.any(Char::isWhitespace)) return null
    val url = runCatching { Url(value) }.getOrNull() ?: return null
    return url.toSubscriptionInstallConfigOrNull(value)
}

internal fun String.toRawHttpsSubscriptionInstallConfigOrNull(): SubscriptionInstallConfig? {
    val value = trim()
    if (value.any(Char::isWhitespace)) return null
    val url = runCatching { Url(value) }.getOrNull() ?: return null
    return url.toRawHttpsSubscriptionInstallConfigOrNull(value)
}

internal fun Uri.isSubscriptionInstallConfigUri(): Boolean {
    return runCatching { Url(toString()).isSubscriptionInstallConfigUri() }
        .getOrDefault(false)
}

private fun Url.toSubscriptionInstallConfigOrNull(rawValue: String): SubscriptionInstallConfig? {
    toRawHttpsSubscriptionInstallConfigOrNull(rawValue)?.let { return it }
    val source = installConfigSource() ?: return null
    val url = parameters["url"]?.trim().orEmpty()
    if (!isSubscriptionInstallConfigUri() || !url.isValidSubscriptionUrl()) return null
    val name = listOfNotNull(
        parameters["name"],
        fragment,
        url.toSubscriptionUrlFragmentOrNull(),
        source.defaultName,
    )
        .firstNotNullOfOrNull { value -> value.trim().decodeUrlComponentPreservingPlus().takeIf(String::isNotBlank) }
        ?: return null
    return SubscriptionInstallConfig(
        name = name,
        url = url,
        userAgent = source.userAgent,
    )
}

private fun Url.toRawHttpsSubscriptionInstallConfigOrNull(rawValue: String): SubscriptionInstallConfig? {
    if (!rawValue.isValidSubscriptionUrl()) return null
    val name = listOfNotNull(fragment, V2rayNgDefaultSubscriptionName)
        .firstNotNullOfOrNull { value -> value.trim().decodeUrlComponentPreservingPlus().takeIf(String::isNotBlank) }
        ?: return null
    return SubscriptionInstallConfig(
        name = name,
        url = rawValue,
        userAgent = DefaultSubscriptionUserAgent,
    )
}

private fun AndroidAppStateStore.prepareSubscriptionInstallGroup(
    config: SubscriptionInstallConfig,
): SubscriptionGroupState {
    var savedGroup: SubscriptionGroupState? = null
    update { state ->
        val reusableGroup = state.reusableDefaultSubscriptionGroup()
        val group = reusableGroup?.copy(
            url = config.url,
            userAgent = config.userAgent,
        ) ?: state.newSubscriptionGroup(config)
        savedGroup = group
        if (reusableGroup == null) {
            state.copy(
                subscriptionGroups = state.subscriptionGroups + group,
                nextSubscriptionGroupId = state.nextSubscriptionGroupId + 1,
            )
        } else {
            state.copy(
                subscriptionGroups = state.subscriptionGroups.map { existingGroup ->
                    if (existingGroup.id == group.id) group else existingGroup
                },
            )
        }
    }
    return checkNotNull(savedGroup)
}

private fun AppState.reusableDefaultSubscriptionGroup(): SubscriptionGroupState? {
    return subscriptionGroups.firstOrNull { group -> group.id == DefaultSubscriptionGroupId }
        ?.takeIf {
            proxyServers.none { server -> server.groupId == DefaultSubscriptionGroupId }
        }
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
    val url = runCatching { Url(this) }.getOrNull() ?: return false
    val scheme = url.protocol.name.lowercase()
    return url.host.isNotBlank() &&
        scheme in SubscriptionUrlSchemes &&
        this.any(Char::isWhitespace).not()
}

private enum class InstallConfigSource(
    val scheme: String,
    val userAgent: String,
    val defaultName: String? = null,
) {
    V2rayNg(scheme = "v2rayng", userAgent = DefaultSubscriptionUserAgent, defaultName = V2rayNgDefaultSubscriptionName),
    Clash(scheme = "clash", userAgent = ClashMetaSubscriptionUserAgent, defaultName = ClashDefaultSubscriptionName),
    ClashMeta(scheme = "clashmeta", userAgent = ClashMetaSubscriptionUserAgent, defaultName = ClashDefaultSubscriptionName),
    FlClashX(
        scheme = "flclashx",
        userAgent = FlClashXSubscriptionUserAgent,
        defaultName = ClashDefaultSubscriptionName,
    ),
}

private fun Url.isSubscriptionInstallConfigUri(): Boolean {
    return installConfigSource() != null &&
        host.lowercase() in InstallConfigHosts
}

private fun Url.installConfigSource(): InstallConfigSource? {
    val uriScheme = protocol.name
    return InstallConfigSource.entries.firstOrNull { source ->
        source.scheme.equals(uriScheme, ignoreCase = true)
    }
}

private fun String.toSubscriptionUrlFragmentOrNull(): String? {
    return runCatching { Url(this).fragment }.getOrNull()
}

private const val V2rayNgDefaultSubscriptionName = "import sub"
private const val ClashDefaultSubscriptionName = "clashsub"
private val InstallConfigHosts = setOf("install-config", "install-sub")
private val SubscriptionUrlSchemes = setOf("https")
