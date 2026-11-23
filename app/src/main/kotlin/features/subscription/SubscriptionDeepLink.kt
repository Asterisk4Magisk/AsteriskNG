// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import android.content.Intent
import android.net.Uri
import io.ktor.http.Url
import utils.decodeUrlComponentPreservingPlus

internal data class SubscriptionInstallConfig(
    val name: String,
    val url: String,
    val userAgent: String,
)

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

internal fun Uri.isSubscriptionInstallConfigUri(): Boolean {
    return runCatching { Url(toString()).isSubscriptionInstallConfigUri() }
        .getOrDefault(false)
}

private fun Url.toSubscriptionInstallConfigOrNull(rawValue: String): SubscriptionInstallConfig? {
    toRawHttpsSubscriptionInstallConfigOrNull(rawValue)?.let { return it }
    val source = installConfigSource() ?: return null
    val url = parameters["url"]?.trim().orEmpty()
    if (!isSubscriptionInstallConfigUri() || !url.isValidSubscriptionInstallUrl()) return null
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
    if (!rawValue.isValidSubscriptionInstallUrl()) return null
    val name = listOfNotNull(fragment, V2rayNgDefaultSubscriptionName)
        .firstNotNullOfOrNull { value -> value.trim().decodeUrlComponentPreservingPlus().takeIf(String::isNotBlank) }
        ?: return null
    return SubscriptionInstallConfig(
        name = name,
        url = rawValue,
        userAgent = DefaultSubscriptionUserAgent,
    )
}

private enum class InstallConfigSource(
    val scheme: String,
    val userAgent: String,
    val defaultName: String? = null,
) {
    V2rayNg(scheme = "v2rayng", userAgent = DefaultSubscriptionUserAgent, defaultName = V2rayNgDefaultSubscriptionName),
    Clash(scheme = "clash", userAgent = ClashMetaSubscriptionUserAgent, defaultName = ClashDefaultSubscriptionName),
    ClashMeta(scheme = "clashmeta", userAgent = ClashMetaSubscriptionUserAgent, defaultName = ClashDefaultSubscriptionName),
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
