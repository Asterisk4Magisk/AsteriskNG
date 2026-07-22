// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class CoreLogFile(
    val path: String,
    val defaultLevel: String,
)

internal class CoreLogFileTailer(
    private val logFiles: List<CoreLogFile>,
    private val repository: CoreLogRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        logFiles
            .filter { logFile -> logFile.path.isNotBlank() }
            .forEach { logFile ->
                scope.launch {
                    tail(logFile)
                }
            }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun tail(logFile: CoreLogFile) {
        val file = File(logFile.path)
        file.parentFile?.mkdirs()
        var position = runCatching { file.length() }.getOrDefault(0L)
        var failureLogged = false

        while (scope.isActive) {
            if (!file.exists()) {
                delay(TailIntervalMillis)
                continue
            }

            runCatching {
                RandomAccessFile(file, "r").use { reader ->
                    if (position > reader.length()) {
                        position = 0L
                    }
                    reader.seek(position)

                    var line = reader.readUtf8Line()
                    while (line != null) {
                        repository.appendParsedCoreLogLine(line, logFile.defaultLevel)
                        line = reader.readUtf8Line()
                    }
                    position = reader.filePointer
                }
            }.onSuccess {
                failureLogged = false
            }.onFailure { error ->
                if (!failureLogged) {
                    AndroidAppLogger.warn(LogTag, "Failed to tail Xray log file: ${file.absolutePath}", error)
                    failureLogged = true
                }
            }

            delay(TailIntervalMillis)
        }
    }

    private fun RandomAccessFile.readUtf8Line(): String? {
        return readLine()?.let { line ->
            String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }
    }

    private companion object {
        private const val LogTag = "CoreLogFileTailer"
        private const val TailIntervalMillis = 500L
    }
}

internal data class ParsedCoreLogLine(
    val time: String?,
    val level: String,
    val message: String,
)

private val XrayLogLineRegex = Regex("""^(\d{4}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+\[([A-Za-z]+)]\s*(.*)$""")
private val XrayLogLineWithoutLevelRegex = Regex("""^(\d{4}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+(.*)$""")
private val XrayLogTimeWhitespaceRegex = Regex("\\s+")
private const val XrayLogTimeFormat = "yyyy-MM-dd HH:mm:ss"

// Xray-core (Go) timestamps are UTC on Android: Go's runtime stubs the local
// timezone to UTC and ignores $TZ (src/time/zoneinfo_android.go initLocal), and
// libv2ray embeds no tzdata. Parse the captured stamp as UTC, render in the
// device's local timezone so the viewer shows wall-clock time.
private val utcXrayLogTimeParser = ThreadLocal.withInitial {
    SimpleDateFormat(XrayLogTimeFormat, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
}
private val localXrayLogTimeFormatter = ThreadLocal.withInitial {
    SimpleDateFormat(XrayLogTimeFormat, Locale.ROOT).apply { timeZone = TimeZone.getDefault() }
}

private fun toLocalXrayLogTime(rawXrayTime: String): String? {
    val normalized = rawXrayTime.replace('/', '-').replace(XrayLogTimeWhitespaceRegex, " ")
    // Xray-core may include sub-second precision (e.g. 11:48:12.901279) which
    // SimpleDateFormat "yyyy-MM-dd HH:mm:ss" cannot parse. Strip the fraction.
    val withoutFraction = normalized.substringBeforeLast('.')
    val instant = runCatching { utcXrayLogTimeParser.get().parse(withoutFraction) }.getOrNull() ?: return null
    val formatter = localXrayLogTimeFormatter.get()
    formatter.timeZone = TimeZone.getDefault()
    return runCatching { formatter.format(instant) }.getOrNull()
}

internal fun CoreLogRepository.appendParsedCoreLogLine(line: String, defaultLevel: String) {
    val parsedLine = parseCoreLogLine(line, defaultLevel) ?: return
    if (parsedLine.time == null) {
        append(level = parsedLine.level, message = parsedLine.message)
    } else {
        append(level = parsedLine.level, message = parsedLine.message, time = parsedLine.time)
    }
}

internal fun parseCoreLogLine(line: String, defaultLevel: String): ParsedCoreLogLine? {
    val trimmedLine = line.trim()
    if (trimmedLine.isEmpty()) {
        return null
    }

    XrayLogLineRegex.matchEntire(trimmedLine)?.let { match ->
        val (rawTime, level, message) = match.destructured
        val localTime = toLocalXrayLogTime(rawTime) ?: rawTime.replace('/', '-')
        return ParsedCoreLogLine(
            time = localTime,
            level = level,
            message = "$localTime [$level] $message",
        )
    }

    XrayLogLineWithoutLevelRegex.matchEntire(trimmedLine)?.let { match ->
        val (rawTime, message) = match.destructured
        val localTime = toLocalXrayLogTime(rawTime) ?: rawTime.replace('/', '-')
        return ParsedCoreLogLine(
            time = localTime,
            level = defaultLevel,
            message = "$localTime $message",
        )
    }

    return ParsedCoreLogLine(
        time = null,
        level = defaultLevel,
        message = trimmedLine,
    )
}
