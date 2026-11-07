// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import utils.decodeFlexibleBase64ToStringOrRaw
import utils.putIfNotBlank
import utils.userInfoOrNull

// Shared by the HTTP and SOCKS servers, which carry the same address and optional credentials.

/** Xray `settings` of a server with an optional user; the password is sent only with a user. */
internal fun userPasswordOutboundSettings(
    server: String,
    port: String,
    user: String?,
    password: String?,
): JsonObject = buildJsonObject {
    put("address", server)
    put("port", port.toXrayPort())
    putIfNotBlank("user", user)
    if (!user.isNullOrBlank()) {
        put("pass", password.orEmpty())
    }
}

/** The `user:password` pair in a share URL's user info, Base64-encoded or plain; null without a colon. */
internal fun Url.userPasswordOrNull(): Pair<String, String>? {
    val info = userInfoOrNull()?.decodeFlexibleBase64ToStringOrRaw() ?: return null
    val pos = info.indexOf(':')
    if (pos < 0) return null
    return info.substring(0, pos) to info.substring(pos + 1)
}

/** A share URL carrying `user:password` Base64-encoded in its user info when a user is set. */
internal fun userPasswordProxyUrl(
    protocol: URLProtocol,
    server: String,
    port: String,
    user: String?,
    password: String?,
    remarks: String,
): String = URLBuilder().apply {
    this.protocol = protocol
    setProxyUrlHost(server)
    port.toIntOrNull()?.let { this.port = it }
    if (!user.isNullOrBlank()) {
        this.user = "$user:${password.orEmpty()}".encodeToByteArray().encodeProxyUrlBase64()
    }
    fragment = remarks
}.buildString()
