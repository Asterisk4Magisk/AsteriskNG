package features.resources.runtime

import features.logs.AndroidAppLogger

internal object AndroidResourceFileLogger {
    private const val Tag = "AndroidResourceFiles"

    fun info(message: String) {
        AndroidAppLogger.info(Tag, message)
    }

    fun warn(message: String, error: Throwable? = null) {
        AndroidAppLogger.warn(Tag, message, error)
    }

    fun error(message: String, error: Throwable? = null) {
        AndroidAppLogger.error(Tag, message, error)
    }
}
