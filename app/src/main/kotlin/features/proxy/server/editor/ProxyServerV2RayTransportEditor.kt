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
import features.proxy.server.model.V2RayTransportGrpc
import features.proxy.server.model.V2RayTransportHttpUpgrade
import features.proxy.server.model.V2RayTransportMkcp
import features.proxy.server.model.V2RayTransportOptions
import features.proxy.server.model.V2RayTransportRaw
import features.proxy.server.model.V2RayTransportWebSocket
import features.proxy.server.model.V2RayTransportXhttp
import features.proxy.server.model.v2RayTransportOptionIndex
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference


internal fun LazyListScope.v2rayServerTransport(params: V2RayParameters) {
    item(key = "transport") {
        val focusManager = LocalFocusManager.current
        val typeOptions = remember {
            V2RayTransportOptions
        }
        val typeLabels = remember {
            typeOptions.map { option -> option.label }
        }
        val securityOptions = remember { listOf("none", "tls", "reality") }

        val type = remember {
            mutableIntStateOf(
                v2RayTransportOptionIndex(params.type)
            )
        }
        val security = remember {
            mutableIntStateOf(
                if (securityOptions.indexOf(params.security) > -1) securityOptions.indexOf(params.security) else 0
            )
        }
        val selectedTransportType = typeOptions.getOrElse(type.intValue) { typeOptions.first() }.value

        SmallTitle(text = stringResource(R.string.proxy_editor_transport))
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_transport_type),
            items = typeLabels,
            selectedIndex = type.intValue,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onSelectedIndexChange = { newType ->
                type.intValue = newType
                params.type = typeOptions[newType].value
            },
        )
        //raw
        AnimatedVisibility(
            visible = selectedTransportType == V2RayTransportRaw,
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
            visible = selectedTransportType == V2RayTransportMkcp,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                SmallTitle(text = stringResource(R.string.proxy_editor_transport_kcp))
                ProxyEditorTextField(stringResource(R.string.proxy_editor_mkcp_mtu), params.mtu ?: "", digitsOnly = true) { params.mtu = it }
                ProxyEditorTextField(stringResource(R.string.proxy_editor_mkcp_tti), params.tti ?: "", digitsOnly = true) { params.tti = it }
            }
        }
        //ws
        AnimatedVisibility(
            visible = selectedTransportType == V2RayTransportWebSocket,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                SmallTitle(text = stringResource(R.string.proxy_editor_transport_ws))
                ProxyEditorTextField("ws host", params.host ?: "") { params.host = it }
                ProxyEditorTextField("ws path", params.path ?: "") { params.path = it }
            }
        }
        //httpupgrade
        AnimatedVisibility(
            visible = selectedTransportType == V2RayTransportHttpUpgrade,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                SmallTitle(text = stringResource(R.string.proxy_editor_transport_httpupgrade))
                ProxyEditorTextField("httpupgrade host", params.host ?: "") { params.host = it }
                ProxyEditorTextField("httpupgrade path", params.path ?: "") { params.path = it }
            }
        }
        //xhttp
        AnimatedVisibility(
            visible = selectedTransportType == V2RayTransportXhttp,
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
                ProxyEditorTextField("xhttp host", params.host ?: "") { params.host = it }
                ProxyEditorTextField("xhttp path", params.path ?: "") { params.path = it }
                ProxyEditorTextField(stringResource(R.string.proxy_editor_xhttp_extra), params.extra ?: "", multiLine = true) { params.extra = it }
            }
        }
        //grpc
        AnimatedVisibility(
            visible = selectedTransportType == V2RayTransportGrpc,
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
                ProxyEditorTextField("gRPC authority", params.authority ?: "") { params.authority = it }
                ProxyEditorTextField("gRPC serviceName", params.serviceName ?: "") { params.serviceName = it }
            }
        }
        ProxyEditorTextField(stringResource(R.string.proxy_editor_final_mask), params.fm ?: "", multiLine = true) { params.fm = it }

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
                ProxyEditorTextField("SNI", params.sni ?: "") { params.sni = it }
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
                ProxyEditorTextField("EchConfigList", params.ech ?: "") { params.ech = it }
                ProxyEditorTextField(stringResource(R.string.proxy_editor_certificate_fingerprint), params.pcs ?: "") { params.pcs = it }
                ProxyEditorTextField("VerifyPeerCertByName", params.vcn ?: "") { params.vcn = it }
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
                ProxyEditorTextField("SNI", params.sni ?: "") { params.sni = it }
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
                ProxyEditorTextField("PublicKey", params.pbk ?: "") { params.pbk = it }
                ProxyEditorTextField("ShortId", params.sid ?: "") { params.sid = it }
                ProxyEditorTextField("SpiderX", params.spx ?: "") { params.spx = it }
                ProxyEditorTextField("Mldsa65Verify", params.pqv ?: "") { params.pqv = it }
            }
        }
    }
}
