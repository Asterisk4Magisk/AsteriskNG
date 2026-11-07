// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.editor

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.R
import features.proxy.server.model.HTTP
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference


internal fun LazyListScope.httpProxyServer(httpEdit: HTTP) {
    item(key = "properties") {
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        ProxyEditorTextField(stringResource(R.string.proxy_editor_remarks), httpEdit.remarks) { httpEdit.remarks = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_server), httpEdit.server) { httpEdit.server = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_port), httpEdit.port, digitsOnly = true) { httpEdit.port = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_username_optional), httpEdit.user ?: "") { httpEdit.user = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_password_optional), httpEdit.password ?: "") { httpEdit.password = it }
    }
}

internal fun LazyListScope.socksProxyServer(socksEdit: Socks) {
    item(key = "properties") {
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        ProxyEditorTextField(stringResource(R.string.proxy_editor_remarks), socksEdit.remarks) { socksEdit.remarks = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_server), socksEdit.server) { socksEdit.server = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_port), socksEdit.port, digitsOnly = true) { socksEdit.port = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_username_optional), socksEdit.user ?: "") { socksEdit.user = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_password_optional), socksEdit.password ?: "") { socksEdit.password = it }
    }
}

internal fun LazyListScope.shadowsocksProxyServer(ssEdit: Shadowsocks) {
    item(key = "properties") {
        val methodOptions = remember {
            listOf(
                "aes-256-gcm",
                "aes-128-gcm",
                "chacha20-poly1305",
                "chacha20-ietf-poly1305",
                "xchacha20-poly1305",
                "xchacha20-ietf-poly1305",
                "2022-blake3-aes-128-gcm",
                "2022-blake3-aes-256-gcm",
                "2022-blake3-chacha20-poly1305",
            )
        }
        val method = remember {
            mutableIntStateOf(
                if (methodOptions.indexOf(ssEdit.method) > -1) methodOptions.indexOf(ssEdit.method) else 0
            )
        }
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        ProxyEditorTextField(stringResource(R.string.proxy_editor_remarks), ssEdit.remarks) { ssEdit.remarks = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_server), ssEdit.server) { ssEdit.server = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_port), ssEdit.port, digitsOnly = true) { ssEdit.port = it }

        ProxyEditorTextField(stringResource(R.string.proxy_editor_password), ssEdit.password) { ssEdit.password = it }
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_method),
            items = methodOptions,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            selectedIndex = method.intValue,
            onSelectedIndexChange = { newMethod ->
                method.intValue = newMethod
                ssEdit.method = methodOptions[newMethod]
            },
        )
    }
    v2rayServerTransport(ssEdit.parms)
}
