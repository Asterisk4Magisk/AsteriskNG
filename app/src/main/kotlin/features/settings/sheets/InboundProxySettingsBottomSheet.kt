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
    lockInboundSettings: Boolean,
    transparentProxyPort: String,
    socks5ProxyPort: String,
    enableHttpProxy: Boolean,
    httpProxyPort: String,
    onTransparentProxyPortChange: (String) -> Unit,
    onSocks5ProxyPortChange: (String) -> Unit,
    onEnableHttpProxyChange: (Boolean) -> Unit,
    onHttpProxyPortChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (String, String, Boolean, String) -> Unit,
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
                        socks5ProxyPort,
                        enableHttpProxy,
                        httpProxyPort,
                    )
                },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        key(show, useTun2SocksProxyPort) {
            SettingsSheetContent {
                if (useTun2SocksProxyPort) {
                    ProxyPortTextField(
                        value = socks5ProxyPort,
                        onValueChange = if (lockInboundSettings) {
                            {}
                        } else {
                            onSocks5ProxyPortChange
                        },
                        label = stringResource(R.string.settings_tun2socks_socks5_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        enabled = !lockInboundSettings,
                    )
                } else {
                    ProxyPortTextField(
                        value = transparentProxyPort,
                        onValueChange = if (lockInboundSettings) {
                            {}
                        } else {
                            onTransparentProxyPortChange
                        },
                        label = stringResource(R.string.settings_transparent_proxy_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        enabled = !lockInboundSettings,
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.settings_http_proxy),
                    summary = stringResource(R.string.settings_http_proxy_summary),
                    checked = enableHttpProxy,
                    onCheckedChange = onEnableHttpProxyChange,
                    enabled = !lockInboundSettings,
                    modifier = Modifier.padding(bottom = if (enableHttpProxy) 12.dp else 0.dp),
                )
                AnimatedVisibility(
                    visible = enableHttpProxy,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    ProxyPortTextField(
                        value = httpProxyPort,
                        onValueChange = if (lockInboundSettings) {
                            {}
                        } else {
                            onHttpProxyPortChange
                        },
                        label = stringResource(R.string.settings_http_proxy_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        enabled = !lockInboundSettings,
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
