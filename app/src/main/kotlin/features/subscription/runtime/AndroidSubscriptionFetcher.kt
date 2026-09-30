// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import android.content.Context
import data.AppSettingsPreferences
import features.subscription.DefaultSubscriptionUserAgent
import features.subscription.SubscriptionHttpException
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyOptions
import engine.proxy.LocalProxyRuntime
import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import utils.runCancellableHttpRequest
import utils.encodeBase64
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
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
    ): String {
        val proxy = options.toProxy()
        return runCancellableHttpRequest { track ->
            val requestCredentials = options.toRequestCredentials(
                installationHwid = installationHwid,
                ageCrypto = ageCrypto,
            )
            proxy.withAuthenticator {
                fetchWithRedirects(
                    track = track,
                    url = url.toIdnUrl(),
                    userAgent = userAgent.ifBlank { DefaultSubscriptionUserAgent },
                    requestCredentials = requestCredentials,
                    proxy = proxy,
                )
            }.decryptAgeArmoredOrPassThrough(
                secretKey = options.ageSecretKey,
                ageCrypto = ageCrypto,
            )
        }
    }
}

internal data class AndroidSubscriptionFetchOptions(
    val useRunningProxy: Boolean = false,
    val fallbackProxy: LocalProxyOptions? = null,
    val hwid: String = "",
    val ageSecretKey: String = "",
)

private data class SubscriptionRequestCredentials(
    val hwid: String,
    val agePublicKey: String?,
)

internal data class AndroidSubscriptionProxy(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

private const val MaxRedirects = 3
private val ProxyAuthenticatorLock = Any()

internal suspend fun AndroidSubscriptionFetchOptions.toProxy(): AndroidSubscriptionProxy? {
    if (!useRunningProxy) return null
    val runtimeOptions = availableLocalProxy(fallbackProxy) ?: return null
    return AndroidSubscriptionProxy(
        host = LocalProxyLoopbackAddress,
        port = runtimeOptions.port,
        username = runtimeOptions.username,
        password = runtimeOptions.password,
    )
}

/** Resolve a usable loopback SOCKS proxy without querying VPN/ROOT service state. */
private suspend fun availableLocalProxy(
    configuredOptions: LocalProxyOptions?,
): LocalProxyOptions? = withContext(Dispatchers.IO) {
    listOfNotNull(LocalProxyRuntime.current(), configuredOptions)
        .distinct()
        .firstOrNull { options ->
            ensureActive()
            options.acceptsSocksAuthentication()
        }
}

private fun LocalProxyOptions.acceptsSocksAuthentication(): Boolean {
    if (port !in 1..65535) return false
    val requiresAuthentication = username.isNotBlank()
    val userBytes = username.toByteArray(Charsets.UTF_8)
    val passwordBytes = password.toByteArray(Charsets.UTF_8)
    if (requiresAuthentication && (userBytes.size > 255 || passwordBytes.size > 255)) return false

    return try {
        Socket().use { socket ->
            socket.soTimeout = ProxyProbeTimeoutMillis
            socket.connect(
                InetSocketAddress(LocalProxyLoopbackAddress, port),
                ProxyProbeTimeoutMillis,
            )
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val method = if (requiresAuthentication) 2 else 0
            output.write(byteArrayOf(5, 1, method.toByte()))
            output.flush()
            if (input.read() != 5 || input.read() != method) return@use false
            if (!requiresAuthentication) return@use true

            output.write(byteArrayOf(1, userBytes.size.toByte()))
            output.write(userBytes)
            output.write(passwordBytes.size)
            output.write(passwordBytes)
            output.flush()
            input.read() == 1 && input.read() == 0
        }
    } catch (_: IOException) {
        false
    }
}

private const val ProxyProbeTimeoutMillis = 500

private fun fetchWithRedirects(
    track: (HttpURLConnection) -> Unit,
    url: String,
    userAgent: String,
    requestCredentials: SubscriptionRequestCredentials,
    proxy: AndroidSubscriptionProxy?,
): String {
    var currentUrl = url
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

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: error("Redirect location missing")
                currentUrl = URI(currentUrl).resolve(location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                throw SubscriptionHttpException(code)
            }
            return connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
        }
    }
    error("Too many redirects")
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
        URI(this).toURL().openConnection(proxy.toJavaProxy())
    }
    return (connection as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = false
        requestMethod = "GET"
    }
}

private fun AndroidSubscriptionProxy.toJavaProxy(): Proxy {
    return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
}

private inline fun <T> AndroidSubscriptionProxy?.withAuthenticator(block: () -> T): T {
    if (this == null || username.isBlank()) return block()
    synchronized(ProxyAuthenticatorLock) {
        Authenticator.setDefault(toAuthenticator())
        return try {
            block()
        } finally {
            Authenticator.setDefault(null)
        }
    }
}

private fun AndroidSubscriptionProxy.toAuthenticator(): Authenticator {
    return object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestingHost != host || requestingPort != port) return null
            return PasswordAuthentication(username, password.toCharArray())
        }
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
