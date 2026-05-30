// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.content.Context
import java.io.File

internal fun Context.androidXrayAccessLog(): CoreLogFile {
    return CoreLogFile(path = androidCoreLogAccessFile().absolutePath, defaultLevel = "info")
}

internal fun Context.androidXrayErrorLog(): CoreLogFile {
    return CoreLogFile(path = androidCoreLogErrorFile().absolutePath, defaultLevel = "error")
}

internal fun Context.androidCoreLogAccessFile(): File {
    return File(androidXrayLogDirectory(), "access.log")
}

internal fun Context.androidCoreLogErrorFile(): File {
    return File(androidXrayLogDirectory(), "error.log")
}

internal fun Context.androidAppLogcatFile(): File {
    return File(androidXrayLogDirectory(), "logcat.log")
}

internal fun Context.androidXrayLogDirectory(): File {
    return File(filesDir, AndroidXrayLogDirectoryPath).apply {
        mkdirs()
    }
}

private const val AndroidXrayLogDirectoryPath = "xray/logs"
