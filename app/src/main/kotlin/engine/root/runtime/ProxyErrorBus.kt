// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A structured explanation of a failed proxy start.
 *
 * [rawMessage] is always the real error text read from the device (supervisor state plus the
 * core's own FATAL line, or the thrown exception). [diagnostics] holds string resource ids for
 * suggested actions, and is empty unless the error matched a rule confirmed on real hardware.
 *
 * Produced by [RootFailureAnalyzer]; UI hosts consume [ProxyErrorBus] and render the
 * explanation via [features.proxy.ProxyErrorDialog].
 */
internal data class ProxyErrorExplanation(
    val mode: String,
    val occurredAtEpochMillis: Long,
    val rawMessage: String,
    @StringRes val diagnostics: List<Int>,
    /** Device/system summary produced by [RootFailureReport]; empty when unavailable. */
    val deviceInfo: String = "",
    /** Logs written by the failed start attempt; empty when unavailable. */
    val serviceLog: String = "",
) {
    val hasDiagnostics: Boolean get() = diagnostics.isNotEmpty()
    val hasDeviceInfo: Boolean get() = deviceInfo.isNotBlank()
    val hasServiceLog: Boolean get() = serviceLog.isNotBlank()
}

/**
 * Module-scoped publisher for proxy start failures. One slot; UI hosts `collectAsState` it and
 * pop the dialog; the user dismisses by calling [acknowledge].
 *
 * Lifetime is tied to the application process; on cold start the slot is `null`.
 */
internal object ProxyErrorBus {
    private val state = MutableStateFlow<ProxyErrorExplanation?>(null)

    fun observe(): StateFlow<ProxyErrorExplanation?> = state.asStateFlow()

    /**
     * Publish [explanation], unless the slot already holds that same explanation.
     *
     * Per-failure deduplication happens at the source: [RootFailureWatcher] publishes once per
     * failure episode, so one failed start cannot produce a burst of dialogs. This guard only
     * covers the trivial repeat — the identical explanation being republished, for example by a
     * retry loop — and is deliberately an equality check rather than a derived key, so it cannot
     * suppress a failure that differs in any way.
     */
    fun publish(explanation: ProxyErrorExplanation) {
        if (state.value == explanation) return
        state.value = explanation
    }

    /**
     * Clear the slot, but only while it still holds [displayed].
     *
     * A newer failure can be published between the dialog rendering and the user dismissing it.
     * An unconditional clear would discard that newer failure unseen, so the slot is only
     * cleared when it is still the explanation the user actually looked at.
     */
    fun acknowledge(displayed: ProxyErrorExplanation) {
        state.compareAndSet(displayed, null)
    }
}