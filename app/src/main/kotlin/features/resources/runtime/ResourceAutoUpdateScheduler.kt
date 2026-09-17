// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal class ResourceAutoUpdateScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun reconcile(intervalMillis: Long?) {
        if (intervalMillis == null) {
            workManager.cancelUniqueWork(WorkName)
            return
        }
        val request = PeriodicWorkRequest.Builder(
            ResourceAutoUpdateWorker::class.java, intervalMillis, TimeUnit.MILLISECONDS,
        )
            .setInitialDelay(intervalMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(WorkName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object { const val WorkName = "resource-auto-update" }
}
