// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import java.math.BigDecimal
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun resourceAutoUpdateIntervalMillis(enabled: Boolean, hours: String): Long? {
    if (!enabled || hours.length > 64) return null
    val value = hours.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (value < BigDecimal("0.25") || value > BigDecimal("8760")) return null
    return value.multiply(BigDecimal(3_600_000)).toLong()
}

internal fun isTransientResourceUpdateFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(16).any { cause ->
        cause is SocketException || cause is SocketTimeoutException || cause is UnknownHostException ||
            HttpStatus.find(cause.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
                code == 408 || code == 429 || code in 500..599
            } == true
    }

private val HttpStatus = Regex("""(?i)\bHTTP\s+(\d{3})\b""")

internal enum class ResourceAutoUpdateOutcome { Success, Retry, Failed, Cancelled }

internal class ResourceAutoUpdateRunner(
    private val completed: (String) -> Boolean,
    private val record: (String) -> Unit,
    private val shouldContinue: () -> Boolean,
    private val attempts: (String) -> Int = { 0 },
    private val recordAttempt: (String) -> Unit = {},
    private val update: suspend (String) -> ResourceAutoUpdateOutcome,
) {
    suspend fun run(targets: List<String>): ResourceAutoUpdateOutcome {
        var retry = false
        var failed = false
        for (target in targets.sortedBy(attempts)) {
            currentCoroutineContext().ensureActive()
            if (!shouldContinue()) return ResourceAutoUpdateOutcome.Cancelled
            if (completed(target)) continue
            if (attempts(target) >= 3) {
                failed = true
                record(target)
                continue
            }
            recordAttempt(target)
            when (update(target)) {
                ResourceAutoUpdateOutcome.Success -> record(target)
                ResourceAutoUpdateOutcome.Failed -> {
                    failed = true
                    record(target)
                }
                ResourceAutoUpdateOutcome.Retry -> {
                    if (attempts(target) >= 3) {
                        failed = true
                        record(target)
                    } else retry = true
                }
                ResourceAutoUpdateOutcome.Cancelled -> return ResourceAutoUpdateOutcome.Cancelled
            }
        }
        return when {
            retry -> ResourceAutoUpdateOutcome.Retry
            failed -> ResourceAutoUpdateOutcome.Failed
            else -> ResourceAutoUpdateOutcome.Success
        }
    }
}
