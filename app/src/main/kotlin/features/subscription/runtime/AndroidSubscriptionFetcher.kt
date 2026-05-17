package features.subscription.runtime

import features.subscription.DefaultSubscriptionUserAgent
import engine.vpn.LocalProxyLoopbackAddress
import engine.vpn.VpnLocalProxyRuntime
import engine.network.NetworkLimits
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.Base64

internal class AndroidSubscriptionFetcher {
    fun fetch(
        url: String,
        userAgent: String,
        options: AndroidSubscriptionFetchOptions,
    ): String {
        val proxy = options.toProxy()
        return proxy.withAuthenticator {
            fetchWithRedirects(
                url = url.toIdnUrl(),
                userAgent = userAgent.ifBlank { DefaultSubscriptionUserAgent },
                proxy = proxy,
            )
        }
    }
}

internal data class AndroidSubscriptionFetchOptions(
    val useRunningProxy: Boolean,
    val fallbackProxyPort: Int?,
    val fallbackProxyUsername: String,
    val fallbackProxyPassword: String,
)

private data class AndroidSubscriptionProxy(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

private const val MaxRedirects = 3
private val ProxyAuthenticatorLock = Any()

private fun AndroidSubscriptionFetchOptions.toProxy(): AndroidSubscriptionProxy? {
    if (!useRunningProxy) return null
    val runtimeOptions = VpnLocalProxyRuntime.current()
    val port = runtimeOptions?.port
        ?: fallbackProxyPort?.takeIf { it in NetworkLimits.PORT_MIN..NetworkLimits.PORT_MAX }
        ?: return null
    return AndroidSubscriptionProxy(
        host = LocalProxyLoopbackAddress,
        port = port,
        username = runtimeOptions?.username ?: fallbackProxyUsername,
        password = runtimeOptions?.password ?: fallbackProxyPassword,
    )
}

private fun fetchWithRedirects(
    url: String,
    userAgent: String,
    proxy: AndroidSubscriptionProxy?,
): String {
    var currentUrl = url
    repeat(MaxRedirects) {
        val connection = currentUrl.toConnection(proxy)
        try {
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Connection", "close")
            connection.setEmbeddedBasicAuth(currentUrl)

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: error("Redirect location missing")
                currentUrl = URI(currentUrl).resolve(location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                error("HTTP $code")
            }
            return connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
        }
    }
    error("Too many redirects")
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
    val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    setRequestProperty("Authorization", "Basic $token")
}

private fun String.toIdnUrl(): String {
    val parsedUrl = URL(this)
    val host = parsedUrl.host
    val asciiHost = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
    return if (host == asciiHost) this else replace(host, asciiHost)
}
