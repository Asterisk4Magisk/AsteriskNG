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
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = hy2Edit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                hy2Edit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_server),
            state = rememberTextFieldState(initialText = hy2Edit.server),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                hy2Edit.server = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_port),
            state = rememberTextFieldState(initialText = hy2Edit.port),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                hy2Edit.port = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_auth),
            state = rememberTextFieldState(initialText = hy2Edit.auth),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                hy2Edit.auth = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
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
        TextField(
            label = stringResource(R.string.proxy_editor_mport),
            state = rememberTextFieldState(initialText = hy2Edit.mport),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                hy2Edit.mport = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_mport_hop_interval),
            state = rememberTextFieldState(initialText = hy2Edit.mportHopInt),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                hy2Edit.mportHopInt = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_down_bandwidth),
            state = rememberTextFieldState(initialText = hy2Edit.down),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                hy2Edit.down = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_up_bandwidth),
            state = rememberTextFieldState(initialText = hy2Edit.up),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                hy2Edit.up = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_security),
            items = securityOptions,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            selectedIndex = security.value,
            onSelectedIndexChange = { newSecurity ->
                security.value = newSecurity
                hy2Edit.security = securityOptions[newSecurity]
            },
        )
        //tls
        AnimatedVisibility(
            visible = security.value == 1,
            enter = fadeIn() + expandVertically(),
            exit = ExitTransition.None,
        ) {
            Column {
                val allowInsecureOptions = remember { listOf("false", "true") }
                val allowInsecure = remember { mutableIntStateOf(hy2Edit.insecure) }

                SmallTitle(text = stringResource(R.string.proxy_editor_tls_settings))
                TextField(
                    label = "SNI",
                    state = rememberTextFieldState(initialText = hy2Edit.sni),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        hy2Edit.sni = asCharSequence().toString()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onKeyboardAction = { focusManager.clearFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.proxy_editor_allow_insecure),
                    items = allowInsecureOptions,
                    selectedIndex = allowInsecure.value,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    onSelectedIndexChange = { newOption ->
                        allowInsecure.value = newOption
                        hy2Edit.insecure = newOption
                    },
                )
                TextField(
                    label = stringResource(R.string.proxy_editor_certificate_fingerprint),
                    state = rememberTextFieldState(initialText = hy2Edit.pinSHA256),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        hy2Edit.pinSHA256 = asCharSequence().toString()
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
