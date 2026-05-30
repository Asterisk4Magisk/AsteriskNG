// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.LocalAppServices
import app.R
import features.proxy.server.model.Custom
import features.proxy.server.model.formatCustomXrayConfigJson
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
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
    val jsonHighlighting = rememberJsonSyntaxHighlightTransformation(jsonEditorColors)
    var remarks by remember(customEdit) {
        mutableStateOf(customEdit.remarks)
    }
    var overrideAsteriskInboundAndDns by remember(customEdit) {
        mutableStateOf(customEdit.overrideAsteriskInboundAndDns)
    }
    var configJsonValue by remember(customEdit) {
        mutableStateOf(
            TextFieldValue(
                text = customEdit.configJson,
                selection = TextRange(customEdit.configJson.length),
            ),
        )
    }

    fun formatCurrentJson() {
        runCatching {
            formatCustomXrayConfigJson(configJsonValue.text)
        }.onSuccess { formatted ->
            configJsonValue = TextFieldValue(
                text = formatted,
                selection = TextRange(formatted.length),
            )
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
            value = remarks,
            onValueChange = { value ->
                remarks = value
                customEdit.remarks = value
            },
            singleLine = true,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() },
            ),
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
            JsonConfigTextField(
                label = stringResource(R.string.proxy_editor_custom_json),
                value = configJsonValue,
                onValueChange = { value ->
                    configJsonValue = value
                    customEdit.configJson = value.text
                },
                visualTransformation = jsonHighlighting,
                textStyle = TextStyle(
                    color = jsonEditorColors.foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                modifier = Modifier.fillMaxSize(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                cursorBrush = SolidColor(jsonEditorColors.accent),
                editorColors = jsonEditorColors,
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
private fun JsonConfigTextField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    visualTransformation: VisualTransformation,
    textStyle: TextStyle,
    keyboardOptions: KeyboardOptions,
    cursorBrush: SolidColor,
    editorColors: JsonEditorColors,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current
    var textLayoutResult by remember {
        mutableStateOf<TextLayoutResult?>(null)
    }
    var editorViewportHeight by remember {
        mutableIntStateOf(0)
    }
    var textViewportWidth by remember {
        mutableIntStateOf(0)
    }
    val lineCount = remember(value.text) {
        value.text.count { char -> char == '\n' } + 1
    }
    val lineNumbers = remember(lineCount) {
        (1..lineCount).joinToString(separator = "\n")
    }
    val gutterWidth = ((lineCount.toString().length.coerceAtLeast(JsonEditorMinLineNumberDigits) * 8) + 10).dp
    val shape = RoundedCornerShape(TextFieldDefaults.CornerRadius)
    val borderWidth by animateDpAsState(if (isFocused) JsonEditorBorderWidth else 0.dp)
    val borderColor by animateColorAsState(
        if (isFocused) editorColors.accent else editorColors.border,
    )
    val verticalScrollMaxValue = scrollState.maxValue
    val horizontalScrollMaxValue = horizontalScrollState.maxValue

    LaunchedEffect(
        value.selection,
        value.text,
        textLayoutResult,
        editorViewportHeight,
        textViewportWidth,
        verticalScrollMaxValue,
        horizontalScrollMaxValue,
    ) {
        val layoutResult = textLayoutResult ?: return@LaunchedEffect
        if (editorViewportHeight <= 0 || textViewportWidth <= 0) {
            return@LaunchedEffect
        }

        val cursorOffset = value.selection.end.coerceIn(0, value.text.length)
        val cursorRect = layoutResult.getCursorRect(cursorOffset)
        val verticalPaddingPx = with(density) { JsonEditorVerticalPadding.toPx() }
        val cursorPaddingPx = with(density) { JsonEditorCursorScrollPadding.toPx() }
        val nextVerticalScroll = scrollToVisible(
            current = scrollState.value,
            viewportSize = editorViewportHeight,
            targetStart = cursorRect.top + verticalPaddingPx - cursorPaddingPx,
            targetEnd = cursorRect.bottom + verticalPaddingPx + cursorPaddingPx,
            maxValue = verticalScrollMaxValue,
        )
        if (nextVerticalScroll != scrollState.value) {
            scrollState.scrollTo(nextVerticalScroll)
        }

        val nextHorizontalScroll = scrollToVisible(
            current = horizontalScrollState.value,
            viewportSize = textViewportWidth,
            targetStart = cursorRect.left - cursorPaddingPx,
            targetEnd = cursorRect.right + cursorPaddingPx,
            maxValue = horizontalScrollMaxValue,
        )
        if (nextHorizontalScroll != horizontalScrollState.value) {
            horizontalScrollState.scrollTo(nextHorizontalScroll)
        }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        singleLine = false,
        maxLines = Int.MAX_VALUE,
        minLines = 1,
        visualTransformation = visualTransformation,
        onTextLayout = { result ->
            textLayoutResult = result
        },
        interactionSource = interactionSource,
        cursorBrush = cursorBrush,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(editorColors.background)
                    .border(borderWidth, borderColor, shape)
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            editorViewportHeight = size.height
                        },
                ) {
                    val contentMinHeight = (maxHeight - JsonEditorVerticalPadding * 2f)
                        .coerceAtLeast(0.dp)

                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .width(gutterWidth)
                                .fillMaxHeight()
                                .background(editorColors.gutter),
                        )
                        Spacer(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(editorColors.separator),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .heightIn(min = contentMinHeight)
                            .padding(vertical = JsonEditorVerticalPadding),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(gutterWidth)
                                .heightIn(min = contentMinHeight)
                                .padding(
                                    start = 2.dp,
                                    end = 4.dp,
                                ),
                        ) {
                            BasicText(
                                text = lineNumbers,
                                style = textStyle.copy(
                                    color = editorColors.lineNumber,
                                    textAlign = TextAlign.End,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(
                            modifier = Modifier
                                .width(1.dp)
                                .heightIn(min = contentMinHeight),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = contentMinHeight)
                                .padding(
                                    start = 0.dp,
                                    end = JsonEditorHorizontalPadding,
                                )
                                .onSizeChanged { size ->
                                    textViewportWidth = size.width
                                }
                                .horizontalScroll(horizontalScrollState),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            if (value.text.isEmpty()) {
                                BasicText(
                                    text = label,
                                    style = textStyle.copy(color = editorColors.placeholder),
                                )
                            }
                            innerTextField()
                        }
                    }
                }
            }
        },
    )
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

private val JsonEditorBorderWidth = 2.dp
private val JsonEditorHorizontalPadding = 12.dp
private val JsonEditorCursorScrollPadding = 24.dp
private val JsonEditorVerticalPadding = 10.dp
private val JsonEditorFormatButtonCornerRadius = 12.dp
private const val JsonEditorMinLineNumberDigits = 2

private fun scrollToVisible(
    current: Int,
    viewportSize: Int,
    targetStart: Float,
    targetEnd: Float,
    maxValue: Int,
): Int {
    if (viewportSize <= 0 || maxValue <= 0) {
        return current
    }

    val next = when {
        targetStart < current -> targetStart.toInt()
        targetEnd > current + viewportSize -> (targetEnd - viewportSize + 1f).toInt()
        else -> current
    }
    return next.coerceIn(0, maxValue)
}
