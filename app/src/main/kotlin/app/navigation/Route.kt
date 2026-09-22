// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.navigation

import top.yukonga.miuix.kmp.nav.core.NavKey
import features.proxy.server.model.ProxyServer
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Type-safe navigation keys for Miuix navigation.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object About : Route

    @Serializable
    data object License : Route

    @Serializable
    data object CoreLogs : Route

    @Serializable
    data object AccessLogs : Route

    @Serializable
    data object LogcatLogs : Route

    @Serializable
    data object ResourceManagement : Route

    @Serializable
    data object SubscriptionGroupList : Route

    @Serializable
    data class ProxyServerEditor(
        @Serializable(with = ProxyServerRouteSerializer::class)
        val ps: ProxyServer<*>,
        val serverId: Int? = null,
        val groupId: Int? = null,
        val returnGroupId: Int? = null,
        val resultKey: String? = null,
        // Keep saved entry state independent of the mutable proxy configuration.
        val entryId: String = UUID.randomUUID().toString(),
    ) : Route
}

data class ProxyServerEditResult(
    val serverId: Int,
    val server: ProxyServer<*>,
    val groupId: Int? = null,
    val returnGroupId: Int? = null,
)
