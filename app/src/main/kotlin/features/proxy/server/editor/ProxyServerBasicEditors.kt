package features.proxy.server.editor

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
import features.proxy.server.model.HTTP
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference


internal fun LazyListScope.httpProxyServer(httpEdit: HTTP) {
    item(key = "properties") {
        val focusManager = LocalFocusManager.current
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = httpEdit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                httpEdit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_server),
            state = rememberTextFieldState(initialText = httpEdit.server),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                httpEdit.server = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_port),
            state = rememberTextFieldState(initialText = httpEdit.port),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                httpEdit.port = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_username_optional),
            state = rememberTextFieldState(initialText = httpEdit.user ?: ""),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                httpEdit.user = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_password_optional),
            state = rememberTextFieldState(initialText = httpEdit.password ?: ""),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                httpEdit.password = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}

internal fun LazyListScope.socksProxyServer(socksEdit: Socks) {
    item(key = "properties") {
        val focusManager = LocalFocusManager.current
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = socksEdit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                socksEdit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_server),
            state = rememberTextFieldState(initialText = socksEdit.server),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                socksEdit.server = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_port),
            state = rememberTextFieldState(initialText = socksEdit.port),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                socksEdit.port = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_username_optional),
            state = rememberTextFieldState(initialText = socksEdit.user ?: ""),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                socksEdit.user = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_password_optional),
            state = rememberTextFieldState(initialText = socksEdit.password ?: ""),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                socksEdit.password = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}

internal fun LazyListScope.shadowsocksProxyServer(ssEdit: Shadowsocks) {
    item(key = "properties") {
        val focusManager = LocalFocusManager.current
        val methodOptions = remember {
            listOf(
                "aes-256-gcm",
                "aes-128-gcm",
                "chacha20-poly1305",
                "chacha20-ietf-poly1305",
                "xchacha20-poly1305",
                "xchacha20-ietf-poly1305",
                "none",
                "plain",
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
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = ssEdit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                ssEdit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_server),
            state = rememberTextFieldState(initialText = ssEdit.server),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                ssEdit.server = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_port),
            state = rememberTextFieldState(initialText = ssEdit.port),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                ssEdit.port = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        TextField(
            label = stringResource(R.string.proxy_editor_password),
            state = rememberTextFieldState(initialText = ssEdit.password),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                ssEdit.password = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_method),
            items = methodOptions,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            selectedIndex = method.value,
            onSelectedIndexChange = { newMethod ->
                method.value = newMethod
                ssEdit.method = methodOptions[newMethod]
            },
        )
    }
}
