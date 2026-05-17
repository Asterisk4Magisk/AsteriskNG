package features.proxy.server.editor

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.R
import features.proxy.server.model.Wireguard
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField


internal fun LazyListScope.wireguardProxyServer(wgEdit: Wireguard) {
    item(key = "properties") {
        val focusManager = LocalFocusManager.current
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = wgEdit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                wgEdit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_server),
            state = rememberTextFieldState(initialText = wgEdit.server),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                wgEdit.server = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_port),
            state = rememberTextFieldState(initialText = wgEdit.port),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                wgEdit.port = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = "SecretKey",
            state = rememberTextFieldState(initialText = wgEdit.secretKey),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                wgEdit.secretKey = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = "PublicKey",
            state = rememberTextFieldState(initialText = wgEdit.publicKey),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                wgEdit.publicKey = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_preshared_key_optional),
            state = rememberTextFieldState(initialText = wgEdit.preSharedKey),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                wgEdit.preSharedKey = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_reserved_optional),
            state = rememberTextFieldState(initialText = wgEdit.reserved),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                wgEdit.reserved = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_local_address_optional),
            state = rememberTextFieldState(initialText = wgEdit.address),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                wgEdit.address = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_mtu_optional),
            state = rememberTextFieldState(initialText = wgEdit.mtu),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                wgEdit.mtu = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}
