// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.os.Process
import app.AppState
import app.effectiveLocalDnsEnabled
import engine.hevtun.HevSocks5TunnelConfig
import engine.hevtun.HevSocks5TunnelConfigFileName
import engine.hevtun.HevSocks5TunnelLogFileName
import engine.hevtun.hevSocks5TunnelLogFile
import engine.hevtun.hevSocks5TunnelSocksTargetAddress
import engine.proxy.LocalProxyOptions
import engine.proxy.ProxyEngineStartRequest
import engine.proxy.buildLocalSocksInbound
import engine.proxy.toLocalProxyOptions
import engine.proxy.xrayStatsApiConfig
import engine.xray.XrayConfigFactory
import engine.xray.XrayConfigRequest
import engine.xray.XrayCoreLogPaths
import engine.xray.XrayTags
import engine.xray.buildXrayOutboundPlan
import engine.xray.prepareXrayCoreLogPaths
import engine.xray.validateXrayExternalRoutingResources
import features.resources.runtime.prepareXrayResourceFilePaths
import features.proxy.server.model.Custom
import system.toAndroidUserId
import java.io.File
import kotlinx.serialization.json.JsonObject

internal data class VpnServiceStartConfig(
    val sessionName: String,
    val mtu: Int = VpnDefaults.MTU,
    val ipv4Address: String = defaultIpv4TunAddress.address,
    val ipv4PrefixLength: Int = defaultIpv4TunAddress.prefixLength,
    val ipv6Address: String? = null,
    val ipv6PrefixLength: Int = defaultIpv6TunAddress.prefixLength,
    val enableIpv6: Boolean = false,
    val enableLocalDns: Boolean = true,
    val dnsServers: List<String>,
    val xrayConfigJson: String,
    val applicationPolicy: VpnApplicationPolicy,
    val localProxyOptions: LocalProxyOptions,
    val appendHttpProxyOptions: VpnAppendHttpProxyOptions,
    val coreLogPaths: XrayCoreLogPaths,
    val enableAccessLog: Boolean = false,
    val dataDir: String = "",
    val hevSocks5TunnelConfig: HevSocks5TunnelConfig? = null,
)

internal fun VpnServiceStartConfig.xrayTunFd(vpnTunFd: Int): Int {
    return if (hevSocks5TunnelConfig == null) vpnTunFd else 0
}

internal object VpnXrayConfigFactory {
    fun create(context: Context, request: ProxyEngineStartRequest): VpnServiceStartConfig {
        val appState = request.appState
        val coreLogPaths = context.prepareXrayCoreLogPaths()
        val resourceFilePaths = context.prepareXrayResourceFilePaths()
        if (request.selectedServer.server !is Custom) {
            appState.validateXrayExternalRoutingResources(resourceFilePaths.dataDir)
        }
        val tunOptions = appState.toTunOptions()
        val localProxyOptions = appState.toLocalProxyOptions()
        val appendHttpProxyOptions = appState.toVpnAppendHttpProxyOptions(localProxyOptions)
        val outboundPlan = appState.buildXrayOutboundPlan(request.selectedServer)
        val dnsHosts = appState.xrayDnsHosts(outboundPlan.dnsHostServers)

        return VpnServiceStartConfig(
            sessionName = "AsteriskNG",
            mtu = tunOptions.mtu,
            ipv4Address = tunOptions.ipv4Address.address,
            ipv4PrefixLength = tunOptions.ipv4Address.prefixLength,
            enableIpv6 = appState.enableIpv6,
            ipv6Address = if (appState.enableIpv6) tunOptions.ipv6Address.address else null,
            ipv6PrefixLength = tunOptions.ipv6Address.prefixLength,
            enableLocalDns = appState.effectiveLocalDnsEnabled,
            dnsServers = tunOptions.dnsServers,
            xrayConfigJson = XrayConfigFactory.buildXrayConfig(
                XrayConfigRequest(
                    appState = appState,
                    selectedServer = request.selectedServer,
                    inbounds = buildVpnXrayInbounds(
                        appState = appState,
                        tunOptions = tunOptions,
                        localProxyOptions = localProxyOptions,
                        appendHttpProxyOptions = appendHttpProxyOptions,
                    ),
                    coreLogPaths = coreLogPaths,
                    dnsHosts = dnsHosts,
                    dnsHijackInboundTags = vpnDnsHijackInboundTags(appState.enableVpnHevTun),
                    statsApiConfig = request.xrayStatsApiConfig(),
                ),
            ),
            applicationPolicy = appState.toVpnApplicationPolicy(Process.myUid().toAndroidUserId()),
            localProxyOptions = localProxyOptions,
            appendHttpProxyOptions = appendHttpProxyOptions,
            coreLogPaths = coreLogPaths,
            enableAccessLog = appState.enableAccessLog,
            dataDir = resourceFilePaths.dataDir,
            hevSocks5TunnelConfig = buildVpnHevSocks5TunnelConfig(
                dataDir = resourceFilePaths.dataDir,
                coreLogPaths = coreLogPaths,
                localProxyOptions = localProxyOptions,
                tunOptions = tunOptions,
                enableIpv6 = appState.enableIpv6,
                useHevTun = appState.enableVpnHevTun,
            ),
        )
    }
}

internal fun buildVpnXrayInbounds(
    appState: AppState,
    tunOptions: TunOptions,
    localProxyOptions: LocalProxyOptions,
    appendHttpProxyOptions: VpnAppendHttpProxyOptions,
): List<JsonObject> {
    return buildList {
        add(buildLocalSocksInbound(appState, XrayTags.LOCAL_SOCKS_INBOUND, localProxyOptions))
        if (appendHttpProxyOptions.enabled) {
            add(buildVpnAppendHttpInbound(appendHttpProxyOptions))
        }
        if (!appState.enableVpnHevTun) {
            add(buildVpnTunInbound(appState, tunOptions))
        }
    }
}

internal fun vpnDnsHijackInboundTags(useHevTun: Boolean): List<String> {
    return if (useHevTun) {
        listOf(XrayTags.LOCAL_SOCKS_INBOUND)
    } else {
        listOf(XrayTags.VPN_TUN_INBOUND)
    }
}

internal fun buildVpnHevSocks5TunnelConfig(
    dataDir: String,
    coreLogPaths: XrayCoreLogPaths,
    localProxyOptions: LocalProxyOptions,
    tunOptions: TunOptions,
    enableIpv6: Boolean,
    useHevTun: Boolean = true,
): HevSocks5TunnelConfig? {
    if (!useHevTun) return null
    return HevSocks5TunnelConfig(
        configPath = File(dataDir, HevSocks5TunnelConfigFileName).absolutePath,
        logPath = coreLogPaths.hevSocks5TunnelLogFile(HevSocks5TunnelLogFileName).absolutePath,
        socksAddress = hevSocks5TunnelSocksTargetAddress(localProxyOptions),
        socksPort = localProxyOptions.port,
        socksUsername = localProxyOptions.username,
        socksPassword = localProxyOptions.password,
        mtu = tunOptions.mtu,
        ipv4Address = tunOptions.ipv4Address.address,
        ipv6Address = tunOptions.ipv6Address.address.takeIf { enableIpv6 },
        tunnelName = "asterisk0",
        enableMultiQueue = true,
        enableTcpFastOpen = true,
    )
}
