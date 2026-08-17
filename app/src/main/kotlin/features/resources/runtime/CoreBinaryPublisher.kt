// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

private val coreBinaryPublicationLocks = ConcurrentHashMap<String, Any>()

internal fun publishCoreBinaryCandidate(
    candidate: File,
    target: File,
    replaceExisting: Boolean,
): Boolean {
    val absoluteTarget = target.absoluteFile
    val publicationLock = coreBinaryPublicationLocks.computeIfAbsent(absoluteTarget.path) { Any() }
    return synchronized(publicationLock) {
        val parent = absoluteTarget.parentFile
            ?: error("Core binary target has no parent directory: ${absoluteTarget.path}")
        if (!parent.isDirectory && !parent.mkdirs()) {
            error("Failed to create core binary directory: ${parent.path}")
        }
        if (!replaceExisting && absoluteTarget.exists()) {
            return@synchronized false
        }

        val temporary = File.createTempFile(".${absoluteTarget.name}.", ".tmp", parent)
        try {
            candidate.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!temporary.setExecutable(true, false)) {
                error("Failed to make core binary executable: ${temporary.path}")
            }
            if (absoluteTarget.exists() && !absoluteTarget.delete()) {
                error("Failed to replace core binary: ${absoluteTarget.path}")
            }
            if (!temporary.renameTo(absoluteTarget)) {
                error("Failed to publish core binary: ${absoluteTarget.path}")
            }
            true
        } finally {
            temporary.delete()
        }
    }
}
