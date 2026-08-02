// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.CustomResourceFileState
import app.ResourceFileKind
import app.ResourceFileUpdateSource
import app.ResourceFilesStatus
import features.resources.runtime.AndroidResourceFileDownloadCancelledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val UpdateableResourceFileKinds = setOf(
    ResourceFileKind.GeoIp,
    ResourceFileKind.GeoSite,
    ResourceFileKind.GeoIpOnlyCnPrivate,
    ResourceFileKind.DirectCidrIpv4,
    ResourceFileKind.DirectCidrIpv6,
)

internal sealed interface ResourceFileUpdateTarget {
    data class BuiltIn(val kind: ResourceFileKind) : ResourceFileUpdateTarget

    data class Custom(val id: Int) : ResourceFileUpdateTarget
}

internal sealed interface ResourceFileUpdateRequest {
    val targets: Set<ResourceFileUpdateTarget>

    class BuiltIn(
        val kind: ResourceFileKind,
        val source: ResourceFileUpdateSource,
        val options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState>,
    ) : ResourceFileUpdateRequest {
        val customResourceFiles = customResourceFiles.toList()
        override val targets = setOf(ResourceFileUpdateTarget.BuiltIn(kind))
    }

    class Custom(
        val file: CustomResourceFileState,
        val options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState>,
    ) : ResourceFileUpdateRequest {
        val customResourceFiles = customResourceFiles.toList()
        override val targets = setOf(ResourceFileUpdateTarget.Custom(file.id))
    }

    class All(
        val source: ResourceFileUpdateSource,
        val options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState>,
    ) : ResourceFileUpdateRequest {
        val customResourceFiles = customResourceFiles.toList()
        override val targets: Set<ResourceFileUpdateTarget> = buildSet {
            UpdateableResourceFileKinds.forEach { kind ->
                add(ResourceFileUpdateTarget.BuiltIn(kind))
            }
            this@All.customResourceFiles.forEach { customFile ->
                if (customFile.url.isNotBlank()) {
                    add(ResourceFileUpdateTarget.Custom(customFile.id))
                }
            }
        }
    }
}

internal data class ResourceFileUpdateQueueEntry(
    val id: Long,
    val request: ResourceFileUpdateRequest,
)

internal enum class ResourceFileUpdateDisplayState {
    Idle,
    Queued,
    Running,
}

internal data class ResourceFileUpdateQueueState(
    val running: ResourceFileUpdateQueueEntry? = null,
    val pending: List<ResourceFileUpdateQueueEntry> = emptyList(),
    val completionRevision: Long = 0,
)

internal val ResourceFileUpdateQueueState.isBusy: Boolean
    get() = running != null || pending.isNotEmpty()

internal fun ResourceFileUpdateQueueState.displayStateOf(
    target: ResourceFileUpdateTarget,
): ResourceFileUpdateDisplayState {
    return when {
        target in running?.request?.targets.orEmpty() -> ResourceFileUpdateDisplayState.Running
        pending.any { entry -> target in entry.request.targets } -> ResourceFileUpdateDisplayState.Queued
        else -> ResourceFileUpdateDisplayState.Idle
    }
}

internal sealed interface ResourceFileUpdateResult {
    val entry: ResourceFileUpdateQueueEntry
    val request: ResourceFileUpdateRequest
        get() = entry.request

    data class Success(
        override val entry: ResourceFileUpdateQueueEntry,
    ) : ResourceFileUpdateResult

    data class Failure(
        override val entry: ResourceFileUpdateQueueEntry,
        val error: Throwable,
    ) : ResourceFileUpdateResult

    data class Cancelled(
        override val entry: ResourceFileUpdateQueueEntry,
    ) : ResourceFileUpdateResult
}

internal class ResourceFileUpdateCoordinator(
    private val scope: CoroutineScope,
    private val execute: suspend (ResourceFileUpdateRequest) -> ResourceFilesStatus,
    private val cancelRunning: () -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayDeque<ResourceFileUpdateQueueEntry>()
    private var running: ResourceFileUpdateQueueEntry? = null
    private var completionRevision = 0L
    private var nextEntryId = 1L
    private var worker: Job? = null

    private val mutableState = MutableStateFlow(ResourceFileUpdateQueueState())
    val state: StateFlow<ResourceFileUpdateQueueState> = mutableState.asStateFlow()

    private val mutableResults = MutableSharedFlow<ResourceFileUpdateResult>(extraBufferCapacity = 16)
    val results: SharedFlow<ResourceFileUpdateResult> = mutableResults.asSharedFlow()

    fun enqueue(request: ResourceFileUpdateRequest): Boolean = synchronized(lock) {
        if (request.targets.isEmpty()) return false
        if (request is ResourceFileUpdateRequest.All && mutableState.value.isBusy) return false
        val busyTargets = buildSet {
            running?.request?.targets?.let(::addAll)
            pending.forEach { entry -> addAll(entry.request.targets) }
        }
        if (request.targets.any(busyTargets::contains)) return false

        pending.addLast(
            ResourceFileUpdateQueueEntry(
                id = nextEntryId++,
                request = request,
            ),
        )
        publishStateLocked()
        if (worker?.isActive != true) {
            worker = scope.launch { drainQueue() }
        }
        true
    }

    fun cancelAll() {
        val hasRunningRequest = synchronized(lock) {
            pending.clear()
            publishStateLocked()
            running != null
        }
        if (hasRunningRequest) {
            cancelRunning()
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val entry = synchronized(lock) {
                val next = pending.removeFirstOrNull()
                if (next == null) {
                    worker = null
                    publishStateLocked()
                    null
                } else {
                    running = next
                    publishStateLocked()
                    next
                }
            } ?: return

            val result = try {
                execute(entry.request)
                ResourceFileUpdateResult.Success(entry)
            } catch (error: CancellationException) {
                clearAfterScopeCancellation()
                throw error
            } catch (_: AndroidResourceFileDownloadCancelledException) {
                ResourceFileUpdateResult.Cancelled(entry)
            } catch (error: Throwable) {
                ResourceFileUpdateResult.Failure(entry, error)
            }

            synchronized(lock) {
                running = null
                completionRevision += 1
                publishStateLocked()
            }
            mutableResults.emit(result)
        }
    }

    private fun clearAfterScopeCancellation() = synchronized(lock) {
        running = null
        pending.clear()
        worker = null
        publishStateLocked()
    }

    private fun publishStateLocked() {
        mutableState.value = ResourceFileUpdateQueueState(
            running = running,
            pending = pending.toList(),
            completionRevision = completionRevision,
        )
    }
}
