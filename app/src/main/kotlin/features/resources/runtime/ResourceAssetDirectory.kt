// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import utils.writeAtomically

/** The flat, user-managed namespace. Runtime configuration and staging live outside it. */
internal class ResourceAssetDirectory(private val runtimeDir: File) {
    val assetsDir = File(runtimeDir, "assets")
    private val stagingDir = File(runtimeDir, "resource-staging")

    fun file(name: String): File {
        require(name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && ':' !in name) { "Invalid resource file name: $name" }
        require(assetsDir.canonicalFile == File(runtimeDir.canonicalFile, "assets")) { "Unsafe resource directory" }
        val target = File(assetsDir, name)
        require(target.canonicalFile == File(assetsDir.canonicalFile, name) && !Files.isSymbolicLink(target.toPath())) { "Unsafe resource file: $name" }
        return target
    }

    fun migrateRegistered(names: Collection<String>) = synchronized(Lock) {
        val marker = File(runtimeDir, ".assets-migrated")
        if (marker.exists()) return@synchronized
        check(assetsDir.isDirectory || assetsDir.mkdirs()) { "Cannot create resource directory" }
        names.distinct().forEach { name ->
            val target = file(name)
            val source = File(runtimeDir, name)
            if (source.isFile) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        marker.writeText("1")
    }

    fun scan(supported: (String) -> Boolean): List<File> = synchronized(Lock) {
        check(assetsDir.isDirectory || assetsDir.mkdirs()) { "Cannot create resource directory" }
        require(assetsDir.canonicalFile == File(runtimeDir.canonicalFile, "assets")) { "Unsafe resource directory" }
        val entries = checkNotNull(assetsDir.listFiles()) { "Cannot read resource directory" }
        entries.filter { entry ->
            when {
                Files.isSymbolicLink(entry.toPath()) -> {
                    Files.delete(entry.toPath())
                    false
                }
                !Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS) -> false
                !supported(entry.name) -> {
                    Files.delete(entry.toPath())
                    false
                }
                else -> true
            }
        }.sortedBy { it.name }
    }

    fun createCandidate(prefix: String): File {
        check(stagingDir.isDirectory || stagingDir.mkdirs()) { "Cannot create resource staging directory" }
        return File.createTempFile(prefix, ".tmp", stagingDir)
    }

    private companion object {
        val Lock = Any()
    }
}

internal fun writeResourceAtomically(target: File, write: (OutputStream) -> Unit) {
    val staging = target.parentFile?.takeIf { it.name == "assets" }
        ?.let { File(it.parentFile, "resource-staging") }
    writeAtomically(target, temporaryDirectory = staging, write = write)
}
