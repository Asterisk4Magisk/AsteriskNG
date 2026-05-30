// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun StringListEditor(
    editorKey: Any?,
    title: String,
    inputLabel: String,
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
    emptyText: String,
    duplicateText: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    validateInput: (String) -> String? = { null },
) {
    var input by remember(editorKey, title) { mutableStateOf("") }
    val inputState = rememberTextFieldState()
    LaunchedEffect(editorKey, title) {
        input = ""
        inputState.clearText()
    }
    val sanitizedValues = values.sanitizeStringListItems()
    val trimmedInput = input.trim()
    val inputError = when {
        trimmedInput.isEmpty() -> null
        trimmedInput in sanitizedValues -> duplicateText
        else -> validateInput(trimmedInput)
    }
    val canAddInput = trimmedInput.isNotEmpty() && inputError == null
    val addInput = {
        if (canAddInput) {
            onValuesChange((sanitizedValues + trimmedInput).sanitizeStringListItems())
            input = ""
            inputState.clearText()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
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
                    label = inputLabel,
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
            sanitizedValues.forEach { value ->
                StringListItem(
                    value = value,
                    onDelete = { onValuesChange(sanitizedValues - value) },
                )
            }
        }
    }
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

internal fun List<String>.sanitizeStringListItems(): List<String> {
    return map(String::trim)
        .filter { it.isNotEmpty() }
        .distinct()
}

@Composable
private fun StringListItem(
    value: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(start = 8.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
        IconButton(
            modifier = Modifier.size(30.dp),
            onClick = onDelete,
        ) {
            Icon(
                modifier = Modifier.size(17.dp),
                imageVector = MiuixIcons.Delete,
                contentDescription = stringResource(R.string.common_delete),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}
