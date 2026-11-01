// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import app.modes.ProxyAppListModeGlobal
import engine.hevtun.DefaultHevSocks5TunnelLogLevel
import engine.hevtun.DefaultHevSocks5TunnelTcpReadWriteTimeoutMillis
import engine.hevtun.DefaultHevSocks5TunnelUdpReadWriteTimeoutMillis
import engine.hevtun.HevSocks5TunnelConfig
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyOptions
import engine.xray.XrayCoreLogPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

internal object AsteriskVpnServiceIntents {
    const val ACTION_START = "app.action.START_VPN"
    const val ACTION_STOP = "app.action.STOP_VPN"

    /**
     * Starts the service without an embedded configuration. Used by the boot receiver and by
     * the system's always-on VPN start; both carry no configuration because the service hands
     * the start back to the app process, which compiles it from the persisted app state.
     */
    const val ACTION_RESUME = "app.action.RESUME_VPN"

    fun resumeIntent(context: Context): Intent {
        return Intent(context, AsteriskVpnService::class.java).apply {
            action = ACTION_RESUME
        }
    }

    fun startIntent(context: Context, config: VpnServiceStartConfig): Intent {
        return Intent(context, AsteriskVpnService::class.java).apply {
            action = ACTION_START
            writeStartConfig(config)
        }
    }

    fun stopIntent(context: Context): Intent {
        return Intent(context, AsteriskVpnService::class.java).apply {
            action = ACTION_STOP
        }
    }
}

private const val EXTRA_SERVICE_REPLY = "service_reply"
private const val EXTRA_SERVICE_REPLY_MESSAGE = "message"
private const val ServiceReplySuccess = 0
private const val ServiceReplyFailure = 1

internal fun Intent.putServiceReply(reply: ResultReceiver) {
    putExtra(EXTRA_SERVICE_REPLY, reply)
}

internal fun Intent.serviceReply(): ResultReceiver? {
    @Suppress("DEPRECATION")
    return getParcelableExtra(EXTRA_SERVICE_REPLY)
}

internal fun ResultReceiver.sendServiceResult(result: Result<Unit>) {
    val error = result.exceptionOrNull()
    if (error == null) {
        send(ServiceReplySuccess, null)
    } else {
        send(
            ServiceReplyFailure,
            Bundle().apply {
                putString(EXTRA_SERVICE_REPLY_MESSAGE, error.message ?: error::class.java.simpleName)
            },
        )
    }
}

/**
 * Sends [intent] to the VPN Service, which may run in another process, and waits for its reply.
 * A service that never answers is a failed call rather than a cancellation, so callers can fall
 * back or report it like any other failure.
 */
internal suspend fun awaitServiceCall(context: Context, intent: Intent, timeoutMillis: Long) {
    val reply = CompletableDeferred<Result<Unit>>()
    intent.putServiceReply(
        object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                reply.complete(
                    if (resultCode == ServiceReplySuccess) {
                        Result.success(Unit)
                    } else {
                        Result.failure(
                            IllegalStateException(resultData?.getString(EXTRA_SERVICE_REPLY_MESSAGE).orEmpty()),
                        )
                    },
                )
            }
        },
    )
    context.startService(intent)
    val result = try {
        withTimeout(timeoutMillis.milliseconds) { reply.await() }
    } catch (error: TimeoutCancellationException) {
        Result.failure(IllegalStateException("VPN Service did not answer ${intent.action}", error))
    }
    result.getOrThrow()
}

internal fun Intent.readVpnServiceStartConfig(): VpnServiceStartConfig? {
    val sessionName = getStringExtra(EXTRA_SESSION_NAME) ?: return null
    val ipv4Address = getStringExtra(EXTRA_IPV4_ADDRESS) ?: return null
    val xrayConfigJson = getStringExtra(EXTRA_XRAY_CONFIG_JSON) ?: return null
    return VpnServiceStartConfig(
        sessionName = sessionName,
        mtu = getIntExtra(EXTRA_MTU, VpnDefaults.MTU),
        ipv4Address = ipv4Address,
        ipv4PrefixLength = getIntExtra(EXTRA_IPV4_PREFIX_LENGTH, defaultIpv4TunAddress.prefixLength),
        ipv6Address = getStringExtra(EXTRA_IPV6_ADDRESS),
        ipv6PrefixLength = getIntExtra(EXTRA_IPV6_PREFIX_LENGTH, defaultIpv6TunAddress.prefixLength),
        enableIpv6 = getBooleanExtra(EXTRA_ENABLE_IPV6, false),
        enableLocalDns = getBooleanExtra(EXTRA_ENABLE_LOCAL_DNS, true),
        dnsServers = getStringArrayExtra(EXTRA_DNS_SERVERS)?.toList().orEmpty().ifEmpty {
            listOf(VpnDefaults.IPV4_DNS)
        },
        xrayConfigJson = xrayConfigJson,
        applicationPolicy = VpnApplicationPolicy(
            mode = getIntExtra(EXTRA_PROXY_APP_LIST_MODE, ProxyAppListModeGlobal),
            packageNames = getStringArrayExtra(EXTRA_PROXY_APP_LIST_PACKAGES)?.toList().orEmpty(),
        ),
        localProxyOptions = LocalProxyOptions(
            listenAddress = getStringExtra(EXTRA_LOCAL_PROXY_LISTEN_ADDRESS).orEmpty().ifBlank {
                LocalProxyLoopbackAddress
            },
            port = getIntExtra(EXTRA_LOCAL_PROXY_PORT, VpnDefaults.LOCAL_PROXY_PORT),
            username = getStringExtra(EXTRA_LOCAL_PROXY_USERNAME).orEmpty(),
            password = getStringExtra(EXTRA_LOCAL_PROXY_PASSWORD).orEmpty(),
        ),
        appendHttpProxyOptions = VpnAppendHttpProxyOptions(
            enabled = getBooleanExtra(EXTRA_APPEND_HTTP_PROXY_ENABLED, false),
            port = getIntExtra(EXTRA_APPEND_HTTP_PROXY_PORT, VpnDefaults.VPN_APPEND_HTTP_PROXY_FALLBACK_PORT),
        ),
        coreLogPaths = XrayCoreLogPaths(
            accessLogPath = getStringExtra(EXTRA_ACCESS_LOG_PATH).orEmpty(),
            errorLogPath = getStringExtra(EXTRA_ERROR_LOG_PATH).orEmpty(),
        ),
        enableAccessLog = getBooleanExtra(EXTRA_ENABLE_ACCESS_LOG, false),
        dataDir = getStringExtra(EXTRA_DATA_DIR).orEmpty(),
        hevSocks5TunnelConfig = readHevSocks5TunnelConfig(),
    )
}

private fun Intent.writeStartConfig(config: VpnServiceStartConfig) {
    putExtra(EXTRA_SESSION_NAME, config.sessionName)
    putExtra(EXTRA_MTU, config.mtu)
    putExtra(EXTRA_IPV4_ADDRESS, config.ipv4Address)
    putExtra(EXTRA_IPV4_PREFIX_LENGTH, config.ipv4PrefixLength)
    putExtra(EXTRA_IPV6_ADDRESS, config.ipv6Address)
    putExtra(EXTRA_IPV6_PREFIX_LENGTH, config.ipv6PrefixLength)
    putExtra(EXTRA_ENABLE_IPV6, config.enableIpv6)
    putExtra(EXTRA_ENABLE_LOCAL_DNS, config.enableLocalDns)
    putExtra(EXTRA_DNS_SERVERS, config.dnsServers.toTypedArray())
    putExtra(EXTRA_XRAY_CONFIG_JSON, config.xrayConfigJson)
    putExtra(EXTRA_PROXY_APP_LIST_MODE, config.applicationPolicy.mode)
    putExtra(EXTRA_PROXY_APP_LIST_PACKAGES, config.applicationPolicy.packageNames.toTypedArray())
    putExtra(EXTRA_LOCAL_PROXY_LISTEN_ADDRESS, config.localProxyOptions.listenAddress)
    putExtra(EXTRA_LOCAL_PROXY_PORT, config.localProxyOptions.port)
    putExtra(EXTRA_LOCAL_PROXY_USERNAME, config.localProxyOptions.username)
    putExtra(EXTRA_LOCAL_PROXY_PASSWORD, config.localProxyOptions.password)
    putExtra(EXTRA_APPEND_HTTP_PROXY_ENABLED, config.appendHttpProxyOptions.enabled)
    putExtra(EXTRA_APPEND_HTTP_PROXY_PORT, config.appendHttpProxyOptions.port)
    putExtra(EXTRA_ACCESS_LOG_PATH, config.coreLogPaths.accessLogPath)
    putExtra(EXTRA_ERROR_LOG_PATH, config.coreLogPaths.errorLogPath)
    putExtra(EXTRA_ENABLE_ACCESS_LOG, config.enableAccessLog)
    putExtra(EXTRA_DATA_DIR, config.dataDir)
    config.hevSocks5TunnelConfig?.let { hevConfig ->
        writeHevSocks5TunnelConfig(hevConfig)
    }
}

private fun Intent.readHevSocks5TunnelConfig(): HevSocks5TunnelConfig? {
    val configPath = getStringExtra(EXTRA_HEV_TUN_CONFIG_PATH) ?: return null
    val logPath = getStringExtra(EXTRA_HEV_TUN_LOG_PATH) ?: return null
    return HevSocks5TunnelConfig(
        executablePath = getStringExtra(EXTRA_HEV_TUN_EXECUTABLE_PATH).orEmpty(),
        configPath = configPath,
        pidPath = getStringExtra(EXTRA_HEV_TUN_PID_PATH).orEmpty(),
        logPath = logPath,
        socksAddress = getStringExtra(EXTRA_HEV_TUN_SOCKS_ADDRESS).orEmpty().ifBlank {
            LocalProxyLoopbackAddress
        },
        socksPort = getIntExtra(EXTRA_HEV_TUN_SOCKS_PORT, VpnDefaults.LOCAL_PROXY_PORT),
        socksUsername = getStringExtra(EXTRA_HEV_TUN_SOCKS_USERNAME).orEmpty(),
        socksPassword = getStringExtra(EXTRA_HEV_TUN_SOCKS_PASSWORD).orEmpty(),
        mtu = getIntExtra(EXTRA_HEV_TUN_MTU, VpnDefaults.MTU),
        ipv4Address = getStringExtra(EXTRA_HEV_TUN_IPV4_ADDRESS) ?: defaultIpv4TunAddress.address,
        ipv6Address = getStringExtra(EXTRA_HEV_TUN_IPV6_ADDRESS),
        tunnelName = getStringExtra(EXTRA_HEV_TUN_TUNNEL_NAME),
        enableMultiQueue = getBooleanExtra(EXTRA_HEV_TUN_MULTI_QUEUE, false),
        enableTcpFastOpen = getBooleanExtra(EXTRA_HEV_TUN_TCP_FAST_OPEN, false),
        tcpReadWriteTimeoutMillis = getIntExtra(
            EXTRA_HEV_TUN_TCP_RW_TIMEOUT,
            DefaultHevSocks5TunnelTcpReadWriteTimeoutMillis,
        ),
        udpReadWriteTimeoutMillis = getIntExtra(
            EXTRA_HEV_TUN_UDP_RW_TIMEOUT,
            DefaultHevSocks5TunnelUdpReadWriteTimeoutMillis,
        ),
        logLevel = getStringExtra(EXTRA_HEV_TUN_LOG_LEVEL).orEmpty().ifBlank {
            DefaultHevSocks5TunnelLogLevel
        },
    )
}

private fun Intent.writeHevSocks5TunnelConfig(config: HevSocks5TunnelConfig) {
    putExtra(EXTRA_HEV_TUN_EXECUTABLE_PATH, config.executablePath)
    putExtra(EXTRA_HEV_TUN_CONFIG_PATH, config.configPath)
    putExtra(EXTRA_HEV_TUN_PID_PATH, config.pidPath)
    putExtra(EXTRA_HEV_TUN_LOG_PATH, config.logPath)
    putExtra(EXTRA_HEV_TUN_SOCKS_ADDRESS, config.socksAddress)
    putExtra(EXTRA_HEV_TUN_SOCKS_PORT, config.socksPort)
    putExtra(EXTRA_HEV_TUN_SOCKS_USERNAME, config.socksUsername)
    putExtra(EXTRA_HEV_TUN_SOCKS_PASSWORD, config.socksPassword)
    putExtra(EXTRA_HEV_TUN_MTU, config.mtu)
    putExtra(EXTRA_HEV_TUN_IPV4_ADDRESS, config.ipv4Address)
    putExtra(EXTRA_HEV_TUN_IPV6_ADDRESS, config.ipv6Address)
    putExtra(EXTRA_HEV_TUN_TUNNEL_NAME, config.tunnelName)
    putExtra(EXTRA_HEV_TUN_MULTI_QUEUE, config.enableMultiQueue)
    putExtra(EXTRA_HEV_TUN_TCP_FAST_OPEN, config.enableTcpFastOpen)
    putExtra(EXTRA_HEV_TUN_TCP_RW_TIMEOUT, config.tcpReadWriteTimeoutMillis)
    putExtra(EXTRA_HEV_TUN_UDP_RW_TIMEOUT, config.udpReadWriteTimeoutMillis)
    putExtra(EXTRA_HEV_TUN_LOG_LEVEL, config.logLevel)
}

private const val EXTRA_SESSION_NAME = "session_name"
private const val EXTRA_MTU = "mtu"
private const val EXTRA_IPV4_ADDRESS = "ipv4_address"
private const val EXTRA_IPV4_PREFIX_LENGTH = "ipv4_prefix_length"
private const val EXTRA_IPV6_ADDRESS = "ipv6_address"
private const val EXTRA_IPV6_PREFIX_LENGTH = "ipv6_prefix_length"
private const val EXTRA_ENABLE_IPV6 = "enable_ipv6"
private const val EXTRA_ENABLE_LOCAL_DNS = "enable_local_dns"
private const val EXTRA_DNS_SERVERS = "dns_servers"
private const val EXTRA_XRAY_CONFIG_JSON = "xray_config_json"
private const val EXTRA_PROXY_APP_LIST_MODE = "proxy_app_list_mode"
private const val EXTRA_PROXY_APP_LIST_PACKAGES = "proxy_app_list_packages"
private const val EXTRA_LOCAL_PROXY_LISTEN_ADDRESS = "local_proxy_listen_address"
private const val EXTRA_LOCAL_PROXY_PORT = "local_proxy_port"
private const val EXTRA_LOCAL_PROXY_USERNAME = "local_proxy_username"
private const val EXTRA_LOCAL_PROXY_PASSWORD = "local_proxy_password"
private const val EXTRA_APPEND_HTTP_PROXY_ENABLED = "append_http_proxy_enabled"
private const val EXTRA_APPEND_HTTP_PROXY_PORT = "append_http_proxy_port"
private const val EXTRA_ACCESS_LOG_PATH = "access_log_path"
private const val EXTRA_ERROR_LOG_PATH = "error_log_path"
private const val EXTRA_ENABLE_ACCESS_LOG = "enable_access_log"
private const val EXTRA_DATA_DIR = "data_dir"
private const val EXTRA_HEV_TUN_EXECUTABLE_PATH = "hev_tun_executable_path"
private const val EXTRA_HEV_TUN_CONFIG_PATH = "hev_tun_config_path"
private const val EXTRA_HEV_TUN_PID_PATH = "hev_tun_pid_path"
private const val EXTRA_HEV_TUN_LOG_PATH = "hev_tun_log_path"
private const val EXTRA_HEV_TUN_SOCKS_ADDRESS = "hev_tun_socks_address"
private const val EXTRA_HEV_TUN_SOCKS_PORT = "hev_tun_socks_port"
private const val EXTRA_HEV_TUN_SOCKS_USERNAME = "hev_tun_socks_username"
private const val EXTRA_HEV_TUN_SOCKS_PASSWORD = "hev_tun_socks_password"
private const val EXTRA_HEV_TUN_MTU = "hev_tun_mtu"
private const val EXTRA_HEV_TUN_IPV4_ADDRESS = "hev_tun_ipv4_address"
private const val EXTRA_HEV_TUN_IPV6_ADDRESS = "hev_tun_ipv6_address"
private const val EXTRA_HEV_TUN_TUNNEL_NAME = "hev_tun_tunnel_name"
private const val EXTRA_HEV_TUN_MULTI_QUEUE = "hev_tun_multi_queue"
private const val EXTRA_HEV_TUN_TCP_FAST_OPEN = "hev_tun_tcp_fast_open"
private const val EXTRA_HEV_TUN_TCP_RW_TIMEOUT = "hev_tun_tcp_rw_timeout"
private const val EXTRA_HEV_TUN_UDP_RW_TIMEOUT = "hev_tun_udp_rw_timeout"
private const val EXTRA_HEV_TUN_LOG_LEVEL = "hev_tun_log_level"
