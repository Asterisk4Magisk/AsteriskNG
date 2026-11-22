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
import ui.components.AdaptiveBottomSheet
import ui.text.formatTemplate
import utils.toIntInRangeOrNull


@Composable
internal fun tunSettingsSummary(
    mtu: String,
    vpnDns: String,
    ipv4Cidr: String,
    ipv6Cidr: String,
    showVpnDns: Boolean,
): String {
    val template = stringResource(
        if (showVpnDns) R.string.settings_tun_summary else R.string.settings_tun_summary_without_dns,
    )
    return template.formatTemplate(
        "mtu" to mtu,
        "vpnDns" to vpnDns,
        "ipv4" to ipv4Cidr,
        "ipv6" to ipv6Cidr,
    )
}

@Composable
internal fun TunSettingsBottomSheet(
    show: Boolean,
    mtu: String,
    vpnDns: String,
    ipv4Cidr: String,
    ipv6Cidr: String,
    showVpnDns: Boolean,
    onMtuChange: (String) -> Unit,
    onVpnDnsChange: (String) -> Unit,
    onIpv4CidrChange: (String) -> Unit,
    onIpv6CidrChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    val mtuError = if (isTunMtu(mtu)) null else stringResource(R.string.settings_tun_mtu_invalid)
    val vpnDnsError = if (!showVpnDns || isTunVpnDns(vpnDns)) {
        null
    } else {
        stringResource(R.string.settings_tun_dns_invalid)
    }
    val ipv4CidrError = if (isTunIpv4Cidr(ipv4Cidr)) {
        null
    } else {
        stringResource(R.string.settings_tun_ipv4_cidr_invalid)
    }
    val ipv6CidrError = if (isTunIpv6Cidr(ipv6Cidr)) {
        null
    } else {
        stringResource(R.string.settings_tun_ipv6_cidr_invalid)
    }
    val canSave = listOf(mtuError, vpnDnsError, ipv4CidrError, ipv6CidrError).all { it == null }

    AdaptiveBottomSheet(
        show = show,
        title = stringResource(R.string.settings_tun),
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
                            vpnDns.trim(),
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
                    label = stringResource(R.string.settings_tun_mtu),
                    errorText = mtuError,
                    keyboardOptions = fiveDigitKeyboardOptions(),
                    sanitizeInput = ::sanitizeFiveDigitInput,
                )
                if (showVpnDns) {
                    SettingsTextField(
                        value = vpnDns,
                        onValueChange = onVpnDnsChange,
                        label = stringResource(R.string.settings_tun_vpn_dns),
                        errorText = vpnDnsError,
                    )
                }
                SettingsTextField(
                    value = ipv4Cidr,
                    onValueChange = onIpv4CidrChange,
                    label = stringResource(R.string.settings_tun_ipv4_cidr),
                    errorText = ipv4CidrError,
                )
                SettingsTextField(
                    value = ipv6Cidr,
                    onValueChange = onIpv6CidrChange,
                    label = stringResource(R.string.settings_tun_ipv6_cidr),
                    errorText = ipv6CidrError,
                )
            }
        }
    }
}


private fun isTunMtu(value: String): Boolean {
    return value.toIntInRangeOrNull(VpnDefaults.MTU_MIN..VpnDefaults.MTU_MAX) != null
}

private fun isTunVpnDns(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.contains(".") && !trimmed.contains(":") && isIpAddress(trimmed)
}

private fun isTunIpv4Cidr(value: String): Boolean {
    return value.contains(".") && !value.contains(":") && isCidrAddress(value)
}

private fun isTunIpv6Cidr(value: String): Boolean {
    return value.contains(":") && isCidrAddress(value)
}
