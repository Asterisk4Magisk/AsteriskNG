// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.CustomResourceFileState
import app.CustomResourceFileStatus
import app.ResourceFileKind
import app.ResourceFileStatus
import app.ResourceFilesStatus
import app.sanitizeCustomResourceFileName
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

internal class AndroidResourceFileStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    val dataDir: File = appContext.xrayResourceFilesDir()

    fun status(customResourceFiles: List<CustomResourceFileState> = emptyList()): ResourceFilesStatus {
        ensureBundledFiles()
        return currentStatus(customResourceFiles)
    }

    fun currentStatus(customResourceFiles: List<CustomResourceFileState> = emptyList()): ResourceFilesStatus {
        return ResourceFilesStatus(
            geoIp = file(ResourceFileKind.GeoIp).toStatus(),
            geoSite = file(ResourceFileKind.GeoSite).toStatus(),
            geoIpOnlyCnPrivate = file(ResourceFileKind.GeoIpOnlyCnPrivate).toStatus(),
            directCidrIpv4 = file(ResourceFileKind.DirectCidrIpv4).toStatus(),
            directCidrIpv6 = file(ResourceFileKind.DirectCidrIpv6).toStatus(),
            xrayCore = file(ResourceFileKind.XrayCore).toStatus(),
            customResourceFiles = customResourceFiles.map { customFile ->
                CustomResourceFileStatus(
                    file = customFile,
                    status = file(customFile).toStatus(),
                )
            },
        )
    }

    fun file(kind: ResourceFileKind): File {
        return File(dataDir, kind.fileName)
    }

    fun file(customFile: CustomResourceFileState): File {
        return File(
            dataDir,
            sanitizeCustomResourceFileName(
                value = customFile.name,
                fallback = "custom-resource-${customFile.id}.dat",
            ),
        )
    }

    fun ensureBundledFiles() {
        val bundledUpdatedAtMillis = appContext.packageUpdatedAtMillis()
        ResourceFileKind.entries.forEach { kind ->
            val target = file(kind)
            if (!target.needsBundledRestore(bundledUpdatedAtMillis)) return@forEach
            if (!kind.hasBundledAsset()) return@forEach
            if (kind == ResourceFileKind.XrayCore && bundledXrayCoreFileOrNull() == null) return@forEach
            runCatching { restoreBundled(kind) }
                .onFailure { error ->
                    AndroidResourceFileLogger.warn(
                        "Failed to restore bundled resource file: ${kind.fileName}",
                        error,
                    )
                }
        }
    }

    fun restoreBundled(kind: ResourceFileKind) {
        if (kind == ResourceFileKind.XrayCore) {
            restoreBundledXrayCore()
        } else {
            restoreBundledAsset(kind, kind.bundledAssetPathOrNull() ?: error("Bundled ${kind.fileName} is unavailable"))
        }
    }

    private fun restoreBundledAsset(kind: ResourceFileKind, assetPath: String) {
        appContext.assets.open(assetPath).use { input ->
            dataDir.mkdirs()
            writeAtomically(file(kind)) { output -> input.copyTo(output) }
        }
        kind.applyPermissions(file(kind))
    }

    private fun restoreBundledXrayCore() {
        val source = bundledXrayCoreFileOrNull()
            ?: error("Bundled ${ResourceFileKind.XrayCore.fileName} is not available for ${currentRuntimeAbi()}")
        dataDir.mkdirs()
        source.inputStream().use { input ->
            writeAtomically(file(ResourceFileKind.XrayCore)) { output -> input.copyTo(output) }
        }
        ResourceFileKind.XrayCore.applyPermissions(file(ResourceFileKind.XrayCore))
    }

    private fun bundledXrayCoreFileOrNull(): File? {
        if (currentRuntimeAbi() != Arm64Abi) return null
        return File(appContext.applicationInfo.nativeLibraryDir, XrayCoreLibraryName)
            .takeIf { it.isFile && it.length() > 0 }
    }

    fun replace(kind: ResourceFileKind, uri: Uri) {
        dataDir.mkdirs()
        val replaceTempFile = file(kind).resolveSibling("${kind.fileName}.replace.tmp")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            replaceTempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException(uri.toString())

        if (kind == ResourceFileKind.XrayCore && replaceTempFile.extractZipEntry("xray", file(kind))) {
            replaceTempFile.delete()
        } else {
            replaceFile(replaceTempFile, file(kind))
        }
        kind.applyPermissions(file(kind))
    }

    fun replaceCustom(customFile: CustomResourceFileState, uri: Uri) {
        val target = file(customFile)
        if (ResourceFileKind.entries.any { kind -> kind.fileName == target.name }) return
        dataDir.mkdirs()
        val replaceTempFile = target.resolveSibling("${target.name}.replace.tmp")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            replaceTempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException(uri.toString())

        replaceFile(replaceTempFile, target)
    }

    fun applyPermissions(kind: ResourceFileKind) {
        kind.applyPermissions(file(kind))
    }

    fun deleteCustom(customFile: CustomResourceFileState) {
        val target = file(customFile)
        if (ResourceFileKind.entries.any { kind -> kind.fileName == target.name }) return
        target.delete()
    }

    fun renameCustom(previousFile: CustomResourceFileState, customFile: CustomResourceFileState) {
        val source = file(previousFile)
        val target = file(customFile)
        if (ResourceFileKind.entries.any { kind -> kind.fileName == source.name || kind.fileName == target.name }) return
        if (source.absolutePath == target.absolutePath) return
        if (!source.isFile) return

        dataDir.mkdirs()
        if (target.exists()) {
            target.delete()
        }
        if (!source.renameTo(target)) {
            source.inputStream().use { input ->
                writeAtomically(target) { output -> input.copyTo(output) }
            }
            source.delete()
        }
    }

    fun preparePaths(): XrayResourceFilePaths {
        dataDir.mkdirs()
        ensureBundledFiles()
        return XrayResourceFilePaths(
            dataDir = dataDir.absolutePath,
            setuidgidPath = File(appContext.applicationInfo.nativeLibraryDir, SetuidgidLibraryName).absolutePath,
            ipv6DisablerPath = File(appContext.applicationInfo.nativeLibraryDir, Ipv6DisablerLibraryName).absolutePath,
            bpfMatcherPath = File(appContext.applicationInfo.nativeLibraryDir, BpfMatcherLibraryName).absolutePath,
            xrayCorePath = file(ResourceFileKind.XrayCore).absolutePath,
            hevSocks5TunnelPath = File(appContext.applicationInfo.nativeLibraryDir, HevSocks5TunnelLibraryName).absolutePath,
            directCidrIpv4Path = file(ResourceFileKind.DirectCidrIpv4).absolutePath,
            directCidrIpv6Path = file(ResourceFileKind.DirectCidrIpv6).absolutePath,
        )
    }
}

private fun File.needsBundledRestore(bundledUpdatedAtMillis: Long): Boolean {
    if (!exists() || length() <= 0) return true
    return bundledUpdatedAtMillis > 0 && lastModified() < bundledUpdatedAtMillis
}

internal data class XrayResourceFilePaths(
    val dataDir: String,
    val setuidgidPath: String,
    val ipv6DisablerPath: String,
    val bpfMatcherPath: String,
    val xrayCorePath: String,
    val hevSocks5TunnelPath: String,
    val directCidrIpv4Path: String,
    val directCidrIpv6Path: String,
)

internal fun Context.xrayResourceFilesDir(): File {
    return File(filesDir, "xray")
}

internal fun Context.prepareXrayResourceFilePaths(): XrayResourceFilePaths {
    return AndroidResourceFileStore(this).preparePaths()
}

private fun ResourceFileKind.hasBundledAsset(): Boolean {
    return this == ResourceFileKind.XrayCore || bundledAssetPathOrNull() != null
}

private fun ResourceFileKind.bundledAssetPathOrNull(): String? {
    return when (this) {
        ResourceFileKind.GeoIp -> fileName
        ResourceFileKind.GeoSite -> fileName
        ResourceFileKind.GeoIpOnlyCnPrivate -> fileName
        ResourceFileKind.DirectCidrIpv4 -> fileName
        ResourceFileKind.DirectCidrIpv6 -> fileName
        ResourceFileKind.XrayCore -> error("Xray-core is restored from native libraries")
    }
}

private fun currentRuntimeAbi(): String {
    return Build.SUPPORTED_ABIS.firstOrNull { abi -> abi in SupportedAndroidAbis }
        ?: error("Unsupported CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
}

private fun Context.packageUpdatedAtMillis(): Long {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager
                .getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                .lastUpdateTime
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }
    }.getOrDefault(0L)
}

private const val Arm64Abi = "arm64-v8a"
private const val SetuidgidLibraryName = "libsetuidgid.so"
private const val Ipv6DisablerLibraryName = "libipv6disabler.so"
private const val BpfMatcherLibraryName = "libbpf-matcher.so"
private const val XrayCoreLibraryName = "libxray.so"
private const val HevSocks5TunnelLibraryName = "libhev-socks5-tunnel.so"

private val SupportedAndroidAbis = setOf(Arm64Abi, "armeabi-v7a", "x86", "x86_64")

private fun File.toStatus(): ResourceFileStatus {
    return ResourceFileStatus(
        exists = exists() && length() > 0,
        sizeBytes = takeIf { exists() }?.length() ?: 0,
        updatedAtMillis = takeIf { exists() }?.lastModified() ?: 0,
    )
}

private fun File.extractZipEntry(entryName: String, target: File): Boolean {
    return runCatching {
        ZipInputStream(inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == entryName) {
                    writeAtomically(target) { output -> zip.copyTo(output) }
                    return@runCatching true
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            false
        }
    }.onFailure { error ->
        AndroidResourceFileLogger.warn("Failed to extract $entryName from $absolutePath", error)
    }.getOrDefault(false)
}

private fun replaceFile(source: File, target: File) {
    if (source.length() <= 0) {
        source.delete()
        error("${target.name} is empty")
    }
    if (target.exists()) {
        target.delete()
    }
    if (!source.renameTo(target)) {
        source.inputStream().use { input ->
            writeAtomically(target) { output -> input.copyTo(output) }
        }
        source.delete()
    }
}

internal fun writeAtomically(
    target: File,
    write: (java.io.OutputStream) -> Unit,
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
            if (target.exists() && !target.delete()) {
                tempFile.delete()
                error("Failed to replace ${target.name}")
            }
            if (!tempFile.renameTo(target)) {
                tempFile.delete()
                error("Failed to replace ${target.name}")
            }
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

private fun ResourceFileKind.applyPermissions(file: File) {
    if (this == ResourceFileKind.XrayCore) {
        file.setExecutable(true, false)
    }
}
