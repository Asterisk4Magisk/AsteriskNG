// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy.latency

import android.content.Context
import android.os.SystemClock
import app.AppState
import app.ProxyServerState
import features.logs.AndroidAppLogger
import engine.xray.XrayConfigRequest
import engine.xray.XraySpeedTestConfigFactory
import engine.xray.initializeAndroidXrayCoreEnvironment
import engine.xray.prepareXrayCoreLogPaths
import features.resources.runtime.prepareXrayResourceFilePaths
import engine.network.NetworkDefaults
import engine.network.toPortOrNull
import features.proxy.server.model.Custom
import features.proxy.server.model.HTTP
import features.proxy.server.model.Hysteria2
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import features.proxy.server.model.Trojan
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import features.proxy.server.model.Wireguard
import features.proxy.server.model.customXrayConfigProxyOutboundEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

internal class AndroidProxyLatencyTester(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun test(
        appState: AppState,
        server: ProxyServerState,
        mode: ProxyServerLatencyTestMode,
    ): ProxyServerLatencyTestResult = withContext(Dispatchers.IO) {
        val elapsedMillis = when (mode) {
            ProxyServerLatencyTestMode.TcpConnect -> tcpConnectLatency(server)
            ProxyServerLatencyTestMode.RealConnection -> realConnectionLatency(appState, server)
        }
        ProxyServerLatencyTestResult(elapsedMillis)
    }

    private suspend fun tcpConnectLatency(server: ProxyServerState): Long {
        val endpoint = server.server.endpoint() ?: return FailedDelayMillis
        var bestMillis = FailedDelayMillis
        repeat(TcpConnectAttempts) {
            currentCoroutineContext().ensureActive()
            val millis = socketConnectTime(endpoint.host, endpoint.port)
            if (millis >= 0 && (bestMillis !in 0..millis)) {
                bestMillis = millis
            }
        }
        AndroidAppLogger.debug(LogTag, "TCP latency test serverId=${server.id} result=${bestMillis}ms")
        return bestMillis
    }

    private fun realConnectionLatency(appState: AppState, server: ProxyServerState): Long {
        return runCatching {
            val resourceFilePaths = appContext.prepareXrayResourceFilePaths()
            appContext.initializeAndroidXrayCoreEnvironment(resourceFilePaths.dataDir)
            val configJson = XraySpeedTestConfigFactory.buildXraySpeedTestConfig(
                XrayConfigRequest(
                    appState = appState,
                    selectedServer = server,
                    inbounds = emptyList(),
                    coreLogPaths = appContext.prepareXrayCoreLogPaths(),
                ),
            )
            Libv2ray.measureOutboundDelay(configJson, DelayTestUrl)
        }.onSuccess { millis ->
            AndroidAppLogger.debug(LogTag, "Real connection latency test serverId=${server.id} result=${millis}ms")
        }.onFailure { error ->
            AndroidAppLogger.warn(
                LogTag,
                "Real connection latency test failed serverId=${server.id}: ${error.logSummary()}",
            )
        }.getOrDefault(FailedDelayMillis)
    }

    private fun socketConnectTime(host: String, port: Int): Long {
        return runCatching {
            Socket().use { socket ->
                val startedAt = SystemClock.elapsedRealtime()
                socket.connect(InetSocketAddress(host, port), TcpConnectTimeoutMillis)
                SystemClock.elapsedRealtime() - startedAt
            }
        }.onFailure { error ->
            when (error) {
                is UnknownHostException -> AndroidAppLogger.debug(LogTag, "Unknown host for TCP latency test: $host")
                is IOException -> AndroidAppLogger.debug(LogTag, "TCP latency test IO failure: $host:$port ${error.message}")
                else -> AndroidAppLogger.warn(LogTag, "TCP latency test failed: $host:$port ${error.logSummary()}")
            }
        }.getOrDefault(FailedDelayMillis)
    }
}

private fun Throwable.logSummary(): String {
    val type = this::class.simpleName.orEmpty().ifBlank { "Throwable" }
    val detail = message
        ?.replace(WhitespaceRegex, " ")
        ?.trim()
        .orEmpty()
    return if (detail.isEmpty()) type else "$type: $detail"
}

enum class ProxyServerLatencyTestMode {
    TcpConnect,
    RealConnection,
}

data class ProxyServerLatencyTestResult(
    val elapsedMillis: Long,
) {
    companion object {
        val Failed = ProxyServerLatencyTestResult(elapsedMillis = -1L)
    }
}

private data class ProxyServerEndpoint(
    val host: String,
    val port: Int,
)

private fun ProxyServer<*>.endpoint(): ProxyServerEndpoint? {
    return when (this) {
        is HTTP -> endpoint(server, port)
        is Hysteria2 -> endpoint(server, port)
        is Shadowsocks -> endpoint(server, port)
        is Socks -> endpoint(server, port)
        is Trojan -> endpoint(server, port)
        is VLESS -> endpoint(server, port)
        is VMess -> endpoint(server, port)
        is Wireguard -> endpoint(server, port)
        is Custom -> customXrayConfigProxyOutboundEndpoint(configJson)
            ?.let { endpoint -> ProxyServerEndpoint(endpoint.host, endpoint.port) }
        else -> null
    }
}

private fun endpoint(host: String, port: String): ProxyServerEndpoint? {
    val parsedPort = port.toPortOrNull() ?: return null
    return host.trim()
        .takeIf(String::isNotEmpty)
        ?.let { ProxyServerEndpoint(it, parsedPort) }
}

private const val LogTag = "ProxyLatencyTest"
private const val DelayTestUrl = NetworkDefaults.CONNECTIVITY_CHECK_URL
private const val FailedDelayMillis = -1L
private const val TcpConnectAttempts = 2
private const val TcpConnectTimeoutMillis = 3_000
private val WhitespaceRegex = Regex("\\s+")
