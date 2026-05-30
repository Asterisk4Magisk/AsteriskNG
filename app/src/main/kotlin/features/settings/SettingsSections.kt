// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
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
    SmallTitle(text = stringResource(R.string.settings_subscriptions))
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
    enableIpv6: Boolean,
    enableIpv6Prefer: Boolean,
    runModeOptions: List<String>,
    runMode: Int,
    onEnableIpv6Change: (Boolean) -> Unit,
    onEnableIpv6PreferChange: (Boolean) -> Unit,
    onRunModeChange: (Int) -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_advanced))
    SettingsSectionCard {
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
    enableVpnAppendHttpProxy: Boolean,
    vpnSettingsSummary: String,
    inboundProxySummary: String,
    enableTproxyBootScript: Boolean,
    externalInterfacesSummary: String,
    ignoredInterfacesSummary: String,
    privateAddressCidrsSummary: String,
    onOpenLocalProxySettings: () -> Unit,
    onEnableVpnAppendHttpProxyChange: (Boolean) -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenProxySettings: () -> Unit,
    onEnableTproxyBootScriptChange: (Boolean) -> Unit,
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
                    title = stringResource(R.string.settings_vpn_append_http_proxy),
                    summary = stringResource(R.string.settings_vpn_append_http_proxy_summary),
                    checked = enableVpnAppendHttpProxy,
                    onCheckedChange = onEnableVpnAppendHttpProxyChange,
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_vpn),
                    summary = vpnSettingsSummary,
                    onClick = onOpenVpnSettings,
                )
            }
        }
    }
    AnimatedVisibility(
        visible = runMode == RunModeTproxy,
        enter = fadeIn() + expandVertically(),
        exit = ExitTransition.None,
    ) {
        Column {
            SmallTitle(text = stringResource(R.string.settings_proxy_tproxy))
            SettingsSectionCard {
                SwitchPreference(
                    title = stringResource(R.string.settings_tproxy_boot_script),
                    summary = stringResource(R.string.settings_tproxy_boot_script_summary),
                    checked = enableTproxyBootScript,
                    onCheckedChange = onEnableTproxyBootScriptChange,
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_inbound),
                    summary = inboundProxySummary,
                    onClick = onOpenProxySettings,
                )
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
