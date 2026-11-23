// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import engine.network.withProxyAuthenticator
import engine.network.toSocksProxy
import android.content.Context
import data.AppSettingsPreferences
import features.subscription.DefaultSubscriptionUserAgent
import features.subscription.SubscriptionHttpException
import features.proxy.server.usecase.readProxyServerImportText
import features.proxy.server.usecase.requireProxyServerImportSize
import utils.decodeContentDispositionFilename
import utils.runCancellableHttpRequest
import utils.encodeBase64
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL

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
            val requestCredentials = options.toRequestCredentials(
                installationHwid = installationHwid,
                ageCrypto = ageCrypto,
            )
            proxy.withProxyAuthenticator {
                fetchWithRedirects(
                    track = track,
                    url = url.toIdnUrl(),
                    userAgent = userAgent.ifBlank { DefaultSubscriptionUserAgent },
                    requestCredentials = requestCredentials,
                    proxy = proxy,
                    headers = options.headers,
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

private data class SubscriptionRequestCredentials(
    val hwid: String,
    val agePublicKey: String?,
)

private const val MaxRedirects = 3

private fun fetchWithRedirects(
    track: (HttpURLConnection) -> Unit,
    url: String,
    userAgent: String,
    requestCredentials: SubscriptionRequestCredentials,
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
            connection.setRequestProperty("X-Hwid", requestCredentials.hwid)
            requestCredentials.agePublicKey?.let { publicKey ->
                connection.setRequestProperty("X-Age-Public-Key", publicKey)
            }
            connection.setEmbeddedBasicAuth(currentUrl)
            currentHeaders.forEach { (name, values) ->
                values.forEachIndexed { index, value ->
                    if (index == 0) connection.setRequestProperty(name, value)
                    else connection.addRequestProperty(name, value)
                }
            }

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: error("Redirect location missing")
                val nextUrl = URI(currentUrl).resolve(location).toString()
                if (!sameSubscriptionOrigin(currentUrl, nextUrl)) {
                    currentHeaders = emptyMap()
                }
                currentUrl = nextUrl
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

private fun sameSubscriptionOrigin(first: String, second: String): Boolean {
    val a = URI(first)
    val b = URI(second)
    fun URI.effectivePort(): Int = if (port >= 0) port else if (scheme.equals("https", true)) 443 else 80
    return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && a.effectivePort() == b.effectivePort()
}

private fun AndroidSubscriptionFetchOptions.toRequestCredentials(
    installationHwid: String,
    ageCrypto: SubscriptionAgeCrypto,
): SubscriptionRequestCredentials {
    val secretKey = ageSecretKey.trim()
    return SubscriptionRequestCredentials(
        hwid = hwid.trim().ifBlank { installationHwid },
        agePublicKey = secretKey.takeIf(String::isNotEmpty)?.let(ageCrypto::publicKey),
    )
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

private fun String.toConnection(proxy: AndroidSubscriptionProxy?): HttpURLConnection {
    val connection = if (proxy == null) {
        URI(this).toURL().openConnection()
    } else {
        URI(this).toURL().openConnection(proxy.toSocksProxy())
    }
    return (connection as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = false
        requestMethod = "GET"
    }
}

private fun HttpURLConnection.setEmbeddedBasicAuth(rawUrl: String) {
    val userInfo = runCatching { URL(rawUrl).userInfo }.getOrNull() ?: return
    val parts = userInfo.split(":", limit = 2)
    val user = parts.getOrElse(0) { "" }
    val password = parts.getOrElse(1) { "" }
    val token = "$user:$password".encodeBase64()
    setRequestProperty("Authorization", "Basic $token")
}

private fun String.toIdnUrl(): String {
    val parsedUrl = URL(this)
    val host = parsedUrl.host
    val asciiHost = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
    return if (host == asciiHost) this else replace(host, asciiHost)
}

private const val AgeArmorHeader = "-----BEGIN AGE ENCRYPTED FILE-----"
