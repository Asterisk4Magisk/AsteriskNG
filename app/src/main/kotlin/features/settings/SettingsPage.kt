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
import app.modes.RunModeTun2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import app.ProjectInfo
import app.R
import data.backup.AppBackupRestorePreview
import engine.proxy.withResolvedDynamicLocalProxyPort
import features.settings.sheets.externalInterfacesSummary
import features.settings.sheets.fragmentSettingsSummary
import features.settings.sheets.ignoredInterfacesSummary
import features.settings.sheets.muxSettingsSummary
import features.settings.sheets.privateAddressCidrsSummary
import features.settings.sheets.tunSettingsSummary
import features.settings.usecase.SwitchRunModeResult
import features.settings.usecase.RootBootScriptResult
import features.settings.usecase.RootEbpfProbeResult
import features.proxy.server.usecase.ProxyServiceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.navigation.Route
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.remember
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
import ui.components.WarningConfirmDialog
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
    val rootBootScriptUseCase = services.rootBootScriptUseCase
    val rootEbpfProbeUseCase = services.rootEbpfProbeUseCase
    val appBackupUseCase = services.appBackupUseCase
    val proxyServiceUseCase = services.proxyServiceUseCase
    val tipNotifier = services.tipNotifier
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    var runModeSwitchInProgress by rememberSaveable { mutableStateOf(false) }
    var rootBootScriptSwitchInProgress by rememberSaveable { mutableStateOf(false) }
    var rootEbpfSwitchInProgress by rememberSaveable { mutableStateOf(false) }
    var showRootEbpfSelinuxPolicyWarning by rememberSaveable { mutableStateOf(false) }
    var backupRestoreInProgress by rememberSaveable { mutableStateOf(false) }
    var pendingRestorePreview by remember { mutableStateOf<AppBackupRestorePreview?>(null) }
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
        stringResource(R.string.option_russian),
    )
    val runModeOptions = listOf(
        stringResource(R.string.settings_run_mode_vpn_service),
        stringResource(R.string.settings_run_mode_tproxy),
        stringResource(R.string.settings_run_mode_tun2socks),
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
    val rootRequiredMessage = stringResource(R.string.settings_root_required)
    val rootBootScriptFailedMessage = stringResource(R.string.settings_root_boot_script_failed)
    val rootEbpfRulesFailedMessage = stringResource(R.string.settings_root_ebpf_matcher_failed)
    val rootEbpfRulesUnsupportedMessage = stringResource(R.string.settings_root_ebpf_matcher_unsupported)
    val rootEbpfSelinuxPolicyWarningTitle = stringResource(R.string.settings_root_ebpf_selinux_policy_warning_title)
    val rootEbpfSelinuxPolicyWarningSummary = stringResource(R.string.settings_root_ebpf_selinux_policy_warning_summary)
    val rootEbpfSelinuxPolicyWarningConfirm = stringResource(R.string.settings_root_ebpf_selinux_policy_warning_confirm)
    val serviceStoppedMessage = stringResource(R.string.proxy_server_list_service_stopped)
    val backupExportedMessage = stringResource(R.string.settings_backup_exported)
    val backupExportFailedMessage = stringResource(R.string.settings_backup_export_failed)
    val restoreReadFailedMessage = stringResource(R.string.settings_restore_read_failed)
    val restoreCompletedMessage = stringResource(R.string.settings_restore_completed)
    val restoreFailedMessage = stringResource(R.string.settings_restore_failed)
    val selectServerFirstMessage = stringResource(R.string.proxy_server_list_select_first)
    val ignoredInterfacesErrorDetail = stringResource(R.string.settings_ignored_interfaces_error_detail)
    val inboundProxySummary = inboundProxySummary(
        useTun2SocksProxyPort = appState.runMode == RunModeTun2Socks,
        transparentProxyPort = appState.transparentProxyPort,
        socks5ProxyPort = appState.socks5ProxyPort,
        enableHttpProxy = appState.enableHttpProxy,
    )
    val localProxySettingsSummary = localProxySettingsSummary(
        port = appState.localProxyPort,
        listenAllInterfaces = appState.localProxyListenAllInterfaces,
    )
    val externalInterfacesSummary = externalInterfacesSummary(appState.externalInterfaces)
    val ignoredInterfacesSummary = ignoredInterfacesSummary(appState.ignoredInterfaces)
    val privateAddressCidrsSummary = privateAddressCidrsSummary(appState.privateAddressCidrs)
    val tunSettingsSummary = tunSettingsSummary(
        mtu = appState.tunMtu,
        vpnDns = appState.tunVpnDns,
        ipv4Cidr = appState.tunIpv4Cidr,
        ipv6Cidr = appState.tunIpv6Cidr,
        showVpnDns = appState.runMode == RunModeVpnService,
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
                            val currentState = appState
                            runModeSwitchInProgress = true
                            services.appScope.launch {
                                try {
                                    when (val result = switchRunModeUseCase.switchRunMode(currentState, index)) {
                                        is SwitchRunModeResult.Success -> {
                                            updateAppState { state ->
                                                state.copy(
                                                    runMode = result.runMode,
                                                    proxyRunning = result.proxyRunning,
                                                    enableRootBootScript = false,
                                                    enableRootEbpfRules = state.enableRootEbpfRules &&
                                                        (result.runMode == RunModeTproxy || result.runMode == RunModeTun2Socks),
                                                )
                                            }
                                        }

                                        is SwitchRunModeResult.RootUnavailable -> {
                                            updateAppState { state -> state.copy(proxyRunning = result.proxyRunning) }
                                            tipNotifier.show(rootRequiredMessage)
                                        }

                                        is SwitchRunModeResult.StopFailed -> {
                                            tipNotifier.showError(result.error, serviceStoppedMessage)
                                        }
                                    }
                                } finally {
                                    withContext(Dispatchers.Main.immediate) {
                                        runModeSwitchInProgress = false
                                    }
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
                    enableTrafficStatsNotification = appState.enableTrafficStatsNotification,
                    enableVpnAppendHttpProxy = appState.enableVpnAppendHttpProxy,
                    enableVpnHevTun = appState.enableVpnHevTun,
                    tunSettingsSummary = tunSettingsSummary,
                    inboundProxySummary = inboundProxySummary,
                    enableIpv6 = appState.enableIpv6,
                    enableRootBootScript = appState.enableRootBootScript,
                    enableRootEbpfRules = appState.enableRootEbpfRules,
                    enableRootEbpfDirectCidrBypass = appState.enableRootEbpfDirectCidrBypass,
                    enableRootIpv6Disabler = appState.enableRootIpv6Disabler,
                    externalInterfacesSummary = externalInterfacesSummary,
                    ignoredInterfacesSummary = ignoredInterfacesSummary,
                    privateAddressCidrsSummary = privateAddressCidrsSummary,
                    onOpenLocalProxySettings = { sheetState.openLocalProxySettings(appState) },
                    onEnableTrafficStatsNotificationChange = { enabled ->
                        updateAppState { state -> state.copy(enableTrafficStatsNotification = enabled) }
                    },
                    onEnableVpnAppendHttpProxyChange = { enabled ->
                        updateAppState { state -> state.copy(enableVpnAppendHttpProxy = enabled) }
                    },
                    onEnableVpnHevTunChange = { enabled ->
                        updateAppState { state -> state.copy(enableVpnHevTun = enabled) }
                    },
                    onOpenTunSettings = { sheetState.openTunSettings(appState) },
                    onOpenProxySettings = { sheetState.openProxySettings(appState) },
                    onEnableRootBootScriptChange = { enabled ->
                        if (!rootBootScriptSwitchInProgress) {
                            val currentState = appState
                            rootBootScriptSwitchInProgress = true
                            services.appScope.launch {
                                try {
                                    val bootScriptState = if (enabled) {
                                        currentState.withResolvedDynamicLocalProxyPort()
                                    } else {
                                        currentState
                                    }
                                    when (val result = rootBootScriptUseCase.setEnabled(bootScriptState, enabled)) {
                                        RootBootScriptResult.Success -> {
                                            updateAppState { state ->
                                                state.copy(
                                                    enableRootBootScript = enabled,
                                                    localProxyPort = bootScriptState.localProxyPort,
                                                )
                                            }
                                        }

                                        RootBootScriptResult.MissingServer -> {
                                            tipNotifier.show(selectServerFirstMessage)
                                        }

                                        RootBootScriptResult.RootUnavailable -> {
                                            tipNotifier.show(rootRequiredMessage)
                                        }

                                        is RootBootScriptResult.Failed -> {
                                            tipNotifier.showError(result.error, rootBootScriptFailedMessage)
                                        }
                                    }
                                } finally {
                                    withContext(Dispatchers.Main.immediate) {
                                        rootBootScriptSwitchInProgress = false
                                    }
                                }
                            }
                        }
                    },
                    onEnableRootEbpfRulesChange = { enabled ->
                        if (!enabled) {
                            updateAppState { state -> state.copy(enableRootEbpfRules = false) }
                            return@SettingsProxyModeSections
                        }
                        if (!rootEbpfSwitchInProgress) {
                            val currentState = appState
                            rootEbpfSwitchInProgress = true
                            services.appScope.launch {
                                try {
                                    when (val result = rootEbpfProbeUseCase.probe(currentState)) {
                                        is RootEbpfProbeResult.Success -> {
                                            if (result.selinuxPolicyApplicator == null) {
                                                showRootEbpfSelinuxPolicyWarning = true
                                            } else {
                                                updateAppState { state -> state.copy(enableRootEbpfRules = true) }
                                            }
                                        }

                                        is RootEbpfProbeResult.Unsupported -> {
                                            tipNotifier.show(
                                                result.probe.message.takeIf(String::isNotBlank)
                                                    ?: rootEbpfRulesUnsupportedMessage,
                                            )
                                        }

                                        RootEbpfProbeResult.RootUnavailable -> {
                                            tipNotifier.show(rootRequiredMessage)
                                        }

                                        is RootEbpfProbeResult.Failed -> {
                                            tipNotifier.showError(result.error, rootEbpfRulesFailedMessage)
                                        }
                                    }
                                } finally {
                                    withContext(Dispatchers.Main.immediate) {
                                        rootEbpfSwitchInProgress = false
                                    }
                                }
                            }
                        }
                    },
                    onEnableRootEbpfDirectCidrBypassChange = { enabled ->
                        updateAppState { state -> state.copy(enableRootEbpfDirectCidrBypass = enabled) }
                    },
                    onEnableRootIpv6DisablerChange = { enabled ->
                        updateAppState { state -> state.copy(enableRootIpv6Disabler = enabled) }
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
            item(key = "settings_backup_restore") {
                SettingsBackupRestoreSection(
                    onBackupUserData = {
                        if (!backupRestoreInProgress) {
                            val currentState = appState
                            backupRestoreInProgress = true
                            scope.launch {
                                try {
                                    runCatching {
                                        appBackupUseCase.export(currentState)
                                    }.onSuccess { exported ->
                                        if (exported) {
                                            tipNotifier.show(backupExportedMessage)
                                        }
                                    }.onFailure { error ->
                                        tipNotifier.showError(error, backupExportFailedMessage)
                                    }
                                } finally {
                                    backupRestoreInProgress = false
                                }
                            }
                        }
                    },
                    onRestoreUserData = {
                        if (!backupRestoreInProgress) {
                            backupRestoreInProgress = true
                            scope.launch {
                                try {
                                    runCatching {
                                        appBackupUseCase.readRestorePreview()
                                    }.onSuccess { preview ->
                                        pendingRestorePreview = preview
                                    }.onFailure { error ->
                                        tipNotifier.showError(error, restoreReadFailedMessage)
                                    }
                                } finally {
                                    backupRestoreInProgress = false
                                }
                            }
                        }
                    },
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
        SettingsRestoreConfirmDialog(
            preview = pendingRestorePreview,
            onDismissRequest = { pendingRestorePreview = null },
            onRestore = {
                val restorePreview = pendingRestorePreview
                if (restorePreview != null && !backupRestoreInProgress) {
                    backupRestoreInProgress = true
                    scope.launch {
                        try {
                            when (val result = proxyServiceUseCase.stop(appState.runMode)) {
                                is ProxyServiceResult.Success -> Unit
                                ProxyServiceResult.MissingServer -> Unit
                                is ProxyServiceResult.Failed -> {
                                    tipNotifier.showError(result.error, serviceStoppedMessage)
                                    return@launch
                                }
                            }
                            if (appState.enableRootBootScript) {
                                when (val result = rootBootScriptUseCase.uninstall()) {
                                    RootBootScriptResult.Success,
                                    RootBootScriptResult.MissingServer -> Unit

                                    RootBootScriptResult.RootUnavailable -> {
                                        tipNotifier.show(rootRequiredMessage)
                                        return@launch
                                    }

                                    is RootBootScriptResult.Failed -> {
                                        tipNotifier.showError(result.error, rootBootScriptFailedMessage)
                                        return@launch
                                    }
                                }
                            }
                            updateAppState {
                                restorePreview.restoredState
                            }
                            pendingRestorePreview = null
                            tipNotifier.show(restoreCompletedMessage)
                        } catch (error: Throwable) {
                            tipNotifier.showError(error, restoreFailedMessage)
                        } finally {
                            backupRestoreInProgress = false
                        }
                    }
                }
            },
        )
        WarningConfirmDialog(
            show = showRootEbpfSelinuxPolicyWarning,
            title = rootEbpfSelinuxPolicyWarningTitle,
            summary = rootEbpfSelinuxPolicyWarningSummary,
            dismissText = stringResource(R.string.common_cancel),
            confirmText = rootEbpfSelinuxPolicyWarningConfirm,
            onDismissRequest = { showRootEbpfSelinuxPolicyWarning = false },
            onConfirm = {
                updateAppState { state -> state.copy(enableRootEbpfRules = true) }
                showRootEbpfSelinuxPolicyWarning = false
            },
        )
    }
}
