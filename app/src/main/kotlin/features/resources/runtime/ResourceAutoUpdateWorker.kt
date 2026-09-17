// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.AsteriskApplication
import app.ResourceFileKind
import app.resourceFileUpdateSource
import features.logs.AndroidAppLogger
import features.resources.ResourceAutoUpdateOutcome
import features.resources.ResourceAutoUpdateRunner
import features.resources.ResourceFileUpdateRequest
import features.resources.ResourceFileUpdateResult
import features.resources.ResourceFileUpdateTarget
import features.resources.isTransientResourceUpdateFailure
import features.resources.resourceAutoUpdateIntervalMillis
import features.resources.resourceFileUpdateOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds

internal class ResourceAutoUpdateWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val application = applicationContext as AsteriskApplication
        val progress = ResourceAutoUpdateProgress(applicationContext, id.toString())
        fun enabled(): Boolean = application.stateStore.state.value.let { state ->
            resourceAutoUpdateIntervalMillis(state.enableResourceAutoUpdate, state.resourceAutoUpdateInterval) != null
        }
        if (!enabled()) {
            progress.clear()
            return@withContext Result.success()
        }
        val state = application.stateStore.state.value
        // Core urlFor() is null; reserved names are excluded from custom downloads too.
        val all = ResourceFileUpdateRequest.All(
            state.resourceFileUpdateSource(), state.resourceFileUpdateOptions(),
            state.customResourceFiles.filter { file ->
                ResourceFileKind.entries.none { it.fileName.equals(file.name, ignoreCase = true) }
            },
        )
        val requests = all.targets.associate { target ->
            val request = when (target) {
                is ResourceFileUpdateTarget.BuiltIn -> ResourceFileUpdateRequest.BuiltIn(
                    target.kind, all.source, all.options, all.customResourceFiles,
                )
                is ResourceFileUpdateTarget.Custom -> ResourceFileUpdateRequest.Custom(
                    all.customResourceFiles.first { it.id == target.id }, all.options, all.customResourceFiles,
                )
            }
            // A changed URL/name must be fetched even when the previous target completed before a retry.
            val identity = when (request) {
                is ResourceFileUpdateRequest.BuiltIn -> "$target|${all.source}"
                is ResourceFileUpdateRequest.Custom -> "$target|${request.file}"
                else -> error("Unexpected automatic resource request")
            }
            identity.sha256() to request
        }
        val runner = ResourceAutoUpdateRunner(
            completed = progress::completed,
            record = progress::record,
            shouldContinue = ::enabled,
            attempts = progress::attempts,
            recordAttempt = progress::recordAttempt,
            update = { key ->
                val request = requests.getValue(key)
                // Recheck settings after waiting behind an interactive update.
                val result = withTimeoutOrNull((2 * 60 * 1000L).milliseconds) {
                    application.resourceFileUpdateCoordinator.enqueueAndAwait(request) {
                        val current = application.stateStore.state.value
                        enabled() && when (request) {
                            is ResourceFileUpdateRequest.BuiltIn -> current.resourceFileUpdateSource() == all.source
                            is ResourceFileUpdateRequest.Custom -> request.file in current.customResourceFiles
                            else -> false
                        }
                    }
                }
                when (result) {
                    is ResourceFileUpdateResult.Success -> ResourceAutoUpdateOutcome.Success
                    is ResourceFileUpdateResult.Cancelled -> ResourceAutoUpdateOutcome.Cancelled
                    is ResourceFileUpdateResult.Failure -> {
                        // Do not log URLs, credentials, or exception messages from remote servers.
                        AndroidAppLogger.warn(LogTag, "Resource update failed: ${result.error.javaClass.simpleName}")
                        if (isTransientResourceUpdateFailure(result.error)) ResourceAutoUpdateOutcome.Retry
                        else ResourceAutoUpdateOutcome.Failed
                    }
                    null -> if (enabled()) ResourceAutoUpdateOutcome.Retry else ResourceAutoUpdateOutcome.Cancelled
                }
            },
        )
        try {
            // Ordinary Workers have a time limit. Checkpoints let a later attempt continue the batch.
            val outcome = withTimeoutOrNull((8 * 60 * 1000L).milliseconds) { runner.run(requests.keys.toList()) }
                ?: ResourceAutoUpdateOutcome.Retry
            if (outcome == ResourceAutoUpdateOutcome.Retry) {
                Result.retry()
            } else {
                progress.clear()
                AndroidAppLogger.info(LogTag, "Resource auto update completed: $outcome")
                Result.success() // Permanent errors wait for the next scheduled cycle.
            }
        } catch (error: CancellationException) {
            throw error // System stops retain checkpoints; disabling cancels only this WorkManager job.
        }
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray()).joinToString("") { "%02x".format(it) }

private class ResourceAutoUpdateProgress(context: Context, private val workId: String) {
    private val preferences = context.getSharedPreferences("resource_auto_update_progress", Context.MODE_PRIVATE)

    init {
        synchronized(Lock) {
            if (preferences.getString("work_id", null) != workId) reset()
        }
    }

    fun completed(key: String): Boolean = synchronized(Lock) {
        preferences.getString("work_id", null) == workId &&
            key in preferences.getStringSet("completed", emptySet()).orEmpty()
    }

    fun attempts(key: String): Int = synchronized(Lock) {
        preferences.getInt("attempts.$key", 0)
    }

    @SuppressLint("UseKtx")
    fun recordAttempt(key: String) = synchronized(Lock) {
        if (preferences.getString("work_id", null) != workId) throw CancellationException("Replaced resource work")
        check(preferences.edit().putInt("attempts.$key", attempts(key) + 1).commit())
    }

    @SuppressLint("UseKtx")
    fun record(key: String) = synchronized(Lock) {
        if (preferences.getString("work_id", null) != workId) throw CancellationException("Replaced resource work")
        val completed = preferences.getStringSet("completed", emptySet()).orEmpty()
        check(preferences.edit().putStringSet("completed", completed + key).commit())
    }

    fun clear() = synchronized(Lock) {
        if (preferences.getString("work_id", null) == workId) reset()
    }

    @SuppressLint("UseKtx")
    private fun reset() {
        check(preferences.edit().clear().putString("work_id", workId).commit())
    }

    private companion object { val Lock = Any() }
}

private const val LogTag = "ResourceAutoUpdate"
