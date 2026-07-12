// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import app.modes.RunModeBpf2Socks
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import app.modes.isRootRunMode
import app.modes.supportsRootEbpfMatcher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SettingsThemeSection(
    colorModeOptions: List<String>,
    colorMode: Int,
    keyColorOptions: List<String>,
    seedIndex: Int,
    languageOptions: List<String>,
    languageMode: Int,
    isThemeColorMode: Boolean,
    onColorModeChange: (Int) -> Unit,
    onSeedIndexChange: (Int) -> Unit,
    onLanguageModeChange: (Int) -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_theme))
    SettingsSectionCard {
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_color_mode),
            items = colorModeOptions,
            selectedIndex = colorMode,
            onSelectedIndexChange = onColorModeChange,
        )
        AnimatedVisibility(
            visible = isThemeColorMode,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            OverlayDropdownPreference(
                title = stringResource(R.string.settings_theme_color),
                items = keyColorOptions,
                selectedIndex = seedIndex,
                onSelectedIndexChange = onSeedIndexChange,
            )
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_language),
            items = languageOptions,
            selectedIndex = languageMode,
            onSelectedIndexChange = onLanguageModeChange,
        )
    }
}

@Composable
internal fun SettingsSubscriptionsSection(
    enableAllProxyGroup: Boolean,
    onOpenGroupManagement: () -> Unit,
    onOpenResourceManagement: () -> Unit,
    onEnableAllProxyGroupChange: (Boolean) -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_general))
    SettingsSectionCard {
        ArrowPreference(
            title = stringResource(R.string.settings_group_management),
            summary = stringResource(R.string.settings_group_management_summary),
            onClick = onOpenGroupManagement,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_resource_management),
            summary = stringResource(R.string.settings_resource_management_summary),
            onClick = onOpenResourceManagement,
        )
        SwitchPreference(
            title = stringResource(R.string.settings_enable_all_proxy_group),
            summary = stringResource(R.string.settings_enable_all_proxy_group_summary),
            checked = enableAllProxyGroup,
            onCheckedChange = onEnableAllProxyGroupChange,
        )
    }
}

@Composable
internal fun SettingsCoreSection(
    enableSniffing: Boolean,
    enableSniffingRouteOnly: Boolean,
    muxSettingsSummary: String,
    fragmentSettingsSummary: String,
    coreLogLevel: Int,
    enableAccessLog: Boolean,
    onOpenDnsSettings: () -> Unit,
    onEnableSniffingChange: (Boolean) -> Unit,
    onEnableSniffingRouteOnlyChange: (Boolean) -> Unit,
    onOpenMuxSettings: () -> Unit,
    onOpenFragmentSettings: () -> Unit,
    onCoreLogLevelChange: (Int) -> Unit,
    onEnableAccessLogChange: (Boolean) -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_core))
    SettingsSectionCard {
        ArrowPreference(
            title = stringResource(R.string.settings_dns),
            summary = stringResource(R.string.settings_dns_summary),
            onClick = onOpenDnsSettings,
        )
        SwitchPreference(
            title = "Sniffing",
            summary = stringResource(R.string.settings_sniffing_summary),
            checked = enableSniffing,
            onCheckedChange = onEnableSniffingChange,
        )
        AnimatedVisibility(
            visible = enableSniffing,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            SwitchPreference(
                title = stringResource(R.string.settings_sniffing_route_only),
                summary = stringResource(R.string.settings_sniffing_route_only_summary),
                checked = enableSniffingRouteOnly,
                onCheckedChange = onEnableSniffingRouteOnlyChange,
            )
        }
        ArrowPreference(
            title = stringResource(R.string.settings_mux),
            summary = muxSettingsSummary,
            onClick = onOpenMuxSettings,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_fragment),
            summary = fragmentSettingsSummary,
            onClick = onOpenFragmentSettings,
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_log_level),
            items = SettingsLogLevelOptions,
            selectedIndex = coreLogLevel,
            onSelectedIndexChange = onCoreLogLevelChange,
        )
        SwitchPreference(
            title = stringResource(R.string.settings_record_access_log),
            checked = enableAccessLog,
            onCheckedChange = onEnableAccessLogChange,
        )
    }
}

@Composable
internal fun SettingsAdvancedSection(
    enableBroadcastControl: Boolean,
    enableIpv6: Boolean,
    enableIpv6Prefer: Boolean,
    runModeOptions: List<String>,
    runMode: Int,
    onEnableBroadcastControlChange: (Boolean) -> Unit,
    onEnableIpv6Change: (Boolean) -> Unit,
    onEnableIpv6PreferChange: (Boolean) -> Unit,
    onRunModeChange: (Int) -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_advanced))
    SettingsSectionCard {
        SwitchPreference(
            title = stringResource(R.string.settings_broadcast_control),
            summary = stringResource(R.string.settings_broadcast_control_summary),
            checked = enableBroadcastControl,
            onCheckedChange = onEnableBroadcastControlChange,
        )
        SwitchPreference(
            title = "IPv6",
            summary = stringResource(R.string.settings_ipv6_summary),
            checked = enableIpv6,
            onCheckedChange = onEnableIpv6Change,
        )
        AnimatedVisibility(
            visible = enableIpv6,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            SwitchPreference(
                title = stringResource(R.string.settings_ipv6_prefer),
                summary = stringResource(R.string.settings_ipv6_prefer_summary),
                checked = enableIpv6Prefer,
                onCheckedChange = onEnableIpv6PreferChange,
            )
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_run_mode),
            items = runModeOptions,
            selectedIndex = runMode.coerceIn(runModeOptions.indices),
            onSelectedIndexChange = onRunModeChange,
        )
    }
}

@Composable
internal fun SettingsProxyModeSections(
    runMode: Int,
    localProxySettingsSummary: String,
    enableTrafficStatsNotification: Boolean,
    enableVpnAppendHttpProxy: Boolean,
    enableVpnHevTun: Boolean,
    tunSettingsSummary: String,
    inboundProxySummary: String,
    enableIpv6: Boolean,
    enableRootBootScript: Boolean,
    enableRootEbpfRules: Boolean,
    enableRootEbpfDirectCidrBypass: Boolean,
    enableRootIpv6Disabler: Boolean,
    externalInterfacesSummary: String,
    ignoredInterfacesSummary: String,
    privateAddressCidrsSummary: String,
    onOpenLocalProxySettings: () -> Unit,
    onEnableTrafficStatsNotificationChange: (Boolean) -> Unit,
    onEnableVpnAppendHttpProxyChange: (Boolean) -> Unit,
    onEnableVpnHevTunChange: (Boolean) -> Unit,
    onOpenTunSettings: () -> Unit,
    onOpenProxySettings: () -> Unit,
    onEnableRootBootScriptChange: (Boolean) -> Unit,
    onEnableRootEbpfRulesChange: (Boolean) -> Unit,
    onEnableRootEbpfDirectCidrBypassChange: (Boolean) -> Unit,
    onEnableRootIpv6DisablerChange: (Boolean) -> Unit,
    onOpenExternalInterfaces: () -> Unit,
    onOpenIgnoredInterfaces: () -> Unit,
    onOpenPrivateAddresses: () -> Unit,
) {
    AnimatedVisibility(
        visible = runMode == RunModeVpnService,
        enter = fadeIn() + expandVertically(),
        exit = ExitTransition.None,
    ) {
        Column {
            SmallTitle(text = stringResource(R.string.settings_proxy_vpn_service))
            SettingsSectionCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_local_proxy),
                    summary = localProxySettingsSummary,
                    onClick = onOpenLocalProxySettings,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_traffic_stats_notification),
                    summary = stringResource(R.string.settings_traffic_stats_notification_summary),
                    checked = enableTrafficStatsNotification,
                    onCheckedChange = onEnableTrafficStatsNotificationChange,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_vpn_append_http_proxy),
                    summary = stringResource(R.string.settings_vpn_append_http_proxy_summary),
                    checked = enableVpnAppendHttpProxy,
                    onCheckedChange = onEnableVpnAppendHttpProxyChange,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_vpn_hev_tun),
                    summary = stringResource(R.string.settings_vpn_hev_tun_summary),
                    checked = enableVpnHevTun,
                    onCheckedChange = onEnableVpnHevTunChange,
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_tun),
                    summary = tunSettingsSummary,
                    onClick = onOpenTunSettings,
                )
            }
        }
    }
    AnimatedVisibility(
        visible = runMode.isRootRunMode(),
        enter = fadeIn() + expandVertically(),
        exit = ExitTransition.None,
    ) {
        Column {
            SmallTitle(
                text = stringResource(
                    when (runMode) {
                        RunModeTun2Socks -> R.string.settings_proxy_tun2socks
                        RunModeBpf2Socks -> R.string.settings_proxy_bpf2socks
                        else -> R.string.settings_proxy_tproxy
                    },
                ),
            )
            SettingsSectionCard {
                AnimatedVisibility(
                    visible = runMode.isRootRunMode(),
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_root_boot_script),
                        summary = stringResource(R.string.settings_root_boot_script_summary),
                        checked = enableRootBootScript,
                        onCheckedChange = onEnableRootBootScriptChange,
                    )
                }
                AnimatedVisibility(
                    visible = runMode.supportsRootEbpfMatcher(),
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_root_ebpf_matcher),
                        summary = stringResource(R.string.settings_root_ebpf_matcher_summary),
                        checked = enableRootEbpfRules,
                        onCheckedChange = onEnableRootEbpfRulesChange,
                    )
                }
                AnimatedVisibility(
                    visible = enableRootEbpfRules || runMode == RunModeBpf2Socks,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_root_ebpf_bypass_direct_cidrs),
                        summary = stringResource(R.string.settings_root_ebpf_bypass_direct_cidrs_summary),
                        checked = enableRootEbpfDirectCidrBypass,
                        onCheckedChange = onEnableRootEbpfDirectCidrBypassChange,
                    )
                }
                AnimatedVisibility(
                    visible = !enableIpv6,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_root_ipv6_disabler),
                        summary = stringResource(R.string.settings_root_ipv6_disabler_summary),
                        checked = enableRootIpv6Disabler,
                        onCheckedChange = onEnableRootIpv6DisablerChange,
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.settings_local_proxy),
                    summary = localProxySettingsSummary,
                    onClick = onOpenLocalProxySettings,
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_inbound),
                    summary = inboundProxySummary,
                    onClick = onOpenProxySettings,
                )
                AnimatedVisibility(
                    visible = runMode == RunModeTun2Socks,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_tun),
                        summary = tunSettingsSummary,
                        onClick = onOpenTunSettings,
                    )
                }
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.settings_external_interfaces),
                        summary = externalInterfacesSummary,
                        onClick = onOpenExternalInterfaces,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_ignored_interfaces),
                        summary = ignoredInterfacesSummary,
                        onClick = onOpenIgnoredInterfaces,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_private_addresses),
                        summary = privateAddressCidrsSummary,
                        onClick = onOpenPrivateAddresses,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsLogsSection(
    enableAccessLog: Boolean,
    onOpenCoreLogs: () -> Unit,
    onOpenAccessLogs: () -> Unit,
    onOpenLogcatLogs: () -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_logs))
    SettingsSectionCard {
        ArrowPreference(
            title = stringResource(R.string.settings_core_logs),
            onClick = onOpenCoreLogs,
        )
        AnimatedVisibility(
            visible = enableAccessLog,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ArrowPreference(
                title = stringResource(R.string.settings_access_logs),
                onClick = onOpenAccessLogs,
            )
        }
        ArrowPreference(
            title = stringResource(R.string.settings_logcat),
            onClick = onOpenLogcatLogs,
        )
    }
}

@Composable
internal fun SettingsBackupRestoreSection(
    onBackupUserData: () -> Unit,
    onRestoreUserData: () -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_backup_restore))
    SettingsSectionCard {
        ArrowPreference(
            title = stringResource(R.string.settings_backup_user_data),
            summary = stringResource(R.string.settings_backup_user_data_summary),
            onClick = onBackupUserData,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_restore_user_data),
            summary = stringResource(R.string.settings_restore_user_data_summary),
            onClick = onRestoreUserData,
        )
    }
}

@Composable
internal fun SettingsAboutSection(
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_about))
    SettingsSectionCard(bottomPadding = 0.dp) {
        ArrowPreference(
            title = stringResource(R.string.settings_about_project),
            onClick = onOpenAbout,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_open_source_licenses),
            onClick = onOpenLicenses,
        )
    }
}
