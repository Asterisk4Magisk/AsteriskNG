// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal suspend fun collectLogViewerEntries(
    repository: CoreLogRepository,
    paused: Boolean,
    onEntries: (List<CoreLogEntry>) -> Unit,
) {
    repository.refresh()
    onEntries(repository.entries.value)
    if (!paused) {
        // The log is followed only while it is shown live, so nothing reads it otherwise.
        coroutineScope {
            launch { repository.follow() }
            repository.entries.collect(onEntries)
        }
    }
}
