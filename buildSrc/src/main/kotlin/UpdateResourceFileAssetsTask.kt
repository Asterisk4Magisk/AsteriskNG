// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

abstract class UpdateResourceFileAssetsTask : DefaultTask() {
    @get:Input
    abstract val xrayCoreVersion: Property<String>

    @get:Input
    abstract val hevSocks5TunnelVersion: Property<String>

    @get:Input
    abstract val directCidrIpv4Url: Property<String>

    @get:Input
    abstract val directCidrIpv6Url: Property<String>

    @get:OutputFile
    abstract val xrayCoreFile: RegularFileProperty

    @get:OutputFile
    abstract val directCidrIpv4File: RegularFileProperty

    @get:OutputFile
    abstract val directCidrIpv6File: RegularFileProperty

    @get:OutputDirectory
    abstract val hevSocks5TunnelJniLibsDir: DirectoryProperty

    init {
        group = "resources"
        description = "Download bundled resource file assets."
    }

    @TaskAction
    fun updateAssets() {
        downloadZipEntry(
            url = xrayCoreArchiveUrl(),
            entryName = "xray",
            target = xrayCoreFile.get().asFile,
        )
        downloadFile(
            url = directCidrIpv4Url.get(),
            target = directCidrIpv4File.get().asFile,
        )
        downloadFile(
            url = directCidrIpv6Url.get(),
            target = directCidrIpv6File.get().asFile,
        )
        AndroidHevSocks5TunnelAssets.forEach { asset ->
            downloadFile(
                url = hevSocks5TunnelArchiveUrl(asset.releaseName),
                target = File(hevSocks5TunnelJniLibsDir.get().asFile, "${asset.androidAbi}/libhev-socks5-tunnel.so"),
            )
        }
    }

    private fun xrayCoreArchiveUrl(): String {
        val version = xrayCoreVersion.get()
        return "https://github.com/XTLS/Xray-core/releases/download/$version/Xray-android-arm64-v8a.zip"
    }

    private fun hevSocks5TunnelArchiveUrl(releaseName: String): String {
        val version = hevSocks5TunnelVersion.get()
        return "https://github.com/heiher/hev-socks5-tunnel/releases/download/$version/$releaseName"
    }

    private fun downloadZipEntry(url: String, entryName: String, target: File) {
        target.parentFile.mkdirs()
        val tempFile = target.resolveSibling("${target.name}.tmp")
        logger.lifecycle("Downloading $url")
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw GradleException("Failed to download $url: HTTP $code")
            }
            ZipInputStream(connection.inputStream).use { zip ->
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == entryName) {
                        tempFile.outputStream().use { output ->
                            zip.copyTo(output)
                        }
                        found = true
                        break
                    }
                    zip.closeEntry()
                }
                if (!found) {
                    throw GradleException("Archive does not contain $entryName: $url")
                }
            }
        } finally {
            connection.disconnect()
        }
        if (tempFile.length() <= 0) {
            tempFile.delete()
            throw GradleException("Downloaded file is empty: $url")
        }
        if (target.exists()) {
            target.delete()
        }
        if (!tempFile.renameTo(target)) {
            throw GradleException("Unable to move ${tempFile.absolutePath} to ${target.absolutePath}")
        }
        logger.lifecycle("Updated ${target.absolutePath} (${target.length()} bytes)")
    }

    private fun downloadFile(url: String, target: File) {
        target.parentFile.mkdirs()
        val tempFile = target.resolveSibling("${target.name}.tmp")
        logger.lifecycle("Downloading $url")
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw GradleException("Failed to download $url: HTTP $code")
            }
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (tempFile.length() <= 0) {
            tempFile.delete()
            throw GradleException("Downloaded file is empty: $url")
        }
        if (target.exists()) {
            target.delete()
        }
        if (!tempFile.renameTo(target)) {
            throw GradleException("Unable to move ${tempFile.absolutePath} to ${target.absolutePath}")
        }
        logger.lifecycle("Updated ${target.absolutePath} (${target.length()} bytes)")
    }
}

private data class HevSocks5TunnelAsset(
    val androidAbi: String,
    val releaseName: String,
)

private val AndroidHevSocks5TunnelAssets = listOf(
    HevSocks5TunnelAsset("arm64-v8a", "hev-socks5-tunnel-linux-arm64"),
    HevSocks5TunnelAsset("armeabi-v7a", "hev-socks5-tunnel-linux-arm32v7"),
    HevSocks5TunnelAsset("x86", "hev-socks5-tunnel-linux-i686"),
    HevSocks5TunnelAsset("x86_64", "hev-socks5-tunnel-linux-x86_64"),
)
