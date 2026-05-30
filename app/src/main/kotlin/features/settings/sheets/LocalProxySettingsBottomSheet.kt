// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import app.R
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference


@Composable
internal fun LocalProxySettingsBottomSheet(
    show: Boolean,
    port: String,
    enableDynamicPort: Boolean,
    listenAllInterfaces: Boolean,
    username: String,
    password: String,
    onPortChange: (String) -> Unit,
    onEnableDynamicPortChange: (Boolean) -> Unit,
    onListenAllInterfacesChange: (Boolean) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (String, Boolean, Boolean, String, String) -> Unit,
) {
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
                    if (portError == null) {
                        onSave(
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
        key(show) {
            SettingsSheetContent {
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
