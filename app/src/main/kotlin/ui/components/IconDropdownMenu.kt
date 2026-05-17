package ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

internal data class IconDropdownMenuEntry<T>(
    val key: Any,
    val title: String,
    val action: T,
    val selected: Boolean = false,
)

@Composable
internal fun <T> IconDropdownMenu(
    imageVector: ImageVector,
    contentDescription: String,
    entries: List<IconDropdownMenuEntry<T>>,
    onAction: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPopup = remember { mutableStateOf(false) }
    val holdDown = remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    IconButton(
        modifier = modifier,
        onClick = {
            showPopup.value = true
            holdDown.value = true
        },
        holdDownState = holdDown.value,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
    WindowListPopup(
        show = showPopup.value,
        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = {
            showPopup.value = false
        },
        onDismissFinished = {
            holdDown.value = false
        },
    ) {
        val dismissState = LocalDismissState.current
        ListPopupColumn {
            entries.forEachIndexed { index, entry ->
                key(entry.key) {
                    DropdownImpl(
                        text = entry.title,
                        optionSize = entries.size,
                        isSelected = entry.selected,
                        onSelectedIndexChange = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onAction(entry.action)
                            dismissState?.invoke()
                        },
                        index = index,
                    )
                }
            }
        }
    }
}
