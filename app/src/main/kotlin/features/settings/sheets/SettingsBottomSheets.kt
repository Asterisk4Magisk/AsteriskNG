// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import app.R
import engine.network.isCidrAddress
import engine.network.isIpAddress
import engine.vpn.VpnDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import ui.text.formatTemplate


@Composable
internal fun vpnSettingsSummary(
    mtu: String,
    defaultDns: String,
    ipv4Cidr: String,
    ipv6Cidr: String,
): String {
    return stringResource(R.string.settings_vpn_summary).formatTemplate(
        "mtu" to mtu,
        "dns" to defaultDns,
        "ipv4" to ipv4Cidr,
        "ipv6" to ipv6Cidr,
    )
}

@Composable
internal fun VpnSettingsBottomSheet(
    show: Boolean,
    mtu: String,
    defaultDns: String,
    ipv4Cidr: String,
    ipv6Cidr: String,
    onMtuChange: (String) -> Unit,
    onDefaultDnsChange: (String) -> Unit,
    onIpv4CidrChange: (String) -> Unit,
    onIpv6CidrChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    val mtuError = if (isVpnMtu(mtu)) null else stringResource(R.string.settings_vpn_mtu_invalid)
    val defaultDnsError = if (isVpnDefaultDns(defaultDns)) null else stringResource(R.string.settings_vpn_dns_invalid)
    val ipv4CidrError = if (isVpnIpv4Cidr(ipv4Cidr)) {
        null
    } else {
        stringResource(R.string.settings_vpn_ipv4_cidr_invalid)
    }
    val ipv6CidrError = if (isVpnIpv6Cidr(ipv6Cidr)) {
        null
    } else {
        stringResource(R.string.settings_vpn_ipv6_cidr_invalid)
    }
    val canSave = listOf(mtuError, defaultDnsError, ipv4CidrError, ipv6CidrError).all { it == null }

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_vpn),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    if (canSave) {
                        onSave(
                            mtu.trim(),
                            defaultDns.trim(),
                            ipv4Cidr.trim(),
                            ipv6Cidr.trim(),
                        )
                    }
                },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        key(show) {
            SettingsSheetContent {
                SettingsTextField(
                    value = mtu,
                    onValueChange = onMtuChange,
                    label = stringResource(R.string.settings_vpn_mtu),
                    errorText = mtuError,
                    keyboardOptions = fiveDigitKeyboardOptions(),
                    sanitizeInput = ::sanitizeFiveDigitInput,
                )
                SettingsTextField(
                    value = defaultDns,
                    onValueChange = onDefaultDnsChange,
                    label = stringResource(R.string.settings_vpn_default_dns),
                    errorText = defaultDnsError,
                )
                SettingsTextField(
                    value = ipv4Cidr,
                    onValueChange = onIpv4CidrChange,
                    label = stringResource(R.string.settings_vpn_ipv4_cidr),
                    errorText = ipv4CidrError,
                )
                SettingsTextField(
                    value = ipv6Cidr,
                    onValueChange = onIpv6CidrChange,
                    label = stringResource(R.string.settings_vpn_ipv6_cidr),
                    errorText = ipv6CidrError,
                )
            }
        }
    }
}


private fun isVpnMtu(value: String): Boolean {
    return value.toIntOrNull()?.let { it in VpnDefaults.MTU_MIN..VpnDefaults.MTU_MAX } == true
}

private fun isVpnDefaultDns(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.contains(".") && !trimmed.contains(":") && isIpAddress(trimmed)
}

private fun isVpnIpv4Cidr(value: String): Boolean {
    return value.contains(".") && !value.contains(":") && isCidrAddress(value)
}

private fun isVpnIpv6Cidr(value: String): Boolean {
    return value.contains(":") && isCidrAddress(value)
}
