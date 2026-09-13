// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

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
internal fun LocalProxySettingsBottomSheet(
    show: Boolean,
    showInboundProxyPort: Boolean,
    useTun2SocksProxyPort: Boolean,
    useBpf2SocksProxyPort: Boolean,
    lockInboundProxyPort: Boolean,
    inboundProxyPort: String,
    bpf2SocksBridgePort: String,
    port: String,
    enableDynamicPort: Boolean,
    listenAllInterfaces: Boolean,
    username: String,
    password: String,
    onInboundProxyPortChange: (String) -> Unit,
    onBpf2SocksBridgePortChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onEnableDynamicPortChange: (Boolean) -> Unit,
    onListenAllInterfacesChange: (Boolean) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (String, String, String, Boolean, Boolean, String, String) -> Unit,
) {
    val bridgePortError = if (useBpf2SocksProxyPort && !lockInboundProxyPort && !isPort(bpf2SocksBridgePort)) {
        stringResource(R.string.settings_local_proxy_port_invalid)
    } else {
        null
    }
    val inboundProxyPortError = if (showInboundProxyPort && !lockInboundProxyPort && !isPort(inboundProxyPort)) {
        stringResource(R.string.settings_local_proxy_port_invalid)
    } else {
        null
    }
    val portError = if (isPort(port)) null else stringResource(R.string.settings_local_proxy_port_invalid)

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_local_proxy),
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
                    if (portError == null && inboundProxyPortError == null && bridgePortError == null) {
                        onSave(
                            inboundProxyPort.trim(),
                            bpf2SocksBridgePort.trim(),
                            port.trim(),
                            enableDynamicPort,
                            listenAllInterfaces,
                            username.trim(),
                            password,
                        )
                    }
                },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        key(show, showInboundProxyPort, useTun2SocksProxyPort, useBpf2SocksProxyPort) {
            SettingsSheetContent {
                if (showInboundProxyPort) {
                    if (useBpf2SocksProxyPort) {
                        InboundProxyPortTextField(
                            value = bpf2SocksBridgePort,
                            onValueChange = onBpf2SocksBridgePortChange,
                            label = stringResource(R.string.settings_bpf2socks_bridge_port),
                            errorText = bridgePortError,
                            enabled = !lockInboundProxyPort,
                        )
                    }
                    InboundProxyPortTextField(
                        value = inboundProxyPort,
                        onValueChange = onInboundProxyPortChange,
                        label = stringResource(
                            when {
                                useBpf2SocksProxyPort -> R.string.settings_bpf2socks_socks5_port
                                useTun2SocksProxyPort -> R.string.settings_tun2socks_socks5_port
                                else -> R.string.settings_transparent_proxy_port
                            },
                        ),
                        errorText = inboundProxyPortError,
                        enabled = !lockInboundProxyPort,
                    )
                }
                SettingsTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = stringResource(R.string.settings_local_proxy_port),
                    errorText = portError,
                    keyboardOptions = fiveDigitKeyboardOptions(),
                    sanitizeInput = ::sanitizeFiveDigitInput,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_local_proxy_dynamic_port),
                    summary = stringResource(R.string.settings_local_proxy_dynamic_port_summary),
                    checked = enableDynamicPort,
                    onCheckedChange = onEnableDynamicPortChange,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_local_proxy_listen_all_interfaces),
                    summary = stringResource(R.string.settings_local_proxy_listen_all_interfaces_summary),
                    checked = listenAllInterfaces,
                    onCheckedChange = onListenAllInterfacesChange,
                )
                SettingsTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = stringResource(R.string.settings_local_proxy_username),
                    errorText = null,
                )
                SettingsTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.settings_local_proxy_password),
                    errorText = null,
                )
            }
        }
    }
}

@Composable
private fun InboundProxyPortTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorText: String?,
    enabled: Boolean,
) {
    if (enabled) {
        SettingsTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            errorText = errorText,
            keyboardOptions = fiveDigitKeyboardOptions(),
            sanitizeInput = ::sanitizeFiveDigitInput,
        )
    } else {
        SheetTextField(
            value = value,
            onValueChange = {},
            label = label,
            enabled = false,
            keyboardOptions = fiveDigitKeyboardOptions(),
            sanitizeInput = ::sanitizeFiveDigitInput,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        )
    }
}
