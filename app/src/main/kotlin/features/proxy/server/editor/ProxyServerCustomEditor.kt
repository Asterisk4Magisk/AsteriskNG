// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.R
import features.proxy.server.model.Custom
import features.proxy.server.model.formatCustomXrayConfigJson
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun CustomProxyServerEditor(
    customEdit: Custom,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val focusManager = LocalFocusManager.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val invalidJsonMessage = stringResource(R.string.proxy_editor_custom_json_invalid)
    val formatJsonContentDescription = stringResource(R.string.proxy_editor_custom_format_json)
    val jsonEditorColors = rememberJsonEditorColors()
    var remarks by remember(customEdit) {
        mutableStateOf(customEdit.remarks)
    }
    val remarksState = rememberTextFieldState(initialText = customEdit.remarks)
    var overrideAsteriskInboundAndDns by remember(customEdit) {
        mutableStateOf(customEdit.overrideAsteriskInboundAndDns)
    }
    val configJsonState = remember(customEdit) {
        JsonCodeEditorState(customEdit.configJson)
    }

    LaunchedEffect(customEdit) {
        remarks = customEdit.remarks
        remarksState.setTextAndPlaceCursorAtEnd(customEdit.remarks)
    }

    LaunchedEffect(configJsonState.documentVersion) {
        customEdit.configJson = configJsonState.snapshotText()
    }

    fun formatCurrentJson() {
        runCatching {
            formatCustomXrayConfigJson(configJsonState.snapshotText())
        }.onSuccess { formatted ->
            configJsonState.replaceText(formatted)
            customEdit.configJson = formatted
            focusManager.clearFocus()
        }.onFailure {
            scope.launch {
                tipNotifier.show(invalidJsonMessage)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = remarksState,
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                remarks = asCharSequence().toString()
                customEdit.remarks = remarks
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        SwitchPreference(
            title = stringResource(R.string.proxy_editor_custom_override_inbound_dns),
            summary = stringResource(R.string.proxy_editor_custom_override_inbound_dns_summary),
            checked = overrideAsteriskInboundAndDns,
            onCheckedChange = { checked ->
                overrideAsteriskInboundAndDns = checked
                customEdit.overrideAsteriskInboundAndDns = checked
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        )

        SmallTitle(text = stringResource(R.string.proxy_editor_custom_json))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .imePadding(),
        ) {
            JsonCodeEditor(
                label = stringResource(R.string.proxy_editor_custom_json),
                state = configJsonState,
                modifier = Modifier.fillMaxSize(),
            )
            JsonFormatButton(
                contentDescription = formatJsonContentDescription,
                onClick = ::formatCurrentJson,
                editorColors = jsonEditorColors,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun JsonFormatButton(
    contentDescription: String,
    onClick: () -> Unit,
    editorColors: JsonEditorColors,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .clip(RoundedCornerShape(JsonEditorFormatButtonCornerRadius))
            .background(editorColors.formatButtonBackground),
    ) {
        Icon(
            imageVector = MiuixIcons.ConvertFile,
            contentDescription = contentDescription,
            tint = editorColors.accent,
        )
    }
}

private val JsonEditorFormatButtonCornerRadius = 12.dp
