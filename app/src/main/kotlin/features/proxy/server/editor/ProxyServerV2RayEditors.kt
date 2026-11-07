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
import features.proxy.server.model.Trojan
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference


internal fun LazyListScope.vmessProxyServer(vmessEdit: VMess) {
    item(key = "properties") {
        val encryptionOptions = remember {
            listOf(
                "auto",
                "aes-128-gcm",
                "chacha20-poly1305",
            )
        }
        val encryption = remember {
            mutableIntStateOf(
                if (encryptionOptions.indexOf(vmessEdit.encryption) > -1)
                    encryptionOptions.indexOf(vmessEdit.encryption) else 0
            )
        }
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        ProxyEditorTextField(stringResource(R.string.proxy_editor_remarks), vmessEdit.remarks) { vmessEdit.remarks = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_server), vmessEdit.server) { vmessEdit.server = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_port), vmessEdit.port, digitsOnly = true) { vmessEdit.port = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_user_id), vmessEdit.id) { vmessEdit.id = it }
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_encryption),
            items = encryptionOptions,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            selectedIndex = encryption.intValue,
            onSelectedIndexChange = { newEncryption ->
                encryption.intValue = newEncryption
                vmessEdit.encryption = encryptionOptions[newEncryption]
            },
        )
    }
    v2rayServerTransport(vmessEdit.parms)
}

internal fun LazyListScope.trojanProxyServer(trojanEdit: Trojan) {
    item(key = "properties") {
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        ProxyEditorTextField(stringResource(R.string.proxy_editor_remarks), trojanEdit.remarks) { trojanEdit.remarks = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_server), trojanEdit.server) { trojanEdit.server = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_port), trojanEdit.port, digitsOnly = true) { trojanEdit.port = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_password), trojanEdit.password) { trojanEdit.password = it }
    }
    v2rayServerTransport(trojanEdit.parms)
}

internal fun LazyListScope.vlessProxyServer(vlessEdit: VLESS) {
    item(key = "properties") {
        val flowOptions = remember {
            listOf(
                "",
                "xtls-rprx-vision",
                "xtls-rprx-vision-udp443",
            )
        }
        val flow = remember {
            mutableIntStateOf(
                if (flowOptions.indexOf(vlessEdit.flow) > -1) flowOptions.indexOf(vlessEdit.flow) else 0
            )
        }
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        ProxyEditorTextField(stringResource(R.string.proxy_editor_remarks), vlessEdit.remarks) { vlessEdit.remarks = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_server), vlessEdit.server) { vlessEdit.server = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_port), vlessEdit.port, digitsOnly = true) { vlessEdit.port = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_user_id), vlessEdit.id) { vlessEdit.id = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_encryption), vlessEdit.encryption.ifBlank { "none" }) { vlessEdit.encryption = it }
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_flow),
            items = flowOptions,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            selectedIndex = flow.intValue,
            onSelectedIndexChange = { newFlow ->
                flow.intValue = newFlow
                vlessEdit.flow = flowOptions[newFlow]
            },
        )
    }
    v2rayServerTransport(vlessEdit.parms)
}
