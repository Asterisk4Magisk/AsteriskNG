// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import features.subscription.resolveSubscriptionRequestHeaders
import engine.network.withProxyAuthenticator
import engine.network.toSocksProxy
import android.content.Context
import data.AppSettingsPreferences
import features.subscription.DefaultSubscriptionUserAgent
import features.subscription.SubscriptionHttpException
import features.subscription.hasSameSubscriptionOrigin
import features.subscription.isSubscriptionRedirect
import features.subscription.resolveSubscriptionRedirect
import features.subscription.toSubscriptionRequestUrl
import features.proxy.server.usecase.readProxyServerImportText
import features.proxy.server.usecase.requireProxyServerImportSize
import utils.decodeContentDispositionFilename
import utils.runCancellableHttpRequest
import utils.encodeBase64
import java.net.HttpURLConnection
import java.net.URI

internal class AndroidSubscriptionFetcher(
    private val installationHwid: String,
    private val ageCrypto: SubscriptionAgeCrypto,
) {
    constructor(context: Context) : this(
        installationHwid = AppSettingsPreferences(context.applicationContext).getOrCreateSubscriptionHwid(),
        ageCrypto = KageSubscriptionAgeCrypto,
    )

    suspend fun fetch(
        url: String,
        userAgent: String,
        options: AndroidSubscriptionFetchOptions,
    ): String = fetchSubscription(url, userAgent, options).text

    suspend fun fetchSubscription(
        url: String,
        userAgent: String,
        options: AndroidSubscriptionFetchOptions,
    ): AndroidSubscriptionResponse {
        val proxy = options.toProxy()
        return runCancellableHttpRequest { track ->
            val agePublicKey = options.ageSecretKey.trim().takeIf(String::isNotEmpty)?.let(ageCrypto::publicKey)
            proxy.withProxyAuthenticator {
                fetchWithRedirects(
                    track = track,
                    url = URI(url),
                    userAgent = userAgent.ifBlank { DefaultSubscriptionUserAgent },
                    agePublicKey = agePublicKey,
                    proxy = proxy,
                    headers = resolveSubscriptionRequestHeaders(
                        options.requestHeaders,
                    ) { installationHwid }.mapValues { (_, value) -> listOf(value) } + options.headers,
                )
            }.let { response ->
                response.copy(
                    text = response.text.decryptAgeArmoredOrPassThrough(
                        secretKey = options.ageSecretKey,
                        ageCrypto = ageCrypto,
                    ),
                )
            }
        }
    }
}

/**
 * [remoteName] is the decoded `Content-Disposition` filename, which
 * subscription platforms set to the subscription's title.
 */
internal data class AndroidSubscriptionResponse(
    val text: String,
    val remoteName: String? = null,
)

private const val MaxRedirects = 3

private fun fetchWithRedirects(
    track: (HttpURLConnection) -> Unit,
    url: URI,
    userAgent: String,
    agePublicKey: String?,
    proxy: AndroidSubscriptionProxy?,
    headers: Map<String, List<String>>,
): AndroidSubscriptionResponse {
    var currentUrl = url
    var currentHeaders = headers
    repeat(MaxRedirects) {
        val connection = currentUrl.toConnection(proxy)
        try {
            track(connection)
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Connection", "close")
            agePublicKey?.let { publicKey ->
                connection.setRequestProperty("X-Age-Public-Key", publicKey)
            }
            connection.setEmbeddedBasicAuth()
            currentHeaders.forEach { (name, values) ->
                values.forEachIndexed { index, value ->
                    if (index == 0) connection.setRequestProperty(name, value)
                    else connection.addRequestProperty(name, value)
                }
            }

            val code = connection.responseCode
            if (isSubscriptionRedirect(code)) {
                val location = connection.getHeaderField("Location")
                    ?: error("Redirect location missing")
                val nextUri = resolveSubscriptionRedirect(currentUrl, location)
                if (!currentUrl.hasSameSubscriptionOrigin(nextUri)) {
                    currentHeaders = emptyMap()
                }
                currentUrl = nextUri
                return@repeat
            }
            if (code !in 200..299) {
                throw SubscriptionHttpException(code)
            }
            requireProxyServerImportSize(connection.contentLengthLong)
            return AndroidSubscriptionResponse(
                text = connection.inputStream.use { it.readProxyServerImportText() },
                remoteName = decodeContentDispositionFilename(connection.getHeaderField("Content-Disposition")),
            )
        } finally {
            connection.disconnect()
        }
    }
    error("Too many redirects")
}

private fun String.decryptAgeArmoredOrPassThrough(
    secretKey: String,
    ageCrypto: SubscriptionAgeCrypto,
): String {
    if (!trimStart().startsWith(AgeArmorHeader)) return this
    val normalizedSecretKey = secretKey.trim()
    require(normalizedSecretKey.isNotEmpty()) {
        "Age-encrypted subscription requires a secret key"
    }
    return ageCrypto.decryptArmored(this, normalizedSecretKey)
}

private fun URI.toConnection(proxy: AndroidSubscriptionProxy?): HttpURLConnection {
    val url = toSubscriptionRequestUrl()
    val connection = if (proxy == null) {
        url.openConnection()
    } else {
        url.openConnection(proxy.toSocksProxy())
    }
    return (connection as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = false
        requestMethod = "GET"
    }
}

private fun HttpURLConnection.setEmbeddedBasicAuth() {
    val userInfo = url.userInfo ?: return
    val parts = userInfo.split(":", limit = 2)
    val user = parts.getOrElse(0) { "" }
    val password = parts.getOrElse(1) { "" }
    val token = "$user:$password".encodeBase64()
    setRequestProperty("Authorization", "Basic $token")
}

private const val AgeArmorHeader = "-----BEGIN AGE ENCRYPTED FILE-----"
