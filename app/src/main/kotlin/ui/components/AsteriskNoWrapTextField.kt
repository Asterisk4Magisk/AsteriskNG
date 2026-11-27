// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Scrolls long lines inside the field while its label and border stay in place. */
@Composable
internal fun AsteriskNoWrapTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String = "",
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    inputTransformation: InputTransformation? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    val colors = TextFieldDefaults.textFieldColors()
    val insideMargin = TextFieldDefaults.InsideMargin
    val cornerRadius = TextFieldDefaults.CornerRadius
    val borderWidth = animateDpAsState(if (isFocused) 2.dp else 0.dp, label = "noWrapTextFieldBorderWidth")
    val borderColor = animateColorAsState(
        if (isFocused) colors.borderColor else colors.backgroundColor,
        label = "noWrapTextFieldBorderColor",
    )
    val contentColor = LocalContentColor.current

    LaunchedEffect(isFocused, state.selection, textLayout, viewportWidth, horizontalScrollState.maxValue) {
        if (!isFocused || viewportWidth <= 0 || horizontalScrollState.maxValue <= 0) return@LaunchedEffect
        val layout = textLayout ?: return@LaunchedEffect
        // A selection can change before its text is measured.
        if (layout.layoutInput.text.text != state.text.toString()) return@LaunchedEffect
        val cursor = layout.getCursorRect(state.selection.end.coerceIn(0, state.text.length))
        val cursorPadding = with(density) { 24.dp.toPx() }
        val current = horizontalScrollState.value
        val next = when {
            cursor.left - cursorPadding < current -> (cursor.left - cursorPadding).toInt()
            cursor.right + cursorPadding > current + viewportWidth ->
                (cursor.right + cursorPadding - viewportWidth + 1f).toInt()
            else -> current
        }.coerceIn(0, horizontalScrollState.maxValue)
        if (next != current) horizontalScrollState.scrollTo(next)
    }

    BasicTextField(
        state = state,
        modifier = modifier,
        textStyle = textStyle.copy(color = textStyle.color.takeOrElse { contentColor }),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxLines),
        inputTransformation = inputTransformation,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        onTextLayout = { getResult -> textLayout = getResult() },
        interactionSource = interactionSource,
        cursorBrush = SolidColor(colors.borderColor),
        decorator = { innerTextField ->
            val floatingLabel = label.isNotEmpty() && state.text.isNotEmpty()
            val background = if (label.isEmpty()) {
                val shape = RoundedCornerShape(cornerRadius)
                Modifier.clip(shape).background(colors.backgroundColor).border(borderWidth.value, borderColor.value, shape)
            } else {
                Modifier
                    .squircleBackground(color = colors.backgroundColor, cornerRadius = cornerRadius)
                    .squircleBorder(width = { borderWidth.value }, color = { borderColor.value }, cornerRadius = cornerRadius)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(background)
                    .padding(horizontal = insideMargin.width, vertical = insideMargin.height),
            ) {
                if (label.isNotEmpty()) {
                    val labelOffset = animateDpAsState(
                        targetValue = if (floatingLabel) -insideMargin.height / 2 else 0.dp,
                        label = "noWrapTextFieldLabelOffset",
                    )
                    val labelSize by animateDpAsState(
                        targetValue = if (floatingLabel) 10.dp else 17.dp,
                        label = "noWrapTextFieldLabelSize",
                    )
                    Text(
                        text = label,
                        modifier = Modifier.offset { IntOffset(0, labelOffset.value.roundToPx()) },
                        color = colors.labelColor,
                        fontSize = labelSize.value.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(y = if (floatingLabel) insideMargin.height / 2 else 0.dp)
                        .fillMaxWidth()
                        .onSizeChanged { viewportWidth = it.width }
                        .horizontalScroll(horizontalScrollState),
                ) {
                    // The scroll viewport gives the text unbounded width and fills the field for short lines.
                    Box(modifier = Modifier.widthIn(min = with(density) { viewportWidth.toDp() })) {
                        innerTextField()
                    }
                }
            }
        },
    )
}
