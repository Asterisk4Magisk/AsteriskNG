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
import features.proxy.server.model.Trojan
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference


internal fun LazyListScope.vmessProxyServer(vmessEdit: VMess) {
    item(key = "properties") {
        val focusManager = LocalFocusManager.current
        val encryptionOptions = remember {
            listOf(
                "auto",
                "aes-128-gcm",
                "chacha20-poly1305",
                "none",
                "zero",
            )
        }
        val encryption = remember {
            mutableIntStateOf(
                if (encryptionOptions.indexOf(vmessEdit.encryption) > -1)
                    encryptionOptions.indexOf(vmessEdit.encryption) else 0
            )
        }
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = vmessEdit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                vmessEdit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_server),
            state = rememberTextFieldState(initialText = vmessEdit.server),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                vmessEdit.server = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_port),
            state = rememberTextFieldState(initialText = vmessEdit.port),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                vmessEdit.port = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_user_id),
            state = rememberTextFieldState(initialText = vmessEdit.id),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                vmessEdit.id = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
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
        val focusManager = LocalFocusManager.current
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = trojanEdit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                trojanEdit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_server),
            state = rememberTextFieldState(initialText = trojanEdit.server),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                trojanEdit.server = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_port),
            state = rememberTextFieldState(initialText = trojanEdit.port),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                trojanEdit.port = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_password),
            state = rememberTextFieldState(initialText = trojanEdit.password),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                trojanEdit.password = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
    v2rayServerTransport(trojanEdit.parms)
}

internal fun LazyListScope.vlessProxyServer(vlessEdit: VLESS) {
    item(key = "properties") {
        val focusManager = LocalFocusManager.current
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
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = vlessEdit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                vlessEdit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_server),
            state = rememberTextFieldState(initialText = vlessEdit.server),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                vlessEdit.server = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_port),
            state = rememberTextFieldState(initialText = vlessEdit.port),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                if (!asCharSequence().isDigitsOnly()) {
                    revertAllChanges()
                    return@InputTransformation
                }
                vlessEdit.port = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_user_id),
            state = rememberTextFieldState(initialText = vlessEdit.id),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                vlessEdit.id = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextField(
            label = stringResource(R.string.proxy_editor_encryption),
            state = rememberTextFieldState(initialText = vlessEdit.encryption.ifBlank { "none" }),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                vlessEdit.encryption = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
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
