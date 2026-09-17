// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun writeAtomically(
    target: File,
    write: (OutputStream) -> Unit,
) {
    val parent = target.parentFile ?: error("Parent directory is unavailable for ${target.absolutePath}")
    parent.mkdirs()
    synchronized(writeLockFor(target)) {
        val tempPrefix = "${target.name}.".let { prefix ->
            if (prefix.length >= 3) prefix else prefix.padEnd(3, '_')
        }
        val tempFile = File.createTempFile(tempPrefix, ".tmp", parent)
        try {
            tempFile.outputStream().use(write)
            if (tempFile.length() <= 0) {
                tempFile.delete()
                error("${target.name} is empty")
            }
            // Never unlink the live file before publication: a failed move must leave it usable.
            Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }
}

private val WriteLocks = mutableMapOf<String, Any>()

private fun writeLockFor(target: File): Any {
    return synchronized(WriteLocks) {
        WriteLocks.getOrPut(target.absolutePath) { Any() }
    }
}
