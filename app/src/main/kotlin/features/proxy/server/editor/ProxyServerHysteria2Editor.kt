// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.R
import features.proxy.server.model.Hysteria2
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference


internal fun LazyListScope.hysteria2ProxyServer(hy2Edit: Hysteria2) {
    item(key = "properties") {
        val focusManager = LocalFocusManager.current
        val securityOptions = remember { listOf("none", "tls") }
        val security = remember {
            mutableIntStateOf(
                if (securityOptions.indexOf(hy2Edit.security) > -1) securityOptions.indexOf(hy2Edit.security) else 0
            )
        }
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        ProxyEditorTextField(stringResource(R.string.proxy_editor_remarks), hy2Edit.remarks) { hy2Edit.remarks = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_server), hy2Edit.server) { hy2Edit.server = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_port), hy2Edit.port, digitsOnly = true) { hy2Edit.port = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_auth), hy2Edit.auth) { hy2Edit.auth = it }
        TextField(
            label = stringResource(R.string.proxy_editor_obfs_password),
            state = rememberTextFieldState(initialText = hy2Edit.obfsPassword),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                val obfsPassword = asCharSequence().toString()
                hy2Edit.obfs = if (obfsPassword.isNotBlank()) "salamander" else ""
                hy2Edit.obfsPassword = obfsPassword
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        ProxyEditorTextField(stringResource(R.string.proxy_editor_mport), hy2Edit.mport) { hy2Edit.mport = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_mport_hop_interval), hy2Edit.mportHopInt, digitsOnly = true) { hy2Edit.mportHopInt = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_down_bandwidth), hy2Edit.down) { hy2Edit.down = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_up_bandwidth), hy2Edit.up) { hy2Edit.up = it }
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_security),
            items = securityOptions,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            selectedIndex = security.intValue,
            onSelectedIndexChange = { newSecurity ->
                security.intValue = newSecurity
                hy2Edit.security = securityOptions[newSecurity]
            },
        )
        //tls
        AnimatedVisibility(
            visible = security.intValue == 1,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                SmallTitle(text = stringResource(R.string.proxy_editor_tls_settings))
                ProxyEditorTextField("SNI", hy2Edit.sni) { hy2Edit.sni = it }
                ProxyEditorTextField(stringResource(R.string.proxy_editor_certificate_fingerprint), hy2Edit.pinSHA256) { hy2Edit.pinSHA256 = it }
            }
        }
    }
}
