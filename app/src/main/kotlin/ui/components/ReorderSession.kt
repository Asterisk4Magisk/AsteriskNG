// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

internal data class ReorderCommit<K>(val generation: Long, val ids: List<K>)

/** Drag-local order, retained until both the commit and collected state acknowledge it. */
internal data class ReorderSession<K>(
    val order: List<K>? = null,
    val dragStartOrder: List<K>? = null,
    val dragging: Boolean = false,
    val invalidated: Boolean = false,
    val pending: ReorderCommit<K>? = null,
    val succeeded: Boolean = false,
    val generation: Long = 0,
) {
    fun displayedIds(liveIds: List<K>): List<K> =
        order?.takeIf { sameMembers(it, liveIds) } ?: liveIds

    fun start(liveIds: List<K>): ReorderSession<K> = reconcile(liveIds).let {
        val initialOrder = it.displayedIds(liveIds)
        it.copy(order = initialOrder, dragStartOrder = initialOrder, dragging = true, invalidated = false)
    }

    fun move(liveIds: List<K>, from: Int, to: Int): ReorderSession<K> {
        val current = reconcile(liveIds)
        val ids = current.order ?: return current
        if (!current.dragging || current.invalidated || from == to ||
            from !in ids.indices || to !in ids.indices
        ) return current
        return current.copy(order = ids.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun stop(liveIds: List<K>): Pair<ReorderSession<K>, ReorderCommit<K>?> {
        val current = reconcile(liveIds)
        if (!current.dragging) return current to null
        val released = current.copy(dragging = false, dragStartOrder = null)
        val ids = released.order
        if (released.invalidated || ids == null) return released to null
        if (ids == released.pending?.ids) return released.reconcile(liveIds) to null
        if (released.pending == null && (ids == liveIds || ids == current.dragStartOrder)) {
            return released.copy(order = null) to null
        }
        val commit = ReorderCommit(generation + 1, ids)
        return released.copy(pending = commit, succeeded = false, generation = commit.generation) to commit
    }

    fun complete(commit: ReorderCommit<K>, success: Boolean): ReorderSession<K> {
        if (pending != commit) return this
        return if (success) copy(succeeded = true) else copy(
            order = if (dragging) order else null,
            pending = null,
            succeeded = false,
        )
    }

    fun reconcile(liveIds: List<K>): ReorderSession<K> {
        val ids = order ?: return this
        if (!sameMembers(ids, liveIds)) {
            return copy(order = null, pending = null, succeeded = false, invalidated = dragging)
        }
        if (!dragging && succeeded && ids == liveIds) {
            return copy(order = null, pending = null, succeeded = false)
        }
        return this
    }
}

private fun <K> sameMembers(first: List<K>, second: List<K>): Boolean =
    first.size == second.size && first.toSet() == second.toSet()

/** Reorder current objects by identity without overwriting edits or unrelated group slots. */
internal fun <T, K> List<T>.reorderByIds(
    ids: List<K>,
    key: (T) -> K,
    allowSubset: Boolean = false,
): List<T> {
    val requested = ids.toSet()
    if (requested.size != ids.size || (!allowSubset && size != ids.size)) return this
    val byId = associateBy(key)
    if (byId.size != size || !byId.keys.containsAll(requested)) return this
    val replacements = ids.iterator()
    val reordered = map { item ->
        if (key(item) in requested) byId.getValue(replacements.next()) else item
    }
    return if (reordered == this) this else reordered
}
