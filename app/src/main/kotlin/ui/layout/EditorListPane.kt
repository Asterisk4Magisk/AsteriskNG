// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class EditorListItem(val id: Int, val title: String)

@Composable
internal fun EditorListPane(
    title: String,
    entries: List<EditorListItem>,
    selectedId: Int?,
    padding: PaddingValues,
    show: Boolean = true,
    animateFocusLayout: Boolean = false,
    onSelect: ((Int) -> Unit)? = null,
    selectionEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val visible = show && entries.isNotEmpty() && LocalAdaptiveWindow.current.showTwoColumns
    val sizeMotion = if (animateFocusLayout) codeEditorFocusSize() else remember { folmeSpring<IntSize>(damping = 0.95f, response = 0.4f) }
    val fadeMotion = remember { folmeSpring<Float>(damping = 0.95f, response = 0.4f) }
    val sidebarFade = if (animateFocusLayout) codeEditorFocusFade() else fadeMotion
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    CodeEditorPaneLayout(sidebarVisible = visible, animateFocusLayout = animateFocusLayout) {
        AnimatedVisibility(
            visible = visible,
            enter = expandHorizontally(sizeMotion, expandFrom = Alignment.Start) + fadeIn(sidebarFade),
            exit = shrinkHorizontally(sizeMotion, shrinkTowards = Alignment.Start) + fadeOut(sidebarFade),
        ) {
            LazyColumn(
                modifier = Modifier
                    .width(268.dp)
                    .padding(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding())
                    .padding(start = 16.dp, end = 8.dp)
                    .background(colors.surfaceContainer, shape)
                    .then(if (onSelect != null) Modifier.selectableGroup() else Modifier),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        text = title,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MiuixTheme.textStyles.title4,
                        color = colors.onSurface,
                    )
                }
                items(entries, key = { it.id }) { item ->
                    val selected = selectedId == item.id
                    Text(
                        text = item.title,
                        modifier = Modifier.fillMaxWidth()
                            .clip(shape)
                            .then(
                                if (selected) Modifier.background(colors.surfaceContainerHigh)
                                else Modifier,
                            )
                            .then(
                                if (onSelect != null) {
                                    Modifier.heightIn(min = 48.dp).selectable(
                                        selected = selected,
                                        enabled = selectionEnabled && visible,
                                        role = Role.Button,
                                        onClick = { onSelect(item.id) },
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = if (selected) colors.primary else colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        EditorEntryTransition(
            selectedId = selectedId,
            animateChanges = visible && onSelect != null,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            content = content,
        )
    }
}

@Composable
internal fun EditorEntryTransition(
    selectedId: Int?,
    animateChanges: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var previousId by remember { mutableStateOf(selectedId) }
    val visibility = remember(selectedId) {
        val animate = animateChanges && selectedId != previousId
        MutableTransitionState(!animate).apply { targetState = true }
    }
    SideEffect { previousId = selectedId }

    val fadeMotion = remember { folmeSpring<Float>(damping = 0.95f, response = 0.4f) }
    AnimatedVisibility(
        visibleState = visibility,
        modifier = modifier,
        enter = fadeIn(fadeMotion),
        exit = ExitTransition.None,
        label = "editorEntryChange",
    ) {
        content()
    }
}
