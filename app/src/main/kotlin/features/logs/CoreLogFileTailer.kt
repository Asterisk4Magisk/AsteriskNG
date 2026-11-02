// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.os.FileObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class CoreLogFile(
    val path: String,
    val defaultLevel: String,
)

/**
 * Appends the lines that are written to [logFile] from now on to [repository], until the caller
 * is cancelled. inotify reports each write, so an idle core costs nothing, and a burst of writes
 * is read in one go at most every [MinFollowReadIntervalMillis]. Callers follow only while
 * someone looks at the log; earlier lines come from the repository's own refresh.
 */
internal suspend fun followCoreLogFile(logFile: CoreLogFile, repository: CoreLogRepository) {
    if (logFile.path.isBlank()) return
    coroutineScope {
        val reader = CoreLogFileReader(logFile, repository)
        // The core keeps its file open and appends to it; clearing the logs recreates it.
        val unwatch = LogDirectoryWatcher.watchCoalesced(
            file = File(logFile.path),
            mask = FileObserver.MODIFY or FileObserver.CREATE or FileObserver.MOVED_TO,
            scope = this,
            minIntervalMillis = MinFollowReadIntervalMillis,
            onChange = { withContext(Dispatchers.IO) { reader.readNewLines() } },
        )
        try {
            awaitCancellation()
        } finally {
            unwatch()
        }
    }
}

private class CoreLogFileReader(
    private val logFile: CoreLogFile,
    private val repository: CoreLogRepository,
) {
    private val file = File(logFile.path)
    private var position = runCatching { file.length() }.getOrDefault(0L)
    private var failureLogged = false

    fun readNewLines() {
        if (!file.exists()) return
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
                AndroidAppLogger.warn(LogTag, "Failed to follow Xray log file: ${file.absolutePath}", error)
                failureLogged = true
            }
        }
    }

    private fun RandomAccessFile.readUtf8Line(): String? {
        return readLine()?.let { line ->
            String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }
    }

    private companion object {
        private const val LogTag = "CoreLogFileReader"
    }
}

private const val MinFollowReadIntervalMillis = 250L

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
    val parser = utcXrayLogTimeParser.get() ?: return null
    val instant = runCatching { parser.parse(withoutFraction) }.getOrNull() ?: return null
    val formatter = localXrayLogTimeFormatter.get() ?: return null
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
