// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.util.Log

internal object AndroidAppLogger {
    fun platformWarn(tag: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, error)
        }
    }

    fun debug(tag: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.d(tag, message)
        } else {
            Log.d(tag, message, error)
        }
        AndroidLogcatRepository.append("debug", logcatMessage(tag, message, error))
    }

    fun info(tag: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.i(tag, message)
        } else {
            Log.i(tag, message, error)
        }
        AndroidLogcatRepository.append("info", logcatMessage(tag, message, error))
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, error)
        }
        AndroidLogcatRepository.append("warning", logcatMessage(tag, message, error))
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, error)
        }
        AndroidLogcatRepository.append("error", logcatMessage(tag, message, error))
    }

    private fun logcatMessage(tag: String, message: String, error: Throwable?): String {
        return buildString {
            append(tag)
            append(": ")
            append(message)
            if (error != null) {
                val stackTrace = Log.getStackTraceString(error).trim()
                if (stackTrace.isNotEmpty()) {
                    append('\n')
                    append(stackTrace)
                }
            }
        }
    }
}
