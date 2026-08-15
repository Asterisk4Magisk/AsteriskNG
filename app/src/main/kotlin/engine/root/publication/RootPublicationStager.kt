// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import java.io.File

internal object RootPublicationStager {
    fun stage(
        stagingDirectory: String,
        coreConfigText: String,
        encodedDaemonConfig: String,
    ): RootStagedPublication {
        val directory = requireSafeDirectory(stagingDirectory)
        val coreConfig = writeStagedFile(directory, "xray-config-", coreConfigText)
        return try {
            RootStagedPublication(
                coreConfig = coreConfig,
                asteriskdConfig = writeStagedFile(directory, "asteriskd-config-", encodedDaemonConfig),
            )
        } catch (error: Throwable) {
            coreConfig.delete()
            throw error
        }
    }

    private fun requireSafeDirectory(path: String): File = File(path).also { directory ->
        require(directory.exists() || directory.mkdirs()) { "Failed to create publication staging directory" }
        require(
            directory.isDirectory && isSafeRootPrivateDirectoryIdentity(
                absolutePath = directory.absolutePath,
                canonicalPath = directory.canonicalPath,
            ),
        ) {
            "Unsafe publication staging directory"
        }
    }

    private fun writeStagedFile(directory: File, prefix: String, content: String): File {
        val file = File.createTempFile(prefix, ".json", directory)
        try {
            file.outputStream().use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            require(file.setReadable(false, false) && file.setReadable(true, true))
            require(file.setWritable(false, false) && file.setWritable(true, true))
            file.setExecutable(false, false)
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}

internal fun isSafeRootPrivateDirectoryIdentity(
    absolutePath: String,
    canonicalPath: String,
): Boolean {
    if (!absolutePath.startsWith('/') || !canonicalPath.startsWith('/')) return false
    if (absolutePath == canonicalPath) return true
    return absolutePath.startsWith(AndroidUserDataPrefix) &&
        canonicalPath.startsWith(AndroidLegacyDataPrefix) &&
        absolutePath.removePrefix(AndroidUserDataPrefix) == canonicalPath.removePrefix(AndroidLegacyDataPrefix)
}

@Suppress("SdCardPath")
private const val AndroidUserDataPrefix = "/data/user/0/"

@Suppress("SdCardPath")
private const val AndroidLegacyDataPrefix = "/data/data/"

internal class RootStagedPublication(
    val coreConfig: File,
    val asteriskdConfig: File,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        coreConfig.delete()
        asteriskdConfig.delete()
    }
}
