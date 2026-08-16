// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.AppState
import app.LocalAppServices
import app.R
import androidx.compose.ui.res.stringResource
import features.logs.AndroidAppLogger
import features.settings.sheets.DnsSettingsBottomSheet
import features.settings.sheets.ExternalInterfacesBottomSheet
import features.settings.sheets.FragmentSettingsBottomSheet
import features.settings.sheets.IgnoredInterfacesBottomSheet
import features.settings.sheets.LocalProxySettingsBottomSheet
import features.settings.sheets.MuxSettingsBottomSheet
import features.settings.sheets.PrivateAddressBottomSheet
import features.settings.sheets.ProxySettingsBottomSheet
import features.settings.sheets.ServiceControlBottomSheet
import features.settings.sheets.TunSettingsBottomSheet
import features.settings.sheets.sanitizeExternalInterfaces
import features.settings.sheets.sanitizeIgnoredInterfaceSelectors
import features.settings.sheets.sanitizeMuxUdp443Index
import features.settings.sheets.sanitizePrivateAddressCidrs
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import app.modes.isRootRunMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingsBottomSheetsHost(
    appState: AppState,
    sheetState: SettingsSheetState,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val applyServiceControl = LocalAppServices.current.applyServiceControlUseCase
    val serviceControlFailedMessage = stringResource(R.string.settings_service_control_save_failed)
    var serviceControlSaving by remember { mutableStateOf(false) }
    var serviceControlError by remember { mutableStateOf<String?>(null) }
    ProxySettingsBottomSheet(
        show = sheetState.showProxySettings,
        useTun2SocksProxyPort = appState.runMode == RunModeTun2Socks,
        useBpf2SocksProxyPort = appState.runMode == RunModeBpf2Socks,
        lockPrimaryPortSettings = appState.runMode.isRootRunMode() && appState.proxyRunning,
        lockSharedInboundSettings = appState.runMode.isRootRunMode() && appState.proxyRunning,
        transparentProxyPort = sheetState.proxySettingsDraft.transparentProxyPort,
        bpf2SocksBridgePort = sheetState.proxySettingsDraft.bpf2SocksBridgePort,
        socks5ProxyPort = sheetState.proxySettingsDraft.socks5ProxyPort,
        enableHttpProxy = sheetState.proxySettingsDraft.enableHttpProxy,
        httpProxyPort = sheetState.proxySettingsDraft.httpProxyPort,
        onTransparentProxyPortChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(
                transparentProxyPort = it,
            )
        },
        onBpf2SocksBridgePortChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(
                bpf2SocksBridgePort = it,
            )
        },
        onSocks5ProxyPortChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(
                socks5ProxyPort = it,
            )
        },
        onEnableHttpProxyChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(enableHttpProxy = it)
        },
        onHttpProxyPortChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(
                httpProxyPort = it,
            )
        },
        onDismissRequest = { sheetState.showProxySettings = false },
        onSave = { transparentProxyPort, bpf2SocksBridgePort, socks5ProxyPort, enableHttpProxy, httpProxyPort ->
            updateAppState { state ->
                val lockPrimaryPortSettings = state.runMode.isRootRunMode() && state.proxyRunning
                val lockSharedInboundSettings = state.runMode.isRootRunMode() && state.proxyRunning
                state.copy(
                    transparentProxyPort = if (lockPrimaryPortSettings) {
                        state.transparentProxyPort
                    } else {
                        transparentProxyPort
                    },
                    bpf2SocksBridgePort = if (lockPrimaryPortSettings) {
                        state.bpf2SocksBridgePort
                    } else {
                        bpf2SocksBridgePort
                    },
                    socks5ProxyPort = if (lockPrimaryPortSettings) state.socks5ProxyPort else socks5ProxyPort,
                    enableHttpProxy = if (lockSharedInboundSettings) state.enableHttpProxy else enableHttpProxy,
                    httpProxyPort = if (lockSharedInboundSettings) state.httpProxyPort else httpProxyPort,
                )
            }
            sheetState.showProxySettings = false
        },
    )
    LocalProxySettingsBottomSheet(
        show = sheetState.showLocalProxySettings,
        port = sheetState.localProxySettingsDraft.port,
        enableDynamicPort = sheetState.localProxySettingsDraft.enableDynamicPort,
        listenAllInterfaces = sheetState.localProxySettingsDraft.listenAllInterfaces,
        username = sheetState.localProxySettingsDraft.username,
        password = sheetState.localProxySettingsDraft.password,
        onPortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(
                port = it,
            )
        },
        onEnableDynamicPortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(enableDynamicPort = it)
        },
        onListenAllInterfacesChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(listenAllInterfaces = it)
        },
        onUsernameChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(username = it)
        },
        onPasswordChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(password = it)
        },
        onDismissRequest = { sheetState.showLocalProxySettings = false },
        onSave = { port, enableDynamicPort, listenAllInterfaces, username, password ->
            updateAppState { state ->
                state.copy(
                    localProxyPort = port,
                    enableDynamicLocalProxyPort = enableDynamicPort,
                    localProxyListenAllInterfaces = listenAllInterfaces,
                    localProxyUsername = username,
                    localProxyPassword = password,
                )
            }
            sheetState.showLocalProxySettings = false
        },
    )
    TunSettingsBottomSheet(
        show = sheetState.showTunSettings,
        mtu = sheetState.tunSettingsDraft.mtu,
        vpnDns = sheetState.tunSettingsDraft.vpnDns,
        ipv4Cidr = sheetState.tunSettingsDraft.ipv4Cidr,
        ipv6Cidr = sheetState.tunSettingsDraft.ipv6Cidr,
        showVpnDns = appState.runMode == RunModeVpnService,
        onMtuChange = {
            sheetState.tunSettingsDraft = sheetState.tunSettingsDraft.copy(mtu = it)
        },
        onVpnDnsChange = { sheetState.tunSettingsDraft = sheetState.tunSettingsDraft.copy(vpnDns = it) },
        onIpv4CidrChange = { sheetState.tunSettingsDraft = sheetState.tunSettingsDraft.copy(ipv4Cidr = it) },
        onIpv6CidrChange = { sheetState.tunSettingsDraft = sheetState.tunSettingsDraft.copy(ipv6Cidr = it) },
        onDismissRequest = { sheetState.showTunSettings = false },
        onSave = { mtu, vpnDns, ipv4Cidr, ipv6Cidr ->
            updateAppState { state ->
                state.copy(
                    tunMtu = mtu,
                    tunVpnDns = if (state.runMode == RunModeVpnService) vpnDns else state.tunVpnDns,
                    tunIpv4Cidr = ipv4Cidr,
                    tunIpv6Cidr = ipv6Cidr,
                )
            }
            sheetState.showTunSettings = false
        },
    )
    DnsSettingsBottomSheet(
        show = sheetState.showDnsSettings,
        enableVpnLocalDns = sheetState.dnsSettingsDraft.enableVpnLocalDns,
        enableFakeDns = sheetState.dnsSettingsDraft.enableFakeDns,
        enableResolveProxyServerDomain = sheetState.dnsSettingsDraft.enableResolveProxyServerDomain,
        proxyDns = sheetState.dnsSettingsDraft.proxyDns,
        directDns = sheetState.dnsSettingsDraft.directDns,
        directDnsDomains = sheetState.dnsSettingsDraft.directDnsDomains,
        enableDirectDnsForProxyServerDomains = sheetState.dnsSettingsDraft.enableDirectDnsForProxyServerDomains,
        dnsHosts = sheetState.dnsSettingsDraft.dnsHosts,
        onEnableVpnLocalDnsChange = { enabled ->
            sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(
                enableVpnLocalDns = enabled,
                enableFakeDns = if (enabled) sheetState.dnsSettingsDraft.enableFakeDns else false,
            )
        },
        onEnableFakeDnsChange = {
            sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(enableFakeDns = it)
        },
        onEnableResolveProxyServerDomainChange = {
            sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(enableResolveProxyServerDomain = it)
        },
        onProxyDnsChange = { sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(proxyDns = it) },
        onDirectDnsChange = { sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(directDns = it) },
        onDirectDnsDomainsChange = {
            sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(directDnsDomains = it)
        },
        onEnableDirectDnsForProxyServerDomainsChange = {
            sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(
                enableDirectDnsForProxyServerDomains = it,
            )
        },
        onDnsHostsChange = { sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(dnsHosts = it) },
        onDismissRequest = { sheetState.showDnsSettings = false },
        onSave = { enableVpnLocalDns, enableFakeDns, enableResolveProxyServerDomain, proxyDns, directDns, directDnsDomains, enableDirectDnsForProxyServerDomains, dnsHosts ->
            updateAppState { state ->
                state.copy(
                    enableVpnLocalDns = enableVpnLocalDns,
                    enableFakeDns = enableFakeDns,
                    enableResolveProxyServerDomain = enableResolveProxyServerDomain,
                    proxyDns = proxyDns,
                    directDns = directDns,
                    directDnsDomains = directDnsDomains,
                    enableDirectDnsForProxyServerDomains = enableDirectDnsForProxyServerDomains,
                    dnsHosts = dnsHosts,
                )
            }
            sheetState.showDnsSettings = false
        },
    )
    MuxSettingsBottomSheet(
        show = sheetState.showMuxSettings,
        enabled = sheetState.muxSettingsDraft.enabled,
        concurrency = sheetState.muxSettingsDraft.concurrency,
        xudpConcurrency = sheetState.muxSettingsDraft.xudpConcurrency,
        xudpProxyUdp443 = sheetState.muxSettingsDraft.xudpProxyUdp443,
        onEnabledChange = { sheetState.muxSettingsDraft = sheetState.muxSettingsDraft.copy(enabled = it) },
        onConcurrencyChange = {
            sheetState.muxSettingsDraft = sheetState.muxSettingsDraft.copy(concurrency = it)
        },
        onXudpConcurrencyChange = {
            sheetState.muxSettingsDraft = sheetState.muxSettingsDraft.copy(xudpConcurrency = it)
        },
        onXudpProxyUdp443Change = {
            sheetState.muxSettingsDraft = sheetState.muxSettingsDraft.copy(xudpProxyUdp443 = sanitizeMuxUdp443Index(it))
        },
        onDismissRequest = { sheetState.showMuxSettings = false },
        onSave = { enabled, concurrency, xudpConcurrency, xudpProxyUdp443 ->
            updateAppState { state ->
                state.copy(
                    enableMux = enabled,
                    muxConcurrency = concurrency,
                    muxXudpConcurrency = xudpConcurrency,
                    muxXudpProxyUdp443 = xudpProxyUdp443,
                )
            }
            sheetState.showMuxSettings = false
        },
    )
    FragmentSettingsBottomSheet(
        show = sheetState.showFragmentSettings,
        enabled = sheetState.fragmentSettingsDraft.enabled,
        packets = sheetState.fragmentSettingsDraft.packets,
        length = sheetState.fragmentSettingsDraft.length,
        interval = sheetState.fragmentSettingsDraft.interval,
        onEnabledChange = {
            sheetState.fragmentSettingsDraft = sheetState.fragmentSettingsDraft.copy(enabled = it)
        },
        onPacketsChange = { sheetState.fragmentSettingsDraft = sheetState.fragmentSettingsDraft.copy(packets = it) },
        onLengthChange = {
            sheetState.fragmentSettingsDraft = sheetState.fragmentSettingsDraft.copy(
                length = it,
            )
        },
        onIntervalChange = {
            sheetState.fragmentSettingsDraft = sheetState.fragmentSettingsDraft.copy(
                interval = it,
            )
        },
        onDismissRequest = { sheetState.showFragmentSettings = false },
        onSave = { enabled, packets, length, interval ->
            updateAppState { state ->
                state.copy(
                    enableFragment = enabled,
                    fragmentPackets = packets,
                    fragmentLength = length,
                    fragmentInterval = interval,
                )
            }
            sheetState.showFragmentSettings = false
        },
    )
    ExternalInterfacesBottomSheet(
        show = sheetState.showExternalInterfaces,
        selectedInterfaces = sheetState.externalInterfacesDraft,
        onSelectedInterfacesChange = { sheetState.externalInterfacesDraft = it.sanitizeExternalInterfaces() },
        onDismissRequest = { sheetState.showExternalInterfaces = false },
        onSave = { interfaces ->
            updateAppState { state -> state.copy(externalInterfaces = interfaces.sanitizeExternalInterfaces()) }
            sheetState.showExternalInterfaces = false
        },
    )
    ServiceControlBottomSheet(
        show = sheetState.showServiceControl,
        saving = serviceControlSaving,
        draft = sheetState.serviceControlDraft,
        runtimeError = serviceControlError,
        onDraftChange = {
            serviceControlError = null
            sheetState.serviceControlDraft = it
        },
        onDismissRequest = {
            if (!serviceControlSaving) sheetState.showServiceControl = false
        },
        onSave = { draft ->
            if (!serviceControlSaving) {
                val baseState = appState
                serviceControlSaving = true
                serviceControlError = null
                scope.launch {
                    try {
                        val applied = applyServiceControl.apply(baseState, draft)
                        updateAppState { current ->
                            current.copy(
                                serviceControl = applied.serviceControl,
                                proxyRunning = applied.proxyRunning,
                                localProxyPort = applied.localProxyPort,
                            )
                        }
                        sheetState.showServiceControl = false
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        AndroidAppLogger.error("ServiceControl", "Failed to restart asteriskd", error)
                        serviceControlError = error.message?.takeIf(String::isNotBlank)
                            ?: serviceControlFailedMessage
                    } finally {
                        serviceControlSaving = false
                    }
                }
            }
        },
    )
    IgnoredInterfacesBottomSheet(
        show = sheetState.showIgnoredInterfaces,
        selectedInterfaces = sheetState.ignoredInterfacesDraft,
        onSelectedInterfacesChange = {
            sheetState.ignoredInterfacesDraft = it.sanitizeIgnoredInterfaceSelectors()
        },
        onDismissRequest = { sheetState.closeIgnoredInterfaces() },
        onSave = { interfaces ->
            updateAppState { state ->
                state.copy(ignoredInterfaces = interfaces.sanitizeIgnoredInterfaceSelectors())
            }
            sheetState.closeIgnoredInterfaces()
        },
    )
    PrivateAddressBottomSheet(
        show = sheetState.showPrivateAddresses,
        selectedCidrs = sheetState.privateAddressCidrsDraft,
        onSelectedCidrsChange = { sheetState.privateAddressCidrsDraft = it.sanitizePrivateAddressCidrs() },
        onDismissRequest = { sheetState.showPrivateAddresses = false },
        onSave = { cidrs ->
            updateAppState { state -> state.copy(privateAddressCidrs = cidrs.sanitizePrivateAddressCidrs()) }
            sheetState.showPrivateAddresses = false
        },
    )
}
