// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.content.Intent
import app.modes.ProxyAppListModeGlobal
import engine.xray.XrayCoreLogPaths

internal object AsteriskVpnServiceIntents {
    const val ACTION_START = "app.action.START_VPN"
    const val ACTION_STOP = "app.action.STOP_VPN"

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
        localProxyOptions = VpnLocalProxyOptions(
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
