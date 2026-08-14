// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.CustomResourceFileState
import app.CustomResourceFileStatus
import app.ResourceFileKind
import app.ResourceFileStatus
import app.ResourceFilesStatus
import app.sanitizeCustomResourceFileName
import features.resources.ResourceFileSourceLoyalsoldierGithub
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

internal class AndroidResourceFileStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    val dataDir: File = appContext.xrayResourceFilesDir()

    fun status(customResourceFiles: List<CustomResourceFileState> = emptyList()): ResourceFilesStatus {
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

    fun synchronizeBundledFilesAfterPackageUpdate(resourceFileSource: Int = ResourceFileSourceLoyalsoldierGithub) {
        ensureBundledFiles(
            resourceFileSource = resourceFileSource,
            restoreAfterPackageUpdate = true,
        )
    }

    fun ensureBundledFiles(
        resourceFileSource: Int = ResourceFileSourceLoyalsoldierGithub,
        restoreAfterPackageUpdate: Boolean = false,
    ) {
        val bundledUpdatedAtMillis = appContext.packageUpdatedAtMillis()
        ResourceFileKind.entries.forEach { kind ->
            if (kind == ResourceFileKind.XrayCore) return@forEach
            val target = file(kind)
            if (
                !target.shouldRestoreBundled(
                    kind = kind,
                    resourceFileSource = resourceFileSource,
                    bundledUpdatedAtMillis = bundledUpdatedAtMillis,
                    restoreAfterPackageUpdate = restoreAfterPackageUpdate,
                )
            ) {
                return@forEach
            }
            if (!kind.hasBundledAsset()) return@forEach
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
        require(kind != ResourceFileKind.XrayCore) { "Xray core must be restored through the locked publisher" }
        restoreBundledAsset(kind, kind.bundledAssetPathOrNull() ?: error("Bundled ${kind.fileName} is unavailable"))
    }

    private fun restoreBundledAsset(kind: ResourceFileKind, assetPath: String) {
        appContext.assets.open(assetPath).use { input ->
            dataDir.mkdirs()
            writeAtomically(file(kind)) { output -> input.copyTo(output) }
        }
        kind.applyPermissions(file(kind))
    }

    fun stageBundledXrayCoreCandidate(): File {
        val source = bundledXrayCoreFileOrNull()
            ?: error("Bundled ${ResourceFileKind.XrayCore.fileName} is not available for ${currentRuntimeAbi()}")
        return source.inputStream().use(::writeXrayCoreCandidate)
    }

    fun installInitialXrayCoreCandidate(candidate: File): Boolean {
        require(candidate.isFile && candidate.length() > 0) { "Xray core candidate is empty" }
        require(dataDir.exists() || dataDir.mkdirs()) { "Failed to create ${dataDir.absolutePath}" }
        val target = file(ResourceFileKind.XrayCore)
        return synchronized(writeLockFor(target)) {
            if (target.exists()) return@synchronized false
            val temp = File.createTempFile(".xray-initial-", ".tmp", dataDir)
            try {
                candidate.inputStream().use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                        output.flush()
                        output.fd.sync()
                    }
                }
                require(temp.length() > 0) { "Xray core candidate is empty" }
                Os.chmod(temp.absolutePath, XrayExecutableMode)
                try {
                    Os.link(temp.absolutePath, target.absolutePath)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EEXIST) return@synchronized false
                    throw error
                }
                syncDirectory(dataDir)
                true
            } finally {
                temp.delete()
            }
        }
    }

    fun shouldPublishBundledXrayCore(
        resourceFileSource: Int,
        restoreAfterPackageUpdate: Boolean,
    ): Boolean {
        val bundledUpdatedAtMillis = appContext.packageUpdatedAtMillis()
        return bundledXrayCoreFileOrNull() != null && file(ResourceFileKind.XrayCore).shouldRestoreBundled(
            kind = ResourceFileKind.XrayCore,
            resourceFileSource = resourceFileSource,
            bundledUpdatedAtMillis = bundledUpdatedAtMillis,
            restoreAfterPackageUpdate = restoreAfterPackageUpdate,
        )
    }

    private fun bundledXrayCoreFileOrNull(): File? {
        if (currentRuntimeAbi() != Arm64Abi) return null
        return File(appContext.applicationInfo.nativeLibraryDir, XrayCoreLibraryName)
            .takeIf { it.isFile && it.length() > 0 }
    }

    fun replace(kind: ResourceFileKind, uri: Uri) {
        require(kind != ResourceFileKind.XrayCore) { "Xray core must be replaced through the locked publisher" }
        dataDir.mkdirs()
        val replaceTempFile = file(kind).resolveSibling("${kind.fileName}.replace.tmp")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            replaceTempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException(uri.toString())

        replaceFile(replaceTempFile, file(kind))
        kind.applyPermissions(file(kind))
    }

    fun stageXrayCoreCandidate(uri: Uri): File {
        val uploaded = appContext.contentResolver.openInputStream(uri)?.use(::writeXrayCoreCandidate)
            ?: throw FileNotFoundException(uri.toString())
        val extracted = createXrayCoreCandidateFile()
        val found = runCatching {
            ZipInputStream(uploaded.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == "xray") {
                        extracted.outputStream().use { output ->
                            zip.copyTo(output)
                            output.flush()
                            output.fd.sync()
                        }
                        return@runCatching extracted.length() > 0
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                false
            }
        }.getOrDefault(false)
        return if (found) {
            uploaded.delete()
            extracted
        } else {
            extracted.delete()
            uploaded
        }
    }

    private fun writeXrayCoreCandidate(input: java.io.InputStream): File {
        val candidate = createXrayCoreCandidateFile()
        try {
            candidate.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
            require(candidate.length() > 0) { "Xray core candidate is empty" }
            return candidate
        } catch (error: Throwable) {
            candidate.delete()
            throw error
        }
    }

    private fun createXrayCoreCandidateFile(): File {
        require(appContext.cacheDir.exists() || appContext.cacheDir.mkdirs())
        return File.createTempFile("xray-core-", ".candidate", appContext.cacheDir)
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

    fun preparePaths(restoreBundledFiles: Boolean = true): XrayResourceFilePaths {
        dataDir.mkdirs()
        if (restoreBundledFiles) {
            ensureBundledFiles()
        }
        return currentPaths()
    }

    fun currentPaths(): XrayResourceFilePaths {
        return XrayResourceFilePaths(
            dataDir = dataDir.absolutePath,
            asteriskdPath = File(appContext.applicationInfo.nativeLibraryDir, AsteriskdLibraryName).absolutePath,
            bpfMatcherPath = File(appContext.applicationInfo.nativeLibraryDir, BpfMatcherLibraryName).absolutePath,
            bpf2socksPath = File(appContext.applicationInfo.nativeLibraryDir, Bpf2SocksLibraryName).absolutePath,
            xrayCorePath = file(ResourceFileKind.XrayCore).absolutePath,
            hevSocks5TunnelPath = File(appContext.applicationInfo.nativeLibraryDir, HevSocks5TunnelLibraryName).absolutePath,
            directCidrIpv4Path = file(ResourceFileKind.DirectCidrIpv4).absolutePath,
            directCidrIpv6Path = file(ResourceFileKind.DirectCidrIpv6).absolutePath,
        )
    }
}

private fun File.shouldRestoreBundled(
    kind: ResourceFileKind,
    resourceFileSource: Int,
    bundledUpdatedAtMillis: Long,
    restoreAfterPackageUpdate: Boolean,
): Boolean {
    return shouldRestoreBundledResourceFile(
        kind = kind,
        resourceFileSource = resourceFileSource,
        targetExists = exists(),
        targetLength = takeIf { exists() }?.length() ?: 0L,
        targetLastModifiedMillis = takeIf { exists() }?.lastModified() ?: 0L,
        bundledUpdatedAtMillis = bundledUpdatedAtMillis,
        restoreAfterPackageUpdate = restoreAfterPackageUpdate,
    )
}

internal fun shouldRestoreBundledResourceFile(
    kind: ResourceFileKind,
    resourceFileSource: Int,
    targetExists: Boolean,
    targetLength: Long,
    targetLastModifiedMillis: Long,
    bundledUpdatedAtMillis: Long,
    restoreAfterPackageUpdate: Boolean,
): Boolean {
    if (!targetExists || targetLength <= 0) return true
    if (!restoreAfterPackageUpdate) return false
    if (kind != ResourceFileKind.XrayCore && resourceFileSource != ResourceFileSourceLoyalsoldierGithub) {
        return false
    }
    return bundledUpdatedAtMillis > 0 && targetLastModifiedMillis < bundledUpdatedAtMillis
}

internal data class XrayResourceFilePaths(
    val dataDir: String,
    val asteriskdPath: String,
    val bpfMatcherPath: String,
    val bpf2socksPath: String,
    val xrayCorePath: String,
    val hevSocks5TunnelPath: String,
    val directCidrIpv4Path: String,
    val directCidrIpv6Path: String,
)

internal fun Context.xrayResourceFilesDir(): File {
    return File(filesDir, "xray")
}

internal fun Context.prepareXrayResourceFilePaths(
    restoreBundledFiles: Boolean = true,
): XrayResourceFilePaths {
    return AndroidResourceFileStore(this).preparePaths(restoreBundledFiles = restoreBundledFiles)
}

internal fun Context.xrayResourceFilePaths(): XrayResourceFilePaths {
    return AndroidResourceFileStore(this).currentPaths()
}

private fun ResourceFileKind.hasBundledAsset(): Boolean {
    return this == ResourceFileKind.XrayCore || bundledAssetPathOrNull() != null
}

private fun ResourceFileKind.bundledAssetPathOrNull(): String? {
    return when (this) {
        ResourceFileKind.GeoIp -> fileName
        ResourceFileKind.GeoSite -> fileName
        ResourceFileKind.GeoIpOnlyCnPrivate -> fileName
        ResourceFileKind.DirectCidrIpv4 -> "$XrayBundledResourceFilesDir/$fileName"
        ResourceFileKind.DirectCidrIpv6 -> "$XrayBundledResourceFilesDir/$fileName"
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
private const val AsteriskdLibraryName = "libasteriskd.so"
private const val BpfMatcherLibraryName = "libbpf-matcher.so"
private const val Bpf2SocksLibraryName = "libbpf2socks.so"
private const val XrayCoreLibraryName = "libxray.so"
private const val HevSocks5TunnelLibraryName = "libhev-socks5-tunnel-cli.so"
private const val XrayBundledResourceFilesDir = "xray"
private const val XrayExecutableMode = 493

private val SupportedAndroidAbis = setOf(Arm64Abi, "armeabi-v7a", "x86", "x86_64")

internal enum class CoreCandidateInstallPath {
    AtomicPublication,
    InitialNoReplace,
    Defer,
}

internal fun chooseCoreCandidateInstallPath(
    hasRootAccess: Boolean,
    targetExists: Boolean,
): CoreCandidateInstallPath = when {
    hasRootAccess -> CoreCandidateInstallPath.AtomicPublication
    !targetExists -> CoreCandidateInstallPath.InitialNoReplace
    else -> CoreCandidateInstallPath.Defer
}

private fun syncDirectory(directory: File) {
    val descriptor = Os.open(
        directory.absolutePath,
        OsConstants.O_RDONLY,
        0,
    )
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

private fun File.toStatus(): ResourceFileStatus {
    return ResourceFileStatus(
        exists = exists() && length() > 0,
        sizeBytes = takeIf { exists() }?.length() ?: 0,
        updatedAtMillis = takeIf { exists() }?.lastModified() ?: 0,
    )
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
