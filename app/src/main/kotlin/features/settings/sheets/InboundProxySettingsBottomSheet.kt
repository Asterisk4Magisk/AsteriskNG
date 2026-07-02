// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.R
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowBottomSheet

@Composable
internal fun ProxySettingsBottomSheet(
    show: Boolean,
    useTun2SocksProxyPort: Boolean,
    useBpf2SocksProxyPort: Boolean,
    lockPrimaryPortSettings: Boolean,
    lockSharedInboundSettings: Boolean,
    transparentProxyPort: String,
    bpf2SocksBridgePort: String,
    socks5ProxyPort: String,
    enableHttpProxy: Boolean,
    httpProxyPort: String,
    onTransparentProxyPortChange: (String) -> Unit,
    onBpf2SocksBridgePortChange: (String) -> Unit,
    onSocks5ProxyPortChange: (String) -> Unit,
    onEnableHttpProxyChange: (Boolean) -> Unit,
    onHttpProxyPortChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (String, String, String, Boolean, String) -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_inbound),
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
                    onSave(
                        transparentProxyPort,
                        bpf2SocksBridgePort,
                        socks5ProxyPort,
                        enableHttpProxy,
                        httpProxyPort,
                    )
                },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        key(show, useTun2SocksProxyPort, useBpf2SocksProxyPort) {
            SettingsSheetContent {
                if (useBpf2SocksProxyPort) {
                    ProxyPortTextField(
                        value = bpf2SocksBridgePort,
                        onValueChange = if (lockPrimaryPortSettings) {
                            {}
                        } else {
                            onBpf2SocksBridgePortChange
                        },
                        label = stringResource(R.string.settings_bpf2socks_bridge_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        enabled = !lockPrimaryPortSettings,
                    )
                    ProxyPortTextField(
                        value = socks5ProxyPort,
                        onValueChange = if (lockPrimaryPortSettings) {
                            {}
                        } else {
                            onSocks5ProxyPortChange
                        },
                        label = stringResource(R.string.settings_bpf2socks_socks5_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        enabled = !lockPrimaryPortSettings,
                    )
                } else if (useTun2SocksProxyPort) {
                    ProxyPortTextField(
                        value = socks5ProxyPort,
                        onValueChange = if (lockPrimaryPortSettings) {
                            {}
                        } else {
                            onSocks5ProxyPortChange
                        },
                        label = stringResource(R.string.settings_tun2socks_socks5_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        enabled = !lockPrimaryPortSettings,
                    )
                } else {
                    ProxyPortTextField(
                        value = transparentProxyPort,
                        onValueChange = if (lockPrimaryPortSettings) {
                            {}
                        } else {
                            onTransparentProxyPortChange
                        },
                        label = stringResource(R.string.settings_transparent_proxy_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        enabled = !lockPrimaryPortSettings,
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.settings_http_proxy),
                    summary = stringResource(R.string.settings_http_proxy_summary),
                    checked = enableHttpProxy,
                    onCheckedChange = onEnableHttpProxyChange,
                    enabled = !lockSharedInboundSettings,
                    modifier = Modifier.padding(bottom = if (enableHttpProxy) 12.dp else 0.dp),
                )
                AnimatedVisibility(
                    visible = enableHttpProxy,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    ProxyPortTextField(
                        value = httpProxyPort,
                        onValueChange = if (lockSharedInboundSettings) {
                            {}
                        } else {
                            onHttpProxyPortChange
                        },
                        label = stringResource(R.string.settings_http_proxy_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        enabled = !lockSharedInboundSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyPortTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SheetTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        keyboardOptions = fiveDigitKeyboardOptions(),
        sanitizeInput = ::sanitizeFiveDigitInput,
    )
}
