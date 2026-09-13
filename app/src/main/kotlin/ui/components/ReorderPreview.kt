// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ReorderPreview<T>(
    val items: List<T>,
    val onDragStarted: () -> Unit,
    val onMove: (Int, Int) -> Unit,
    val onDragStopped: () -> Unit,
)

@Composable
internal fun <T, K> rememberReorderPreview(
    items: List<T>,
    key: (T) -> K,
    enabled: Boolean = true,
    listKey: Any? = Unit,
    commitScope: CoroutineScope? = null,
    onCommit: suspend (List<K>) -> Boolean,
): ReorderPreview<T> {
    var session by remember(listKey) { mutableStateOf(ReorderSession<K>()) }
    val localScope = rememberCoroutineScope()
    val scope = commitScope ?: localScope
    val commitMutex = remember { Mutex() }
    val liveIds = remember(items) { items.map(key) }
    val currentIds by rememberUpdatedState(liveIds)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCommit by rememberUpdatedState(onCommit)
    LaunchedEffect(liveIds, enabled, session.pending, session.succeeded, session.dragging) {
        session = session.reconcile(if (enabled) liveIds else emptyList())
    }
    val displayedItems = remember(items, session.order, enabled) {
        if (enabled) items.reorderByIds(session.displayedIds(liveIds), key) else items
    }
    return ReorderPreview(
        items = displayedItems,
        onDragStarted = {
            if (currentEnabled) session = session.start(currentIds)
        },
        onMove = { from, to ->
            if (currentEnabled) session = session.move(currentIds, from, to)
        },
        onDragStopped = {
            val (released, request) = session.stop(if (currentEnabled) currentIds else emptyList())
            session = released
            if (request != null) {
                // Capture this release's target; later recompositions may show a different group.
                val commit = currentCommit
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var success = false
                    try {
                        success = commitMutex.withLock { commit(request.ids) }
                    } finally {
                        session = session.complete(request, success).reconcile(currentIds)
                    }
                }
            }
        },
    )
}
