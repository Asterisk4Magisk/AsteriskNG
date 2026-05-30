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
import androidx.compose.ui.unit.dp
import app.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference


@Composable
internal fun ProxySettingsBottomSheet(
    show: Boolean,
    transparentProxyPort: String,
    enableSocks5Proxy: Boolean,
    socks5ProxyPort: String,
    enableHttpProxy: Boolean,
    httpProxyPort: String,
    onTransparentProxyPortChange: (String) -> Unit,
    onEnableSocks5ProxyChange: (Boolean) -> Unit,
    onSocks5ProxyPortChange: (String) -> Unit,
    onEnableHttpProxyChange: (Boolean) -> Unit,
    onHttpProxyPortChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (String, Boolean, String, Boolean, String) -> Unit,
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
                        enableSocks5Proxy,
                        socks5ProxyPort,
                        enableHttpProxy,
                        httpProxyPort,
                    )
                },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        key(show) {
            SettingsSheetContent {
                ProxyPortTextField(
                    value = transparentProxyPort,
                    onValueChange = onTransparentProxyPortChange,
                    label = stringResource(R.string.settings_transparent_proxy_port),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_socks5_proxy),
                    summary = stringResource(R.string.settings_socks5_proxy_summary),
                    checked = enableSocks5Proxy,
                    onCheckedChange = onEnableSocks5ProxyChange,
                )
                AnimatedVisibility(
                    visible = enableSocks5Proxy,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    ProxyPortTextField(
                        value = socks5ProxyPort,
                        onValueChange = onSocks5ProxyPortChange,
                        label = stringResource(R.string.settings_socks5_proxy_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.settings_http_proxy),
                    summary = stringResource(R.string.settings_http_proxy_summary),
                    checked = enableHttpProxy,
                    onCheckedChange = onEnableHttpProxyChange,
                )
                AnimatedVisibility(
                    visible = enableHttpProxy,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    ProxyPortTextField(
                        value = httpProxyPort,
                        onValueChange = onHttpProxyPortChange,
                        label = stringResource(R.string.settings_http_proxy_port),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
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
) {
    SheetTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        keyboardOptions = fiveDigitKeyboardOptions(),
        sanitizeInput = ::sanitizeFiveDigitInput,
    )
}
