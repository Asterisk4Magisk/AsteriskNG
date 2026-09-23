// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import android.content.Context
import app.modes.RunModeVpnService
import data.AndroidAppStateStore
import engine.root.publication.RootRuntimeLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import system.RootShellGateway
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/** Started by ROOT operations, never by constructing an engine or rendering the dialog. */
internal object RootFailureWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()
    private var job: Job? = null
    private var deadline: Job? = null
    private val delivery = RootFailureDelivery()
    private var watchingAllowed = true
    private var launchInProgress = false

    suspend fun ensureStarted(
        context: Context,
        shell: RootShellGateway,
        layout: RootRuntimeLayout,
        explicitRootAction: Boolean = false,
        running: Boolean = true,
    ) {
        lifecycle.withLock {
            val store = AndroidAppStateStore.get(context.applicationContext)
            if (store.state.value.runMode == RunModeVpnService) return
            // Status streams may still contain Running from the previous service cycle.
            if (launchInProgress && running && !explicitRootAction) return
            if (explicitRootAction) launchInProgress = !running
            if (job?.isActive == true) {
                if (running) startDeadline()
                return
            }
            if (explicitRootAction) watchingAllowed = true
            if (!watchingAllowed) return
            // Consume this attempt before launching. Status queries cannot rearm an expired watcher.
            watchingAllowed = false
            deadline?.cancelAndJoin()
            deadline = null
            // Capture before scheduling: a failure arriving immediately after launch is fresh.
            val baseline = File(layout.asteriskdStatePath).lastModified()
            // Attaching to an externally restarted resident service is also a new episode.
            delivery.beginAttempt()
            job = scope.launch {
                watch(context.applicationContext, shell, layout, baseline) {
                    store.state.value.runMode != RunModeVpnService
                }
            }
            if (running) startDeadline()
        }
    }

    suspend fun beginAttempt() {
        lifecycle.withLock {
            cancelMonitoring()
            watchingAllowed = true
            launchInProgress = true
            delivery.beginAttempt()
        }
    }

    fun currentAttempt(): Long = delivery.currentAttempt()

    fun publish(explanation: ProxyErrorExplanation, expectedAttempt: Long) {
        delivery.publish(explanation, expectedAttempt)
    }

    /** Await cancellation before returning from the ROOT stop boundary. */
    suspend fun stop() {
        lifecycle.withLock {
            watchingAllowed = false
            launchInProgress = false
            cancelMonitoring()
        }
    }

    // Called under lifecycle. The first Running confirmation owns the deadline;
    // subsequent status events must not extend the observation window.
    private fun startDeadline() {
        if (deadline != null) return
        deadline = scope.launch {
            delay(30_000L.milliseconds)
            lifecycle.withLock {
                job?.cancelAndJoin()
                job = null
            }
        }
    }

    private suspend fun cancelMonitoring() {
        deadline?.cancelAndJoin()
        deadline = null
        job?.cancelAndJoin()
        job = null
    }

    private suspend fun watch(
        context: Context,
        shell: RootShellGateway,
        layout: RootRuntimeLayout,
        baseline: Long,
        rootModeSelected: () -> Boolean,
    ) {
        val stateFile = File(layout.asteriskdStatePath)
        val episode = RootFailureEpisode(baseline)
        var observedAttempt = currentAttempt()
        while (rootModeSelected()) {
            currentCoroutineContext().ensureActive()
            val currentAttempt = currentAttempt()
            if (currentAttempt != observedAttempt) {
                episode.beginAttempt()
                observedAttempt = currentAttempt
            }
            val mtime = stateFile.lastModified()
            if (episode.needsRead(mtime)) {
                val state = diagnosticOrNull {
                    RootFailureReport.readText(shell, layout.asteriskdStatePath)?.let(::JSONObject)
                }
                if (state != null) {
                    val failure = state.optJSONObject("failure")
                    val code = failure?.optString("code")?.takeIf(String::isNotBlank)
                    // A resident supervisor may be started externally without an app launch call.
                    if (code == null && episode.hasFailure &&
                        state.optString("phase") in setOf("starting", "applying-rules", "running")
                    ) {
                        delivery.beginAttempt()
                    }
                    if (episode.shouldPublish(mtime, code) && rootModeSelected()) {
                        val time = System.currentTimeMillis()
                        val report = diagnosticOrNull { RootFailureReport.build(context, shell, layout, time) }
                        val errorTail = RootFailureReport.readText(shell, "${layout.logDirectoryPath}/error.log", 20)
                        val explanation = RootFailureAnalyzer.analyze(
                            errorCode = requireNotNull(code),
                            exitCode = failure.optInt("exitCode", -1).takeIf { it >= 0 },
                            message = failure.optString("message"),
                            mode = state.optString("mode"),
                            // Mihomo and Xray do not share sing-box's FATAL prefix.
                            extraContext = errorTail?.lineSequence()?.lastOrNull(String::isNotBlank),
                            occurredAtEpochMillis = time,
                        ).copy(deviceInfo = report?.deviceInfo.orEmpty(), serviceLog = report?.serviceLog.orEmpty())
                        currentCoroutineContext().ensureActive()
                        if (rootModeSelected()) {
                            publish(explanation, currentAttempt)
                        }
                    }
                }
            }
            delay(500L.milliseconds)
        }
    }
}

/** Pure freshness/episode policy, separate from ROOT IO for regression testing. */
internal class RootFailureEpisode(private val baseline: Long) {
    private var lastRead = Long.MIN_VALUE
    private var publishedCode: String? = null
    val hasFailure: Boolean get() = publishedCode != null

    fun beginAttempt() {
        publishedCode = null
    }

    fun needsRead(mtime: Long): Boolean = mtime > 0L && mtime != lastRead

    fun shouldPublish(mtime: Long, code: String?): Boolean {
        lastRead = mtime
        if (code == null) {
            publishedCode = null
            return false
        }
        if (mtime == baseline || code == publishedCode) return false
        publishedCode = code
        return true
    }
}

/** Both the synchronous start and the watcher may observe one failure; deliver it once. */
internal class RootFailureDelivery {
    private var attempt = 0L
    private var publishedAttempt = -1L

    @Synchronized fun beginAttempt(): Long = ++attempt
    @Synchronized fun currentAttempt(): Long = attempt

    @Synchronized fun publish(explanation: ProxyErrorExplanation, expectedAttempt: Long): Boolean {
        if (expectedAttempt != attempt || publishedAttempt == attempt) return false
        publishedAttempt = attempt
        ProxyErrorBus.publish(explanation)
        return true
    }
}
