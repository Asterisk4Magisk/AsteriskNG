// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import engine.root.publication.RootRuntimeLayout
import system.RootShellGateway
import system.ShellExecOptions
import utils.shellQuote
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds the diagnostic payload attached to a failure dialog: a device/system summary and the
 * service logs written by the failed start attempt.
 *
 * Everything here is best-effort. A field that cannot be read is omitted rather than failing
 * the report, because the report is only useful if it is produced at all.
 *
 * Scope note: the service log directory is cleaned by the ROOT publication step on every start,
 * so `error.log` and `asteriskd.log` describe the attempt that just failed. `logcat.log` is
 * cumulative, so only its tail is included.
 */
internal data class RootFailureReport(
    val deviceInfo: String,
    val serviceLog: String,
) {
    companion object {
        private const val LogcatTailLines = 80
        private const val PerFileLineCap = 200
        private const val ServiceLogCharCap = 8_000

        /**
         * How far back the app log is kept, relative to the failure. The service log directory is
         * per-attempt, but `logcat.log` is cumulative and the app logs sparsely, so an unfiltered
         * tail reaches back tens of minutes and buries the lines that matter.
         */
        private const val AppLogWindowMillis = 10 * 60 * 1_000L
        private const val AppLogTimestampPattern = "yyyy-MM-dd HH:mm:ss"

        suspend fun build(
            context: Context,
            shell: RootShellGateway,
            layout: RootRuntimeLayout,
            occurredAtEpochMillis: Long,
        ): RootFailureReport = RootFailureReport(
            deviceInfo = buildDeviceInfo(context, shell),
            // Paths in the service log are redacted before the text can reach the dialog or the
            // clipboard: this report is meant to be pasted into a public issue.
            serviceLog = DiagnosticRedaction.redact(
                buildServiceLog(shell, layout, occurredAtEpochMillis),
            ),
        )

        // --- device / system ---------------------------------------------------------

        private suspend fun buildDeviceInfo(context: Context, shell: RootShellGateway): String {
            val lines = mutableListOf<String>()

            appVersion(context)?.let { lines += "app: $it" }
            lines += "model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
            lines += "android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            Build.DISPLAY.takeIf { it.isNotBlank() }?.let { lines += "build: $it" }
            if (Build.SUPPORTED_ABIS.isNotEmpty()) {
                lines += "abi: ${Build.SUPPORTED_ABIS.joinToString(", ")}"
            }
            kernelVersion()?.let { lines += "kernel: $it" }
            pageSizeKb()?.let { lines += "page size: ${it} KB" }
            selinuxMode(shell)?.let { lines += "selinux: $it" }
            rootSolution(shell)?.let { lines += "root: $it" }

            return lines.joinToString("\n")
        }

        private fun appVersion(context: Context): String? = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrNull()

        private fun kernelVersion(): String? =
            System.getProperty("os.version")?.takeIf { it.isNotBlank() }

        private fun pageSizeKb(): Long? = runCatching {
            Os.sysconf(OsConstants._SC_PAGESIZE) / 1024L
        }.getOrNull()?.takeIf { it > 0L }

        /**
         * SELinux mode from sysfs, which stays readable without root on the platforms this app
         * targets; falls back to a root read when it is not.
         */
        private suspend fun selinuxMode(shell: RootShellGateway): String? {
            readText(shell, "/sys/fs/selinux/enforce")?.let { value ->
                return when (value.trim()) {
                    "1" -> "enforcing"
                    "0" -> "permissive"
                    else -> null
                }
            }
            return null
        }

        /** KernelSU / Magisk version, best-effort; omitted when neither is discoverable. */
        private suspend fun rootSolution(shell: RootShellGateway): String? {
            // Try each candidate directly. Chaining these with `&&` / `||` is a trap: shell
            // operator precedence is left-associative, so a mixed chain silently runs the wrong
            // branch and the version ends up unreported.
            val candidates = listOf(
                "ksud",
                "/data/adb/ksu/bin/ksud",
                "/data/adb/ksud",
                "magisk",
                "/data/adb/magisk/magisk",
            )
            for (candidate in candidates) {
                val value = diagnosticOrNull {
                    shell.exec("$candidate -V 2>/dev/null", ShellExecOptions(logFailure = false))
                        .stdout
                        .lineSequence()
                        .firstOrNull { it.isNotBlank() }
                        ?.trim()
                }
                if (!value.isNullOrBlank()) return value
            }
            return null
        }

        // --- service logs ------------------------------------------------------------

        private suspend fun buildServiceLog(
            shell: RootShellGateway,
            layout: RootRuntimeLayout,
            occurredAtEpochMillis: Long,
        ): String {
            // error.log and asteriskd.log describe the attempt that just failed and are cleared on
            // every start, so they are the most valuable part of the report. They are collected
            // first and never truncated away; the cumulative app log fills whatever budget is
            // left, and only its newest lines survive because those sit nearest the failure.
            val attemptLogs = buildList {
                readText(shell, "${layout.logDirectoryPath}/error.log", PerFileLineCap)
                    ?.let { add("[error.log]\n" + it.takeLast(3_000)) }
                readText(shell, "${layout.logDirectoryPath}/asteriskd.log", PerFileLineCap)
                    ?.let { add("[asteriskd.log]\n" + it.takeLast(3_000)) }
            }.filter { it.isNotBlank() }.joinToString("\n")

            val appLogBudget = (ServiceLogCharCap - attemptLogs.length).coerceAtLeast(0)
            val appLog = readText(shell, "${layout.logDirectoryPath}/logcat.log", tailLines = LogcatTailLines)
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.filter { line -> isWithinWindow(line, occurredAtEpochMillis) }
                ?.map { decodeAppLogLine(it) }
                ?.joinToString("\n")
                ?.takeLast(appLogBudget)
                ?.takeIf { it.isNotBlank() }
                .orEmpty()

            return listOf(attemptLogs, appLog)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        /**
         * Keep only app-log lines timestamped within [AppLogWindowMillis] before the failure.
         * Lines whose timestamp cannot be parsed are dropped: for a bug report, a line that
         * cannot be placed in time is not worth the noise.
         */
        private fun isWithinWindow(line: String, occurredAtEpochMillis: Long): Boolean {
            val first = line.substringBefore('\t')
            val parsed = runCatching {
                SimpleDateFormat(AppLogTimestampPattern, Locale.getDefault()).parse(first)?.time
            }.getOrNull() ?: return false
            return parsed in (occurredAtEpochMillis - AppLogWindowMillis)..occurredAtEpochMillis
        }

        /**
         * `logcat.log` lines are `timestamp<TAB>level<TAB>base64(message)` — the app encodes the
         * message so that newlines inside it cannot break the line format. Decode for display.
         */
        private fun decodeAppLogLine(line: String): String {
            val parts = line.split('\t')
            if (parts.size < 3) return line
            val decoded = runCatching {
                String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: return line
            return "${parts[0]} ${parts[1]} $decoded"
        }

        private fun String.capLines(limit: Int): String {
            val lines = lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.size <= limit) return lines.joinToString("\n")
            val omitted = lines.size - limit
            return (listOf("... $omitted earlier line(s) omitted") + lines.takeLast(limit))
                .joinToString("\n")
        }

        // --- io ----------------------------------------------------------------------

        /**
         * The supervisor writes its files as root and the app uid cannot open them, so reads go
         * through the ROOT shell gateway. A direct read is attempted as a fallback.
         */
        internal suspend fun readText(shell: RootShellGateway, path: String, tailLines: Int? = null): String? {
            currentCoroutineContext().ensureActive()
            // `logcat.log` grows without bound, so when only the tail is wanted ask the shell for
            // it rather than piping the whole file through the shell and discarding the head.
            val command = if (tailLines != null) {
                "tail -n $tailLines ${path.shellQuote()}"
            } else {
                "cat ${path.shellQuote()}"
            }
            val viaShell = diagnosticOrNull {
                val result = shell.exec(command, ShellExecOptions(logFailure = false))
                result.stdout.takeIf { result.errno == 0 && it.isNotBlank() }
            }
            if (viaShell != null) return viaShell

            currentCoroutineContext().ensureActive()
            return diagnosticOrNull {
                File(path).takeIf { it.isFile && it.canRead() }?.useLines { lines ->
                    if (tailLines == null) lines.joinToString("\n") else {
                        val tail = ArrayDeque<String>()
                        lines.forEach { line ->
                            tail.addLast(line)
                            if (tail.size > tailLines) tail.removeFirst()
                        }
                        tail.joinToString("\n")
                    }
                }
            }?.takeIf { it.isNotBlank() }
        }
    }
}

/** Diagnostics may fail, but cancellation must still stop ROOT work. */
internal inline fun <T> diagnosticOrNull(block: () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
