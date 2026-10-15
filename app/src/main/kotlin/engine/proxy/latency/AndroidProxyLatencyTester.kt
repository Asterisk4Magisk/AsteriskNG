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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import java.io.IOException
import java.net.InetAddress
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
        when (mode) {
            ProxyServerLatencyTestMode.Handshake -> handshakeLatency(server)
            ProxyServerLatencyTestMode.RealConnection -> realConnectionLatency(appState, server).toLatencyTestResult()
        }
    }

    private suspend fun handshakeLatency(server: ProxyServerState): ProxyServerLatencyTestResult {
        return when (val target = server.server.handshakeLatencyTarget()) {
            is HandshakeLatencyTarget.TcpEndpoint -> tcpConnectLatency(server, target)
            is HandshakeLatencyTarget.QuicEndpoint -> quicHandshakeLatency(server, target)
            is HandshakeLatencyTarget.WireGuardEndpoint -> wireGuardHandshakeLatency(server, target)
            HandshakeLatencyTarget.UdpMasked,
            HandshakeLatencyTarget.Connectionless,
            HandshakeLatencyTarget.NoSingleEndpoint,
            -> {
                AndroidAppLogger.debug(LogTag, "Handshake latency test unsupported serverId=${server.id} target=$target")
                ProxyServerLatencyTestResult.Unsupported
            }
            HandshakeLatencyTarget.InvalidEndpoint -> {
                AndroidAppLogger.debug(LogTag, "Handshake latency test has no valid endpoint serverId=${server.id}")
                ProxyServerLatencyTestResult.Failed
            }
        }
    }

    private suspend fun tcpConnectLatency(
        server: ProxyServerState,
        endpoint: HandshakeLatencyTarget.TcpEndpoint,
    ): ProxyServerLatencyTestResult {
        var bestMillis = FailedDelayMillis
        repeat(TcpConnectAttempts) {
            currentCoroutineContext().ensureActive()
            val millis = socketConnectTime(endpoint.host, endpoint.port)
            if (millis >= 0 && (bestMillis !in 0..millis)) {
                bestMillis = millis
            }
        }
        AndroidAppLogger.debug(LogTag, "TCP latency test serverId=${server.id} result=${bestMillis}ms")
        return bestMillis.toLatencyTestResult()
    }

    // Resolves first so that, like the TCP connect, DNS lookup is not part of the measurement.
    private suspend fun quicHandshakeLatency(
        server: ProxyServerState,
        target: HandshakeLatencyTarget.QuicEndpoint,
    ): ProxyServerLatencyTestResult {
        val address = runCatching { InetAddress.getByName(target.host).hostAddress }.getOrNull()
        if (address == null) {
            AndroidAppLogger.debug(LogTag, "Unknown host for QUIC latency test: ${target.host}")
            return ProxyServerLatencyTestResult.Failed
        }
        val request = quicHandshakeRequestJson(address, target.port, target.serverName, HandshakeTimeoutMillis)
        var bestMillis = FailedDelayMillis
        repeat(HandshakeAttempts) {
            currentCoroutineContext().ensureActive()
            val startedAt = System.nanoTime()
            val error = runCatching { quicHandshakeError(Libv2ray.fetchQuicCertSha256(request)) }
                .getOrElse { error -> error.logSummary() }
            val millis = (System.nanoTime() - startedAt) / NanosPerMilli
            if (error != null) {
                AndroidAppLogger.debug(LogTag, "QUIC latency test failure: ${target.host}:${target.port} $error")
            } else if (bestMillis !in 0..millis) {
                bestMillis = millis
            }
        }
        AndroidAppLogger.debug(LogTag, "QUIC latency test serverId=${server.id} result=${bestMillis}ms")
        return bestMillis.toLatencyTestResult()
    }

    private fun wireGuardHandshakeLatency(
        server: ProxyServerState,
        target: HandshakeLatencyTarget.WireGuardEndpoint,
    ): ProxyServerLatencyTestResult {
        val request = wireGuardHandshakeRequestJson(target.host, target.port, target.keys, HandshakeTimeoutMillis)
        val (millis, error) = runCatching { wireGuardHandshakeResult(Libv2ray.measureWireGuardHandshake(request)) }
            .getOrElse { error -> FailedDelayMillis to error.logSummary() }
        if (error.isNotBlank()) {
            AndroidAppLogger.debug(LogTag, "WireGuard latency test failure: ${target.host}:${target.port} $error")
        }
        AndroidAppLogger.debug(LogTag, "WireGuard latency test serverId=${server.id} result=${millis}ms")
        return millis.toLatencyTestResult()
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
    /** Times the transport handshake with the server: TCP connect, QUIC or WireGuard handshake. */
    Handshake,

    /** Times an HTTP request relayed through the proxy. */
    RealConnection,
}

sealed interface ProxyServerLatencyTestResult {
    data class Success(val elapsedMillis: Long) : ProxyServerLatencyTestResult

    data object Failed : ProxyServerLatencyTestResult

    /** The selected test mode cannot measure this server, e.g. a handshake test on mKCP. */
    data object Unsupported : ProxyServerLatencyTestResult
}

private fun Long.toLatencyTestResult(): ProxyServerLatencyTestResult {
    return if (this >= 0) ProxyServerLatencyTestResult.Success(this) else ProxyServerLatencyTestResult.Failed
}

private const val LogTag = "ProxyLatencyTest"
private const val DelayTestUrl = NetworkDefaults.CONNECTIVITY_CHECK_URL
private const val FailedDelayMillis = -1L
private const val TcpConnectAttempts = 2
private const val TcpConnectTimeoutMillis = 3_000
private const val HandshakeAttempts = 2
private const val HandshakeTimeoutMillis = 3_000L
private const val NanosPerMilli = 1_000_000L
private val WhitespaceRegex = Regex("\\s+")
