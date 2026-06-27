// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.LocalAppServices
import app.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import utils.toTrimmedNonEmptyList

private typealias StringListItemValidator = (String) -> String?

@Composable
internal fun StringListEditor(
    editorKey: Any?,
    title: String,
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    validateInput: (String) -> String? = { null },
) {
    var input by remember(editorKey, title) { mutableStateOf("") }
    val inputState = rememberTextFieldState()
    var editingIndex by remember(editorKey, title) { mutableIntStateOf(NoEditingIndex) }
    var editInput by remember(editorKey, title) { mutableStateOf("") }
    val editInputState = rememberTextFieldState()
    var showBulkEditor by remember(editorKey, title) { mutableStateOf(false) }
    var bulkInput by remember(editorKey, title) { mutableStateOf("") }
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    LaunchedEffect(editorKey, title) {
        input = ""
        inputState.clearText()
        editingIndex = NoEditingIndex
        editInput = ""
        editInputState.clearText()
        showBulkEditor = false
        bulkInput = ""
    }
    val sanitizedValues = values.toTrimmedNonEmptyList()
    LaunchedEffect(sanitizedValues.size, editingIndex) {
        if (editingIndex != NoEditingIndex && editingIndex !in sanitizedValues.indices) {
            editingIndex = NoEditingIndex
            editInput = ""
            editInputState.clearText()
        }
    }
    val trimmedInput = input.trim()
    val inputError = when {
        trimmedInput.isEmpty() -> null
        else -> validateInput(trimmedInput)
    }
    val canAddInput = trimmedInput.isNotEmpty() && inputError == null
    val addInput = {
        if (canAddInput) {
            onValuesChange((sanitizedValues + trimmedInput).toTrimmedNonEmptyList())
            input = ""
            inputState.clearText()
        }
    }
    val emptyItemText = stringResource(R.string.string_list_item_empty)
    val editError = if (editingIndex in sanitizedValues.indices) {
        validateStringListItem(
            input = editInput,
            emptyText = emptyItemText,
            validateInput = validateInput,
        )
    } else {
        null
    }
    val canSaveEdit = editingIndex in sanitizedValues.indices && editError == null
    val cancelEdit = {
        editingIndex = NoEditingIndex
        editInput = ""
        editInputState.clearText()
    }
    val saveEdit = {
        if (canSaveEdit) {
            val nextValues = sanitizedValues.toMutableList()
            nextValues[editingIndex] = editInput.trim()
            onValuesChange(nextValues.toTrimmedNonEmptyList())
            cancelEdit()
        }
    }
    val showBulkEdit = {
        val draft = sanitizedValues.joinToString(separator = "\n")
        bulkInput = draft
        showBulkEditor = true
    }
    val bulkParseResult = parseStringListDraft(
        text = bulkInput,
        validateInput = validateInput,
    )
    val bulkErrorText = bulkParseResult.error?.let { error ->
        stringResource(R.string.string_list_line_error, error.lineNumber, error.message)
    }
    val saveBulkEdit: () -> Unit = {
        if (bulkErrorText != null) {
            scope.launch {
                tipNotifier.show(bulkErrorText)
            }
        } else {
            cancelEdit()
            onValuesChange(bulkParseResult.values)
            showBulkEditor = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = StringListTitleFontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    modifier = Modifier.size(StringListBulkEditButtonSize),
                    onClick = showBulkEdit,
                ) {
                    Icon(
                        modifier = Modifier.size(StringListBulkEditIconSize),
                        imageVector = MiuixIcons.Edit,
                        contentDescription = stringResource(R.string.common_edit_all),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            description?.let {
                StringListStatusText(
                    text = it,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    state = inputState,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation {
                        input = asCharSequence().toString()
                    },
                    insideMargin = DpSize(width = 10.dp, height = 8.dp),
                    cornerRadius = 12.dp,
                    textStyle = MiuixTheme.textStyles.main.copy(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(2.dp))
                IconButton(
                    modifier = Modifier.size(36.dp),
                    onClick = addInput,
                    enabled = canAddInput,
                ) {
                    Icon(
                        modifier = Modifier.size(20.dp),
                        imageVector = MiuixIcons.Add,
                        contentDescription = stringResource(R.string.common_add),
                        tint = if (canAddInput) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        },
                    )
                }
            }
            inputError?.let {
                StringListStatusText(text = it, error = true)
            }
            if (sanitizedValues.isEmpty()) {
                StringListStatusText(text = emptyText)
            } else {
                Spacer(Modifier.height(4.dp))
            }
            sanitizedValues.forEachIndexed { index, value ->
                StringListItem(
                    value = value,
                    editing = index == editingIndex,
                    editState = editInputState,
                    editError = editError.takeIf { index == editingIndex },
                    canSaveEdit = canSaveEdit,
                    onEditInputChange = { editInput = it },
                    onStartEdit = {
                        editingIndex = index
                        editInput = value
                        editInputState.setTextAndPlaceCursorAtEnd(value)
                    },
                    onSaveEdit = saveEdit,
                    onCancelEdit = cancelEdit,
                    onDelete = {
                        if (editingIndex != NoEditingIndex) {
                            cancelEdit()
                        }
                        onValuesChange(sanitizedValues.filterIndexed { valueIndex, _ -> valueIndex != index })
                    },
                )
            }
        }
    }

    StringListBulkEditorDialog(
        show = showBulkEditor,
        title = title,
        value = bulkInput,
        onInputChange = { bulkInput = it },
        onDismissRequest = {
            showBulkEditor = false
        },
        onSave = saveBulkEdit,
    )
}

@Composable
internal fun StringListStatusText(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun StringListItem(
    value: String,
    editing: Boolean,
    editState: TextFieldState,
    editError: String?,
    canSaveEdit: Boolean,
    onEditInputChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(start = 8.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
    ) {
        AnimatedContent(
            targetState = editing,
            transitionSpec = {
                (fadeIn() + expandVertically()) togetherWith (fadeOut() + shrinkVertically())
            },
            label = "stringListItemEditState",
        ) { isEditing ->
            if (isEditing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(StringListItemRowHeight)
                        .padding(vertical = StringListItemEditingRowVerticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(StringListItemActionSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        state = editState,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        inputTransformation = InputTransformation {
                            onEditInputChange(asCharSequence().toString())
                        },
                        insideMargin = StringListItemEditFieldInsideMargin,
                        cornerRadius = StringListItemEditFieldCornerRadius,
                        textStyle = MiuixTheme.textStyles.main.copy(fontSize = 14.sp),
                        modifier = Modifier.weight(1f),
                    )
                    StringListItemActionButton(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = stringResource(R.string.common_save),
                        enabled = canSaveEdit,
                        onClick = {
                            if (canSaveEdit) {
                                onSaveEdit()
                            }
                        },
                    )
                    StringListItemActionButton(
                        imageVector = MiuixIcons.Close,
                        contentDescription = stringResource(R.string.common_cancel),
                        onClick = onCancelEdit,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(StringListItemRowHeight)
                        .padding(vertical = StringListItemEditingRowVerticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(StringListItemActionSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = value,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StringListItemActionButton(
                        imageVector = MiuixIcons.Edit,
                        contentDescription = stringResource(R.string.common_edit),
                        onClick = onStartEdit,
                    )
                    StringListItemActionButton(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        onClick = onDelete,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = editing && editError != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            editError?.let {
                StringListStatusText(text = it, error = true)
            }
        }
    }
}

@Composable
private fun StringListItemActionButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        modifier = Modifier.size(StringListItemActionButtonSize),
        onClick = onClick,
        enabled = enabled,
    ) {
        Icon(
            modifier = Modifier.size(StringListItemActionIconSize),
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            },
        )
    }
}

@Composable
private fun StringListBulkEditorDialog(
    show: Boolean,
    title: String,
    value: String,
    onInputChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            StringListBulkTextField(
                value = value,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .padding(bottom = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.common_save),
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StringListBulkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    var textLayoutResult by remember {
        mutableStateOf<TextLayoutResult?>(null)
    }
    var textViewportWidth by remember {
        mutableIntStateOf(0)
    }
    val shape = RoundedCornerShape(StringListBulkTextFieldCornerRadius)
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) StringListBulkTextFieldBorderWidth else 0.dp,
        label = "stringListBulkTextFieldBorderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.secondaryContainer
        },
        label = "stringListBulkTextFieldBorderColor",
    )
    val resolvedTextStyle = MiuixTheme.textStyles.main.copy(
        color = MiuixTheme.colorScheme.onSurface,
        fontSize = 14.sp,
    )

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
        }
    }

    LaunchedEffect(
        fieldValue.selection,
        fieldValue.text,
        textLayoutResult,
        textViewportWidth,
        horizontalScrollState.maxValue,
    ) {
        val layoutResult = textLayoutResult ?: return@LaunchedEffect
        if (textViewportWidth <= 0 || horizontalScrollState.maxValue <= 0) {
            return@LaunchedEffect
        }

        val cursorOffset = fieldValue.selection.end.coerceIn(0, fieldValue.text.length)
        val cursorRect = layoutResult.getCursorRect(cursorOffset)
        val cursorPaddingPx = with(density) { StringListBulkTextFieldCursorScrollPadding.toPx() }
        val nextHorizontalScroll = scrollToVisible(
            current = horizontalScrollState.value,
            viewportSize = textViewportWidth,
            targetStart = cursorRect.left - cursorPaddingPx,
            targetEnd = cursorRect.right + cursorPaddingPx,
            maxValue = horizontalScrollState.maxValue,
        )
        if (nextHorizontalScroll != horizontalScrollState.value) {
            horizontalScrollState.scrollTo(nextHorizontalScroll)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val contentMinWidth = (maxWidth - StringListBulkTextFieldHorizontalPadding * 2)
            .coerceAtLeast(0.dp)

        BasicTextField(
            value = fieldValue,
            onValueChange = { nextValue ->
                fieldValue = nextValue
                onValueChange(nextValue.text)
            },
            textStyle = resolvedTextStyle,
            minLines = StringListBulkTextFieldMinLines,
            maxLines = StringListBulkTextFieldMaxLines,
            onTextLayout = { textLayoutResult = it },
            interactionSource = interactionSource,
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(MiuixTheme.colorScheme.secondaryContainer)
                        .border(borderWidth, borderColor, shape)
                        .padding(
                            horizontal = StringListBulkTextFieldHorizontalPadding,
                            vertical = StringListBulkTextFieldVerticalPadding,
                        ),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                textViewportWidth = size.width
                            }
                            .horizontalScroll(horizontalScrollState),
                    ) {
                        Box(modifier = Modifier.widthIn(min = contentMinWidth)) {
                            innerTextField()
                        }
                    }
                }
            },
        )
    }
}

private fun validateStringListItem(
    input: String,
    emptyText: String,
    validateInput: StringListItemValidator,
): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return emptyText

    return validateInput(trimmed)
}

private fun parseStringListDraft(
    text: String,
    validateInput: StringListItemValidator,
): StringListParseResult {
    val values = mutableListOf<String>()

    text.lineSequence().forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEachIndexed
        validateInput(trimmed)?.let { error ->
            return StringListParseResult(error = StringListLineError(index + 1, error))
        }
        values += trimmed
    }

    return StringListParseResult(values = values)
}

private data class StringListParseResult(
    val values: List<String> = emptyList(),
    val error: StringListLineError? = null,
)

private data class StringListLineError(
    val lineNumber: Int,
    val message: String,
)

private fun scrollToVisible(
    current: Int,
    viewportSize: Int,
    targetStart: Float,
    targetEnd: Float,
    maxValue: Int,
): Int {
    if (viewportSize <= 0 || maxValue <= 0) return current

    val next = when {
        targetStart < current -> targetStart.toInt()
        targetEnd > current + viewportSize -> (targetEnd - viewportSize + 1f).toInt()
        else -> current
    }
    return next.coerceIn(0, maxValue)
}

private val StringListItemActionSpacing = 2.dp
private val StringListItemRowHeight = 34.dp
private val StringListItemActionButtonSize = 30.dp
private val StringListItemActionIconSize = 17.dp
private val StringListItemEditingRowVerticalPadding = 1.dp
private val StringListItemEditFieldInsideMargin = DpSize(width = 8.dp, height = 6.dp)
private val StringListItemEditFieldCornerRadius = 6.dp
private val StringListTitleFontSize = 17.sp
private val StringListBulkEditButtonSize = 38.dp
private val StringListBulkEditIconSize = 20.dp
private val StringListBulkTextFieldCornerRadius = 16.dp
private val StringListBulkTextFieldBorderWidth = 2.dp
private val StringListBulkTextFieldHorizontalPadding = 16.dp
private val StringListBulkTextFieldVerticalPadding = 16.dp
private val StringListBulkTextFieldCursorScrollPadding = 24.dp
private const val StringListBulkTextFieldMinLines = 8
private const val StringListBulkTextFieldMaxLines = 20
private const val NoEditingIndex = -1
