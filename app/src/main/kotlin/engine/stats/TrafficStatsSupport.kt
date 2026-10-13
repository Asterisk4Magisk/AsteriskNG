// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import android.content.Context
import androidx.core.content.edit
import engine.xray.XrayStatsApiTag
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one traffic sampling session. Lifecycle methods are called on the service
 * main thread; workers can publish notification changes only through their session.
 */
internal class TrafficStatsMonitor(
    private val scope: CoroutineScope,
    private val lifecycleDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private var session: Session? = null
    private var job: Job? = null

    val isActive: Boolean
        get() = job?.isActive == true

    fun start(block: suspend Session.() -> Unit) {
        cancel()
        val next = Session()
        session = next
        job = scope.launch { next.block() }
    }

    fun cancel() {
        session = null
        job?.cancel()
        job = null
    }

    inner class Session internal constructor() {
        suspend fun publish(action: () -> Unit) {
            withContext(lifecycleDispatcher) {
                currentCoroutineContext().ensureActive()
                if (session === this@Session) action()
            }
        }
    }
}

internal fun tryStartTrafficStatsService(
    start: () -> Unit,
    onFailure: (Exception) -> Unit,
): Boolean {
    return try {
        start()
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        onFailure(error)
        false
    }
}

internal data class ProxyTrafficStatsRuntime(
    val listenAddress: String,
    val port: Int,
    val serverName: String,
    val apiTag: String = XrayStatsApiTag,
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
            apiTag = preferences.getString(KeyApiTag, XrayStatsApiTag)
                ?.takeIf(String::isNotBlank)
                ?: XrayStatsApiTag,
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
            putString(KeyApiTag, runtime.apiTag)
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
private const val KeyApiTag = "api_tag"
