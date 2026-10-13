// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0
package features.enhancement

import app.modes.supportsAppListEnhancement
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class EnhancementModulePhase { Skipped, Checking, Installing, Missing, Current, UpdateAvailable, PendingReboot, Failed }
internal enum class EnhancementModuleFailure { RootDenied, UnsupportedManager, CommandFailed, Timeout, InvalidPackage, InvalidModule, IncompatibleRuntime, ApkInstallFailed }
internal data class EnhancementModuleState(
    val phase: EnhancementModulePhase = EnhancementModulePhase.Skipped,
    val failure: EnhancementModuleFailure? = null,
) {
    val canEnable: Boolean get() = phase == EnhancementModulePhase.Current || phase == EnhancementModulePhase.PendingReboot
    val busy: Boolean get() = phase == EnhancementModulePhase.Checking || phase == EnhancementModulePhase.Installing
}
internal data class EnhancementModuleInstallProgress(
    val running: Boolean = true,
    val output: String = "",
    val result: EnhancementModuleState? = null,
)
internal data class EnhancementModuleImage(val versionCode: Long, val version: String, val buildId: String)
internal data class EnhancementModuleRuntime(val image: EnhancementModuleImage, val netd: ULong, val api: Int, val modes: Int, val flags: Int, val protocol: Int)
internal class EnhancementModuleException(val failure: EnhancementModuleFailure) : Exception(failure.name)
internal interface EnhancementModuleBackend {
    suspend fun expectedImage(): EnhancementModuleImage
    suspend fun inspect(): EnhancementModuleRuntime?
    suspend fun install(onOutput: (String) -> Unit = {})
}
internal class EnhancementModuleCoordinator(private val backendFactory: () -> EnhancementModuleBackend) {
    private val mutableState = MutableStateFlow(EnhancementModuleState())
    val state = mutableState.asStateFlow()
    private val mutableInstallProgress = MutableStateFlow<EnhancementModuleInstallProgress?>(null)
    val installProgress = mutableInstallProgress.asStateFlow()

    fun dismissInstallProgress() {
        mutableInstallProgress.update { if (it?.running == true) it else null }
    }

    private fun appendInstallOutput(line: String) {
        mutableInstallProgress.update { progress ->
            if (progress?.running != true) progress
            else progress.copy(output = (progress.output + line.take(4096) + "\n").takeLast(64 * 1024))
        }
    }

    private fun finishInstall(result: EnhancementModuleState) {
        mutableInstallProgress.update { progress ->
            progress?.copy(running = false, result = result)
        }
    }
    private val requestId = AtomicLong()
    private val operationLock = Mutex()
    // UI-only evidence from a successful manager CLI install during this process. Disabling the
    // setting does not undo an installation or its reboot requirement. Never saved.
    private var installedVersionCodeAwaitingReboot: Long? = null

    // Only ephemeral UI evidence; never persisted or used to activate the daemon.
    fun invalidate() {
        synchronized(requestId) {
            requestId.incrementAndGet()
            mutableState.value = EnhancementModuleState()
        }
    }

    suspend fun check(enabled: Boolean, runMode: Int, isStillCurrent: () -> Boolean = { true }): EnhancementModuleState =
        operate(enabled, runMode, install = false, isStillCurrent)

    suspend fun ensureInstalled(runMode: Int, isStillCurrent: () -> Boolean = { true }): EnhancementModuleState =
        operate(true, runMode, install = true, isStillCurrent)

    private suspend fun operate(enabled: Boolean, runMode: Int, install: Boolean, isStillCurrent: () -> Boolean): EnhancementModuleState {
        // This pure guard precedes construction of any Android/ROOT backend.
        if (!enabled || !runMode.supportsAppListEnhancement() || !isStillCurrent()) {
            invalidate()
            return EnhancementModuleState()
        }
        val id = synchronized(requestId) { requestId.incrementAndGet() }
        fun current() = requestId.get() == id && isStillCurrent()
        fun publish(result: EnhancementModuleState): EnhancementModuleState {
            return synchronized(requestId) {
                if (!current()) EnhancementModuleState()
                else result.also { mutableState.value = it }
            }
        }
        return operationLock.withLock {
            if (!current()) return@withLock EnhancementModuleState()
            publish(EnhancementModuleState(EnhancementModulePhase.Checking))
            var installing = false
            try {
                val backend = backendFactory()
                val expected = backend.expectedImage()
                if (!current()) return@withLock EnhancementModuleState()
                if (installedVersionCodeAwaitingReboot == expected.versionCode) {
                    return@withLock publish(EnhancementModuleState(EnhancementModulePhase.PendingReboot))
                }
                var result = backend.inspect().classify(expected)
                if (!current()) return@withLock EnhancementModuleState()
                if (install && (result.phase == EnhancementModulePhase.Missing || result.phase == EnhancementModulePhase.UpdateAvailable)) {
                    publish(EnhancementModuleState(EnhancementModulePhase.Installing))
                    installing = true
                    mutableInstallProgress.value = EnhancementModuleInstallProgress()
                    backend.install(::appendInstallOutput)
                    installedVersionCodeAwaitingReboot = expected.versionCode
                    finishInstall(EnhancementModuleState(EnhancementModulePhase.PendingReboot))
                    if (!current()) return@withLock EnhancementModuleState()
                    result = EnhancementModuleState(EnhancementModulePhase.PendingReboot)
                }
                publish(result)
            } catch (error: CancellationException) {
                if (installing) finishInstall(EnhancementModuleState(EnhancementModulePhase.Failed, EnhancementModuleFailure.CommandFailed))
                if (current()) invalidate()
                throw error
            } catch (error: Exception) {
                val failure = EnhancementModuleState(EnhancementModulePhase.Failed,
                    (error as? EnhancementModuleException)?.failure ?: EnhancementModuleFailure.CommandFailed)
                if (installing) finishInstall(failure)
                publish(failure)
            }
        }
    }
}

private fun EnhancementModuleRuntime?.classify(expected: EnhancementModuleImage): EnhancementModuleState {
    val phase = when {
        this == null -> EnhancementModulePhase.Missing
        image.versionCode < expected.versionCode -> EnhancementModulePhase.UpdateAvailable
        protocol != 7 || modes == 0 || flags and 7 != 6 -> return EnhancementModuleState(EnhancementModulePhase.Failed, EnhancementModuleFailure.IncompatibleRuntime)
        else -> EnhancementModulePhase.Current
    }
    return EnhancementModuleState(phase)
}
