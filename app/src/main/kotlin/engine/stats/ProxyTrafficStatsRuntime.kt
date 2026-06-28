// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import android.content.Context
import androidx.core.content.edit

internal data class ProxyTrafficStatsRuntime(
    val listenAddress: String,
    val port: Int,
    val serverName: String,
)

internal object ProxyTrafficStatsRuntimeStore {
    fun read(context: Context): ProxyTrafficStatsRuntime? {
        val preferences = context.preferences()
        val port = preferences.getInt(KeyPort, 0).takeIf { value -> value > 0 } ?: return null
        return ProxyTrafficStatsRuntime(
            listenAddress = preferences.getString(KeyListenAddress, XrayStatsApiListenAddress)
                ?.takeIf(String::isNotBlank)
                ?: XrayStatsApiListenAddress,
            port = port,
            serverName = preferences.getString(KeyServerName, "").orEmpty(),
        )
    }

    fun readPort(context: Context): Int? {
        return context.preferences().getInt(KeyPort, 0).takeIf { value -> value > 0 }
    }

    fun write(
        context: Context,
        runtime: ProxyTrafficStatsRuntime,
    ) {
        context.preferences().edit {
            putString(KeyListenAddress, runtime.listenAddress)
            putInt(KeyPort, runtime.port)
            putString(KeyServerName, runtime.serverName)
        }
    }

    private fun Context.preferences() = applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )
}

private const val PreferencesName = "proxy_traffic_stats"
private const val KeyListenAddress = "listen_address"
private const val KeyPort = "port"
private const val KeyServerName = "server_name"
