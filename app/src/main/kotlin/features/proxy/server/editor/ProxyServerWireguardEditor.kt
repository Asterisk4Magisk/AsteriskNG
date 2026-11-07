// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.editor

import androidx.compose.foundation.lazy.LazyListScope
import app.R
import features.proxy.server.model.Wireguard
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.SmallTitle


internal fun LazyListScope.wireguardProxyServer(wgEdit: Wireguard) {
    item(key = "properties") {
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        ProxyEditorTextField(stringResource(R.string.proxy_editor_remarks), wgEdit.remarks) { wgEdit.remarks = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_server), wgEdit.server) { wgEdit.server = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_port), wgEdit.port, digitsOnly = true) { wgEdit.port = it }
        ProxyEditorTextField("SecretKey", wgEdit.secretKey) { wgEdit.secretKey = it }
        ProxyEditorTextField("PublicKey", wgEdit.publicKey) { wgEdit.publicKey = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_preshared_key_optional), wgEdit.preSharedKey) { wgEdit.preSharedKey = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_reserved_optional), wgEdit.reserved) { wgEdit.reserved = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_local_address_optional), wgEdit.address) { wgEdit.address = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_mtu_optional), wgEdit.mtu, digitsOnly = true) { wgEdit.mtu = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_remote_dns_optional), wgEdit.remoteDNS) { wgEdit.remoteDNS = it }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_final_mask), wgEdit.finalMask, multiLine = true) { wgEdit.finalMask = it }
    }
}
