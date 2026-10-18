// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.editor

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.R
import features.proxy.server.model.Masque
import features.proxy.server.model.MasqueAlpnOptions
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

internal fun LazyListScope.masqueProxyServer(masqueEdit: Masque) {
    item(key = "properties") {
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        MasqueTextField(stringResource(R.string.proxy_editor_remarks), masqueEdit.remarks) {
            masqueEdit.remarks = it
        }
        MasqueTextField(stringResource(R.string.proxy_editor_server), masqueEdit.server) {
            masqueEdit.server = it
        }
        MasqueTextField(stringResource(R.string.proxy_editor_port), masqueEdit.port, digitsOnly = true) {
            masqueEdit.port = it
        }
        MasqueTextField(stringResource(R.string.proxy_editor_username_optional), masqueEdit.user) {
            masqueEdit.user = it
        }
        MasqueTextField(stringResource(R.string.proxy_editor_password_optional), masqueEdit.pass) {
            masqueEdit.pass = it
        }
        MasqueTextField(stringResource(R.string.proxy_editor_masque_host_optional), masqueEdit.host) {
            masqueEdit.host = it
        }
        MasqueTextField(stringResource(R.string.proxy_editor_masque_path_optional), masqueEdit.path) {
            masqueEdit.path = it
        }
        MasqueTextField(stringResource(R.string.proxy_editor_masque_remote_dns_optional), masqueEdit.remoteDNS) {
            masqueEdit.remoteDNS = it
        }
    }
    item(key = "tls") {
        val fingerprintOptions = remember {
            listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized")
        }
        val fingerprint = remember {
            mutableIntStateOf(fingerprintOptions.indexOf(masqueEdit.fingerprint).coerceAtLeast(0))
        }
        val alpn = remember {
            mutableIntStateOf(MasqueAlpnOptions.indexOf(masqueEdit.alpn).coerceAtLeast(0))
        }
        SmallTitle(text = stringResource(R.string.proxy_editor_tls_settings))
        MasqueTextField("SNI", masqueEdit.sni) {
            masqueEdit.sni = it
        }
        OverlayDropdownPreference(
            title = "ALPN",
            items = MasqueAlpnOptions,
            selectedIndex = alpn.intValue,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onSelectedIndexChange = { newAlpn ->
                alpn.intValue = newAlpn
                masqueEdit.alpn = MasqueAlpnOptions[newAlpn]
            },
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_tls_fingerprint),
            items = fingerprintOptions,
            selectedIndex = fingerprint.intValue,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onSelectedIndexChange = { newFingerprint ->
                fingerprint.intValue = newFingerprint
                masqueEdit.fingerprint = fingerprintOptions[newFingerprint]
            },
        )
        MasqueTextField(stringResource(R.string.proxy_editor_certificate_fingerprint), masqueEdit.pinSHA256) {
            masqueEdit.pinSHA256 = it
        }
    }
    item(key = "warp") {
        SmallTitle(text = stringResource(R.string.proxy_editor_masque_warp_settings))
        MasqueTextField("PrivateKey", masqueEdit.warpPrivateKey) {
            masqueEdit.warpPrivateKey = it
        }
        MasqueTextField("PublicKey", masqueEdit.warpPublicKey) {
            masqueEdit.warpPublicKey = it
        }
        MasqueTextField(stringResource(R.string.proxy_editor_masque_warp_address), masqueEdit.warpAddress) {
            masqueEdit.warpAddress = it
        }
    }
    item(key = "finalmask") {
        MasqueTextField(stringResource(R.string.proxy_editor_final_mask), masqueEdit.finalMask, multiLine = true) {
            masqueEdit.finalMask = it
        }
    }
}

@Composable
private fun MasqueTextField(
    label: String,
    initialText: String,
    digitsOnly: Boolean = false,
    multiLine: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    TextField(
        label = label,
        state = rememberTextFieldState(initialText = initialText),
        lineLimits = if (multiLine) {
            TextFieldLineLimits.MultiLine(minHeightInLines = 5, maxHeightInLines = 20)
        } else {
            TextFieldLineLimits.SingleLine
        },
        inputTransformation = InputTransformation {
            if (digitsOnly && !asCharSequence().isDigitsOnly()) {
                revertAllChanges()
                return@InputTransformation
            }
            onValueChange(asCharSequence().toString())
        },
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        onKeyboardAction = { focusManager.clearFocus() },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
}
