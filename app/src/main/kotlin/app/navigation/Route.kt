// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.navigation

import androidx.navigation3.runtime.NavKey
import features.proxy.server.model.ProxyServer
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation keys for Navigation3.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
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
    data class SubscriptionGroup(val groupId: Int? = null) : Route

    @Serializable
    data class ProxyServerEditor(
        val ps: ProxyServer<*>,
        val serverId: Int? = null,
        val groupId: Int? = null,
        val returnGroupId: Int? = null,
        val resultKey: String? = null,
    ) : Route
}

data class ProxyServerEditResult(
    val serverId: Int,
    val server: ProxyServer<*>,
    val groupId: Int? = null,
    val returnGroupId: Int? = null,
)
