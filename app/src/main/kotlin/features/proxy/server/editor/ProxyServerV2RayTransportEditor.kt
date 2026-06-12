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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.R
import features.proxy.server.model.V2RayParameters
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference


internal fun LazyListScope.v2rayServerTransport(params: V2RayParameters) {
    item(key = "transport") {
        val focusManager = LocalFocusManager.current
        val typeOptions = remember {
            listOf(
                "raw",
                "kcp",
                "ws",
                "httpupgrade",
                "xhttp",
                "grpc",
            )
        }
        val securityOptions = remember { listOf("none", "tls", "reality") }

        val type = remember {
            mutableIntStateOf(
                if (typeOptions.indexOf(params.type) > -1) typeOptions.indexOf(params.type) else 0
            )
        }
        val security = remember {
            mutableIntStateOf(
                if (securityOptions.indexOf(params.security) > -1) securityOptions.indexOf(params.security) else 0
            )
        }

        SmallTitle(text = stringResource(R.string.proxy_editor_transport))
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_transport_type),
            items = typeOptions,
            selectedIndex = type.intValue,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onSelectedIndexChange = { newType ->
                type.intValue = newType
                params.type = typeOptions[newType]
            },
        )
        //raw
        AnimatedVisibility(
            visible = type.intValue == 0,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                val headerTypeOptions = remember {
                    listOf("none", "http")
                }
                val headerType = remember {
                    mutableIntStateOf(
                        if (headerTypeOptions.indexOf(params.headerType) > -1) headerTypeOptions.indexOf(params.headerType) else 0
                    )
                }
                SmallTitle(text = stringResource(R.string.proxy_editor_transport_raw))
                OverlayDropdownPreference(
                    title = stringResource(R.string.proxy_editor_header_type),
                    items = headerTypeOptions,
                    selectedIndex = headerType.intValue,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onSelectedIndexChange = { newHeaderType ->
                        headerType.intValue = newHeaderType
                        params.headerType = headerTypeOptions[newHeaderType]
                    },
                )
                TextField(
                    enabled = headerType.intValue != 0,
                    label = "http host",
                    state = rememberTextFieldState(initialText = params.host ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.host = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
        //kcp
        AnimatedVisibility(
            visible = type.intValue == 1,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                SmallTitle(text = stringResource(R.string.proxy_editor_transport_kcp))
                TextField(
                    label = stringResource(R.string.proxy_editor_mkcp_mtu),
                    state = rememberTextFieldState(initialText = params.mtu ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        if (!asCharSequence().isDigitsOnly()) {
                            revertAllChanges()
                            return@InputTransformation
                        }
                        params.mtu = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = stringResource(R.string.proxy_editor_mkcp_tti),
                    state = rememberTextFieldState(initialText = params.tti ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        if (!asCharSequence().isDigitsOnly()) {
                            revertAllChanges()
                            return@InputTransformation
                        }
                        params.tti = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
        //ws
        AnimatedVisibility(
            visible = type.intValue == 2,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                SmallTitle(text = stringResource(R.string.proxy_editor_transport_ws))
                TextField(
                    label = "ws host",
                    state = rememberTextFieldState(initialText = params.host ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.host = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = "ws path",
                    state = rememberTextFieldState(initialText = params.path ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.path = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
        //httpupgrade
        AnimatedVisibility(
            visible = type.intValue == 3,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                SmallTitle(text = stringResource(R.string.proxy_editor_transport_httpupgrade))
                TextField(
                    label = "httpupgrade host",
                    state = rememberTextFieldState(initialText = params.host ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.host = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = "httpupgrade path",
                    state = rememberTextFieldState(initialText = params.path ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.path = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
        //xhttp
        AnimatedVisibility(
            visible = type.intValue == 4,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                val modeOptions = remember {
                    listOf("auto", "packet-up", "stream-up", "stream-one")
                }
                val mode = remember {
                    mutableIntStateOf(
                        if (modeOptions.indexOf(params.mode) > -1) modeOptions.indexOf(params.mode) else 0
                    )
                }
                SmallTitle(text = stringResource(R.string.proxy_editor_transport_xhttp))
                OverlayDropdownPreference(
                    title = stringResource(R.string.proxy_editor_xhttp_mode),
                    items = modeOptions,
                    selectedIndex = mode.intValue,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onSelectedIndexChange = { newMode ->
                        mode.intValue = newMode
                        params.mode = modeOptions[newMode]
                    },
                )
                TextField(
                    label = "xhttp host",
                    state = rememberTextFieldState(initialText = params.host ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.host = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = "xhttp path",
                    state = rememberTextFieldState(initialText = params.path ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.path = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = stringResource(R.string.proxy_editor_xhttp_extra),
                    state = rememberTextFieldState(initialText = params.extra ?: ""),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5, maxHeightInLines = 20),
                    inputTransformation = InputTransformation {
                        params.extra = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
        //grpc
        AnimatedVisibility(
            visible = type.intValue == 5,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                val modeOptions = remember {
                    listOf("gun", "multi")
                }
                val mode = remember {
                    mutableIntStateOf(
                        if (modeOptions.indexOf(params.mode) > -1) modeOptions.indexOf(params.mode) else 0
                    )
                }
                SmallTitle(text = stringResource(R.string.proxy_editor_transport_grpc))
                OverlayDropdownPreference(
                    title = stringResource(R.string.proxy_editor_grpc_mode),
                    items = modeOptions,
                    selectedIndex = mode.intValue,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onSelectedIndexChange = { newMode ->
                        mode.intValue = newMode
                        params.mode = modeOptions[newMode]
                    },
                )
                TextField(
                    label = "gRPC authority",
                    state = rememberTextFieldState(initialText = params.authority ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.authority = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = "gRPC serviceName",
                    state = rememberTextFieldState(initialText = params.serviceName ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.serviceName = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
        TextField(
            label = stringResource(R.string.proxy_editor_final_mask),
            state = rememberTextFieldState(initialText = params.fm ?: ""),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5, maxHeightInLines = 20),
            inputTransformation = InputTransformation {
                params.fm = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        val fingerprintOptions = remember {
            listOf(
                "",
                "chrome",
                "firefox",
                "safari",
                "ios",
                "android",
                "edge",
                "360",
                "qq",
                "random",
                "randomized",
            )
        }
        val alpnOptions = remember {
            listOf(
                "",
                "h3",
                "h2",
                "http/1.1",
                "h3,h2,http/1.1",
                "h3,h2",
                "h2,http/1.1",
            )
        }
        val fingerprint = remember {
            mutableIntStateOf(
                if (fingerprintOptions.indexOf(params.fp) > -1) fingerprintOptions.indexOf(params.fp) else 0
            )
        }
        val alpn = remember {
            mutableIntStateOf(
                if (alpnOptions.indexOf(params.alpn) > -1) alpnOptions.indexOf(params.alpn) else 0
            )
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_security),
            items = securityOptions,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            selectedIndex = security.intValue,
            onSelectedIndexChange = { newSecurity ->
                security.intValue = newSecurity
                params.security = securityOptions[newSecurity]
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
                TextField(
                    label = "SNI",
                    state = rememberTextFieldState(initialText = params.sni ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.sni = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                        params.fp = fingerprintOptions[newFingerprint]
                    },
                )
                OverlayDropdownPreference(
                    title = "ALPN",
                    items = alpnOptions,
                    selectedIndex = alpn.intValue,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onSelectedIndexChange = { newAlpn ->
                        alpn.intValue = newAlpn
                        params.alpn = alpnOptions[newAlpn]
                    },
                )
                TextField(
                    label = "EchConfigList",
                    state = rememberTextFieldState(initialText = params.ech ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.ech = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = stringResource(R.string.proxy_editor_certificate_fingerprint),
                    state = rememberTextFieldState(initialText = params.pcs ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.pcs = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = "VerifyPeerCertByName",
                    state = rememberTextFieldState(initialText = params.vcn ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.vcn = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
        //reality
        AnimatedVisibility(
            visible = security.intValue == 2,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                SmallTitle(text = stringResource(R.string.proxy_editor_reality_settings))
                TextField(
                    label = "SNI",
                    state = rememberTextFieldState(initialText = params.sni ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.sni = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                        params.fp = fingerprintOptions[newFingerprint]
                    },
                )
                TextField(
                    label = "PublicKey",
                    state = rememberTextFieldState(initialText = params.pbk ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.pbk = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = "ShortId",
                    state = rememberTextFieldState(initialText = params.sid ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.sid = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = "SpiderX",
                    state = rememberTextFieldState(initialText = params.spx ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.spx = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    label = "Mldsa65Verify",
                    state = rememberTextFieldState(initialText = params.pqv ?: ""),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        params.pqv = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
    }
}
