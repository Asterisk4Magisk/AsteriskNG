// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.automation

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.AsteriskApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidBroadcastUpdateGateway(
    private val application: AsteriskApplication,
) : BroadcastUpdateGateway {
    private val workManager = WorkManager.getInstance(application)

    override suspend fun start(kind: BroadcastUpdateKind) = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<BroadcastUpdateWorker>()
            .setInputData(workDataOf(BroadcastUpdateKindKey to kind.name))
            .build()
        // KEEP coalesces repeated broadcasts without touching periodic subscription work.
        workManager.enqueueUniqueWork(workName(kind), ExistingWorkPolicy.KEEP, request).result.get()
        Unit
    }

    override suspend fun cancel(kind: BroadcastUpdateKind) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(kind)).result.get()
        if (kind == BroadcastUpdateKind.Resource) {
            application.resourceFileUpdateCoordinator.cancelAll()
        }
    }

    private fun workName(kind: BroadcastUpdateKind) = "broadcast-update-${kind.name}"
}

internal const val BroadcastUpdateKindKey = "broadcast_update_kind"
