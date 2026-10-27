// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.network

import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy

internal interface ProxyCredentials {
    val host: String
    val port: Int
    val username: String
    val password: String
}

internal fun ProxyCredentials.toSocksProxy(): Proxy =
    Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))

/** All application-owned changes to the process-wide authenticator use this lock. */
internal inline fun <T> ProxyCredentials?.withProxyAuthenticator(block: () -> T): T {
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

private fun ProxyCredentials.toAuthenticator(): Authenticator =
    object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestingHost != host || requestingPort != port) return null
            return PasswordAuthentication(username, password.toCharArray())
        }
    }

private val ProxyAuthenticatorLock = Any()
