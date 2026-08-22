// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

internal suspend fun collectLogViewerEntries(
    repository: CoreLogRepository,
    paused: Boolean,
    onEntries: (List<CoreLogEntry>) -> Unit,
) {
    repository.refresh()
    onEntries(repository.entries.value)
    if (!paused) {
        repository.entries.collect(onEntries)
    }
}
