// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import android.content.ComponentName
import android.content.Context
import app.AppState
import app.ProxyServerState
import app.effectiveFakeDnsEnabled
import app.effectiveLocalDnsEnabled
import engine.network.normalizedFakeIpIpv4Pool
import engine.network.toPortOrNull
import engine.proxy.FlowMetadataInboundTag
import engine.proxy.ProxyEngineStartRequest
import engine.proxy.flowMetadataOptions
import engine.proxy.usesFlowMetadata
import engine.proxy.xrayStatsApiConfig
import engine.vpn.xrayDnsHosts
import engine.xray.XrayConfigFactory
import engine.xray.XrayConfigRequest
import engine.xray.XrayCoreLogPaths
import engine.xray.XrayStatsApiConfig
import engine.xray.buildXrayOutboundPlan
import engine.xray.prepareXrayCoreLogPaths
import engine.xray.validateXrayExternalRoutingResources
import engine.xray.withFlowMetadata
import features.proxy.server.model.Custom
import features.quicksettings.QuickSettingsTileRefreshReceiver
import features.resources.runtime.XrayResourceFilePaths
import features.resources.runtime.xrayRootResourceFilePaths
import java.io.File
import kotlinx.serialization.json.JsonObject

internal class RootConfigBuildContext(
    private val androidContext: Context,
    val appState: AppState,
    private val selectedServer: ProxyServerState,
    val resourceFilePaths: XrayResourceFilePaths,
    private val coreLogPaths: XrayCoreLogPaths,
    private val dnsHosts: List<String>,
    val statsApiConfig: XrayStatsApiConfig? = null,
) {
    val bridgeOptions by lazy {
        androidContext.flowMetadataOptions()
    }

    fun buildRootStartConfig(
        inbounds: List<JsonObject>,
        dnsHijackInboundTags: List<String>,
        statsApiConfig: XrayStatsApiConfig? = this.statsApiConfig,
    ): RootStartConfig {
        val xrayConfigJson = XrayConfigFactory.buildXrayConfig(
            XrayConfigRequest(
                appState = appState,
                selectedServer = selectedServer,
                inbounds = inbounds,
                coreLogPaths = coreLogPaths,
                dnsHosts = dnsHosts,
                dnsHijackInboundTags = if (appState.usesFlowMetadata()) {
                    dnsHijackInboundTags + FlowMetadataInboundTag
                } else {
                    dnsHijackInboundTags
                },
                statsApiConfig = statsApiConfig,
            ),
        )
        return appState.toRootStartConfig(
            runtimeEventReceiver = ComponentName(androidContext, QuickSettingsTileRefreshReceiver::class.java)
                .flattenToString(),
            xrayConfigJson = xrayConfigJson.withFlowMetadata(androidContext, appState),
            resourceFilePaths = resourceFilePaths,
        )
    }

    fun buildRootIptablesConfig(): RootIptablesConfig {
        return RootIptablesConfig().withAppSettings(
            context = androidContext,
            appState = appState,
        )
    }

}

internal fun Context.prepareRootConfigBuildContext(request: ProxyEngineStartRequest): RootConfigBuildContext {
    val appState = request.appState
    val resourceFilePaths = xrayRootResourceFilePaths()
    if (request.selectedServer.server !is Custom) {
        appState.validateXrayExternalRoutingResources(resourceFilePaths.assetsDir)
    }
    val coreLogPaths = applicationContext.prepareXrayCoreLogPaths()
    val outboundPlan = appState.buildXrayOutboundPlan(request.selectedServer)
    return RootConfigBuildContext(
        androidContext = applicationContext,
        appState = appState,
        selectedServer = request.selectedServer,
        resourceFilePaths = resourceFilePaths,
        coreLogPaths = coreLogPaths,
        dnsHosts = appState.xrayDnsHosts(outboundPlan.dnsHostServers),
        statsApiConfig = request.xrayStatsApiConfig(),
    )
}

private fun AppState.toRootStartConfig(
    runtimeEventReceiver: String,
    xrayConfigJson: String,
    resourceFilePaths: XrayResourceFilePaths,
): RootStartConfig {
    val dataDirectory = File(resourceFilePaths.dataDir)
    return RootStartConfig(
        xrayConfigJson = xrayConfigJson,
        runtimePaths = RootConfigRuntimePaths(
            coreExecutablePath = resourceFilePaths.xrayCorePath,
            coreConfigPath = File(dataDirectory, "config.json").absolutePath,
            matcherExecutablePath = resourceFilePaths.bpfMatcherPath,
            bpf2SocksExecutablePath = resourceFilePaths.bpf2socksPath,
            hevSocks5TunnelExecutablePath = resourceFilePaths.hevSocks5TunnelPath,
            workingDirectory = resourceFilePaths.assetsDir,
            statePath = File(dataDirectory, "asteriskd.state").absolutePath,
            logPath = File(File(dataDirectory, "logs"), "asteriskd.log").absolutePath,
        ),
        directCidrIpv4Path = resourceFilePaths.directCidrIpv4Path,
        directCidrIpv6Path = resourceFilePaths.directCidrIpv6Path,
        enableIpv6 = enableIpv6,
        enableRootIpv6Disabler = enableRootIpv6Disabler,
        enableAppListEnhancement = enableAppListEnhancement,
        enableLocalDns = effectiveLocalDnsEnabled,
        enableFakeDns = effectiveFakeDnsEnabled,
        fakeDnsIpv4Pool = fakeDnsIpv4Pool.normalizedFakeIpIpv4Pool(),
        enableBoot = enableRootBootScript,
        serviceControl = serviceControl,
        runtimeEventReceiver = runtimeEventReceiver,
    )
}

internal fun AppState.tun2SocksInternalProxyPortValue(): Int {
    return socks5ProxyPort.toPortOrNull() ?: DefaultRootTun2SocksProxyPort
}

internal fun AppState.bpf2SocksBridgePortValue(): Int {
    return bpf2SocksBridgePort.toPortOrNull() ?: RootBpf2SocksDefaultBridgePort
}
