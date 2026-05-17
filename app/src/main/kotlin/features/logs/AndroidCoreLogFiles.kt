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
    return File(androidCoreLogDirectory(), "access.log")
}

internal fun Context.androidCoreLogErrorFile(): File {
    return File(androidCoreLogDirectory(), "error.log")
}

internal fun Context.androidAppLogcatFile(): File {
    return File(androidAppLogDirectory(), "logcat.log")
}

private fun Context.androidCoreLogDirectory(): File {
    return File(filesDir, "xray/logs").apply {
        mkdirs()
    }
}

private fun Context.androidAppLogDirectory(): File {
    return File(filesDir, "app/logs").apply {
        mkdirs()
    }
}
