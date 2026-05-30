// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(ExperimentalScrollBarApi::class)

package features.settings

import app.LocalAppChromeState
import app.LocalAppStateStore
import app.LocalAppServices
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.modes.ColorModeThemeDark
import app.modes.ColorModeThemeSystem
import app.collectAppState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.ProjectInfo
import app.R
import features.settings.sheets.externalInterfacesSummary
import features.settings.sheets.fragmentSettingsSummary
import features.settings.sheets.ignoredInterfacesSummary
import features.settings.sheets.muxSettingsSummary
import features.settings.sheets.privateAddressCidrsSummary
import features.settings.sheets.vpnSettingsSummary
import features.settings.usecase.SwitchRunModeResult
import features.settings.usecase.TproxyBootScriptResult
import kotlinx.coroutines.launch
import app.navigation.Route
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import ui.layout.AdaptiveTopAppBar
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers
import ui.KeyColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

@Composable
fun SettingsPage(
    padding: PaddingValues,
) {
    val languageMode = LocalAppChromeState.current.languageMode
    val isWideScreen = LocalIsWideScreen.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            key(languageMode) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_title),
                    isWideScreen = isWideScreen,
                    scrollBehavior = topAppBarScrollBehavior,
                    subtitle = "v${ProjectInfo.VERSION_NAME} (${ProjectInfo.VERSION_CODE})",
                )
            }
        },
    ) { innerPadding ->
        SettingsContent(
            innerPadding = innerPadding,
            outerPadding = padding,
            topAppBarScrollBehavior = topAppBarScrollBehavior,
        )
    }
}

@Composable
private fun SettingsContent(
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
    topAppBarScrollBehavior: ScrollBehavior,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val isWideScreen = LocalIsWideScreen.current
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val networkInterfaces = services.networkInterfaces
    val switchRunModeUseCase = services.switchRunModeUseCase
    val tproxyBootScriptUseCase = services.tproxyBootScriptUseCase
    val tipNotifier = services.tipNotifier
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    var runModeSwitchInProgress by rememberSaveable { mutableStateOf(false) }
    var tproxyBootScriptSwitchInProgress by rememberSaveable { mutableStateOf(false) }
    val contentPadding = pageContentPaddingWithCutout(
        innerPadding = innerPadding,
        outerPadding = outerPadding,
        isWideScreen = isWideScreen,
    )
    val listPadding = pageListPadding(contentPadding)

    val isThemeColorMode = appState.colorMode in ColorModeThemeSystem..ColorModeThemeDark
    val colorModeOptions = listOf(
        stringResource(R.string.option_follow_system),
        stringResource(R.string.option_light),
        stringResource(R.string.option_dark),
        stringResource(R.string.option_theme_system),
        stringResource(R.string.option_theme_light),
        stringResource(R.string.option_theme_dark),
    )
    val languageOptions = listOf(
        stringResource(R.string.option_follow_system),
        stringResource(R.string.option_english),
        stringResource(R.string.option_simplified_chinese),
    )
    val runModeOptions = listOf(
        stringResource(R.string.settings_run_mode_vpn_service),
        stringResource(R.string.settings_run_mode_tproxy),
    )
    val keyColorOptions = listOf(
        stringResource(R.string.theme_color_default),
        stringResource(R.string.theme_color_blue),
        stringResource(R.string.theme_color_green),
        stringResource(R.string.theme_color_violet),
        stringResource(R.string.theme_color_yellow),
        stringResource(R.string.theme_color_orange),
        stringResource(R.string.theme_color_rose),
        stringResource(R.string.theme_color_cyan),
    ).take(KeyColors.size + 1)
    val tproxyRootRequiredMessage = stringResource(R.string.settings_tproxy_root_required)
    val tproxyBootScriptFailedMessage = stringResource(R.string.settings_tproxy_boot_script_failed)
    val serviceStoppedMessage = stringResource(R.string.proxy_server_list_service_stopped)
    val selectServerFirstMessage = stringResource(R.string.proxy_server_list_select_first)
    val ignoredInterfacesErrorDetail = stringResource(R.string.settings_ignored_interfaces_error_detail)
    val inboundProxySummary = inboundProxySummary(
        transparentProxyPort = appState.transparentProxyPort,
        enableSocks5Proxy = appState.enableSocks5Proxy,
        enableHttpProxy = appState.enableHttpProxy,
    )
    val localProxySettingsSummary = localProxySettingsSummary(
        port = appState.localProxyPort,
        enableDynamicPort = appState.enableDynamicLocalProxyPort,
        listenAllInterfaces = appState.localProxyListenAllInterfaces,
    )
    val externalInterfacesSummary = externalInterfacesSummary(appState.externalInterfaces)
    val ignoredInterfacesSummary = ignoredInterfacesSummary(appState.ignoredInterfaces)
    val privateAddressCidrsSummary = privateAddressCidrsSummary(appState.privateAddressCidrs)
    val vpnSettingsSummary = vpnSettingsSummary(
        mtu = appState.vpnMtu,
        defaultDns = appState.vpnDefaultDns,
        ipv4Cidr = appState.vpnIpv4Cidr,
        ipv6Cidr = appState.vpnIpv6Cidr,
    )
    val muxSettingsSummary = muxSettingsSummary(
        enabled = appState.enableMux,
        concurrency = appState.muxConcurrency,
        xudpConcurrency = appState.muxXudpConcurrency,
        xudpProxyUdp443 = appState.muxXudpProxyUdp443,
    )
    val fragmentSettingsSummary = fragmentSettingsSummary(
        enabled = appState.enableFragment,
        packets = appState.fragmentPackets,
        length = appState.fragmentLength,
        interval = appState.fragmentInterval,
    )
    val sheetState = rememberSettingsSheetState(updateAppState)

    Box {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.pageScrollModifiers(
                topAppBarScrollBehavior,
            ),
            contentPadding = listPadding,
        ) {
            item(key = "settings_theme") {
                SettingsThemeSection(
                    colorModeOptions = colorModeOptions,
                    colorMode = appState.colorMode,
                    keyColorOptions = keyColorOptions,
                    seedIndex = appState.seedIndex,
                    languageOptions = languageOptions,
                    languageMode = appState.languageMode,
                    isThemeColorMode = isThemeColorMode,
                    onColorModeChange = { index -> updateAppState { state -> state.copy(colorMode = index) } },
                    onSeedIndexChange = { index -> updateAppState { state -> state.copy(seedIndex = index) } },
                    onLanguageModeChange = { index -> updateAppState { state -> state.copy(languageMode = index) } },
                )
            }
            item(key = "settings_subscriptions") {
                SettingsSubscriptionsSection(
                    enableAllProxyGroup = appState.enableAllProxyGroup,
                    onOpenGroupManagement = { navigator.push(Route.SubscriptionGroupList) },
                    onOpenResourceManagement = { navigator.push(Route.ResourceManagement) },
                    onEnableAllProxyGroupChange = { enabled ->
                        updateAppState { state -> state.copy(enableAllProxyGroup = enabled) }
                    },
                )
            }
            item(key = "settings_core") {
                SettingsCoreSection(
                    enableSniffing = appState.enableSniffing,
                    enableSniffingRouteOnly = appState.enableSniffingRouteOnly,
                    muxSettingsSummary = muxSettingsSummary,
                    fragmentSettingsSummary = fragmentSettingsSummary,
                    coreLogLevel = appState.coreLogLevel,
                    enableAccessLog = appState.enableAccessLog,
                    onOpenDnsSettings = { sheetState.openDnsSettings(appState) },
                    onEnableSniffingChange = { enabled ->
                        updateAppState { state -> state.copy(enableSniffing = enabled) }
                    },
                    onEnableSniffingRouteOnlyChange = { enabled ->
                        updateAppState { state -> state.copy(enableSniffingRouteOnly = enabled) }
                    },
                    onOpenMuxSettings = { sheetState.openMuxSettings(appState) },
                    onOpenFragmentSettings = { sheetState.openFragmentSettings(appState) },
                    onCoreLogLevelChange = { index -> updateAppState { state -> state.copy(coreLogLevel = index) } },
                    onEnableAccessLogChange = { enabled ->
                        updateAppState { state -> state.copy(enableAccessLog = enabled) }
                    },
                )
            }
            item(key = "settings_run_mode") {
                SettingsAdvancedSection(
                    enableIpv6 = appState.enableIpv6,
                    enableIpv6Prefer = appState.enableIpv6Prefer,
                    runModeOptions = runModeOptions,
                    runMode = appState.runMode,
                    onEnableIpv6Change = { enabled ->
                        updateAppState { state -> state.copy(enableIpv6 = enabled) }
                    },
                    onEnableIpv6PreferChange = { enabled ->
                        updateAppState { state -> state.copy(enableIpv6Prefer = enabled) }
                    },
                    onRunModeChange = { index ->
                        if (index != appState.runMode && !runModeSwitchInProgress) {
                            scope.launch {
                                runModeSwitchInProgress = true
                                try {
                                    when (val result = switchRunModeUseCase.switchRunMode(appState, index)) {
                                        is SwitchRunModeResult.Success -> {
                                            updateAppState { state ->
                                                state.copy(
                                                    runMode = result.runMode,
                                                    proxyRunning = result.proxyRunning,
                                                    enableTproxyBootScript = false,
                                                )
                                            }
                                        }

                                        is SwitchRunModeResult.RootUnavailable -> {
                                            updateAppState { state -> state.copy(proxyRunning = result.proxyRunning) }
                                            tipNotifier.show(tproxyRootRequiredMessage)
                                        }

                                        is SwitchRunModeResult.StopFailed -> {
                                            tipNotifier.showError(result.error, serviceStoppedMessage)
                                        }
                                    }
                                } finally {
                                    runModeSwitchInProgress = false
                                }
                            }
                        }
                    },
                )
            }
            item(key = "settings_proxy") {
                SettingsProxyModeSections(
                    runMode = appState.runMode,
                    localProxySettingsSummary = localProxySettingsSummary,
                    enableVpnAppendHttpProxy = appState.enableVpnAppendHttpProxy,
                    vpnSettingsSummary = vpnSettingsSummary,
                    inboundProxySummary = inboundProxySummary,
                    enableTproxyBootScript = appState.enableTproxyBootScript,
                    externalInterfacesSummary = externalInterfacesSummary,
                    ignoredInterfacesSummary = ignoredInterfacesSummary,
                    privateAddressCidrsSummary = privateAddressCidrsSummary,
                    onOpenLocalProxySettings = { sheetState.openLocalProxySettings(appState) },
                    onEnableVpnAppendHttpProxyChange = { enabled ->
                        updateAppState { state -> state.copy(enableVpnAppendHttpProxy = enabled) }
                    },
                    onOpenVpnSettings = { sheetState.openVpnSettings(appState) },
                    onOpenProxySettings = { sheetState.openProxySettings(appState) },
                    onEnableTproxyBootScriptChange = { enabled ->
                        if (!tproxyBootScriptSwitchInProgress) {
                            scope.launch {
                                tproxyBootScriptSwitchInProgress = true
                                try {
                                    when (val result = tproxyBootScriptUseCase.setEnabled(appState, enabled)) {
                                        TproxyBootScriptResult.Success -> {
                                            updateAppState { state ->
                                                state.copy(enableTproxyBootScript = enabled)
                                            }
                                        }

                                        TproxyBootScriptResult.MissingServer -> {
                                            tipNotifier.show(selectServerFirstMessage)
                                        }

                                        TproxyBootScriptResult.RootUnavailable -> {
                                            tipNotifier.show(tproxyRootRequiredMessage)
                                        }

                                        is TproxyBootScriptResult.Failed -> {
                                            tipNotifier.showError(result.error, tproxyBootScriptFailedMessage)
                                        }
                                    }
                                } finally {
                                    tproxyBootScriptSwitchInProgress = false
                                }
                            }
                        }
                    },
                    onOpenExternalInterfaces = { sheetState.openExternalInterfaces(appState) },
                    onOpenIgnoredInterfaces = {
                        sheetState.openIgnoredInterfaces(appState)
                        scope.launch {
                            sheetState.loadIgnoredInterfaces(
                                appState = appState,
                                networkInterfaces = networkInterfaces,
                                errorDetail = ignoredInterfacesErrorDetail,
                            )
                        }
                    },
                    onOpenPrivateAddresses = { sheetState.openPrivateAddresses(appState) },
                )
            }
            item(key = "settings_logs") {
                SettingsLogsSection(
                    enableAccessLog = appState.enableAccessLog,
                    onOpenCoreLogs = { navigator.push(Route.CoreLogs) },
                    onOpenAccessLogs = { navigator.push(Route.AccessLogs) },
                    onOpenLogcatLogs = { navigator.push(Route.LogcatLogs) },
                )
            }
            item(key = "settings_about") {
                SettingsAboutSection(
                    onOpenAbout = { navigator.push(Route.About) },
                    onOpenLicenses = { navigator.push(Route.License) },
                )
            }
        }
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(lazyListState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            trackPadding = contentPadding,
        )
        SettingsBottomSheetsHost(
            appState = appState,
            sheetState = sheetState,
            updateAppState = updateAppState,
        )
    }
}
