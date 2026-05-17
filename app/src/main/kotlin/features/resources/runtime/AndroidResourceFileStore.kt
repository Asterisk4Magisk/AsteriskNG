package features.resources.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import app.ResourceFileKind
import app.ResourceFileStatus
import app.ResourceFilesStatus
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

internal class AndroidResourceFileStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    val dataDir: File = appContext.xrayResourceFilesDir()

    fun status(): ResourceFilesStatus {
        ensureBundledFiles()
        return currentStatus()
    }

    fun currentStatus(): ResourceFilesStatus {
        return ResourceFilesStatus(
            geoIp = file(ResourceFileKind.GeoIp).toStatus(),
            geoSite = file(ResourceFileKind.GeoSite).toStatus(),
            xrayCore = file(ResourceFileKind.XrayCore).toStatus(),
        )
    }

    fun file(kind: ResourceFileKind): File {
        return File(dataDir, kind.fileName)
    }

    fun ensureBundledFiles() {
        ResourceFileKind.entries.forEach { kind ->
            val target = file(kind)
            if (target.exists() && target.length() > 0) return@forEach
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
            restoreBundledAsset(kind, kind.bundledAssetPath())
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

    fun applyPermissions(kind: ResourceFileKind) {
        kind.applyPermissions(file(kind))
    }

    fun preparePaths(): XrayResourceFilePaths {
        dataDir.mkdirs()
        ensureBundledFiles()
        return XrayResourceFilePaths(
            dataDir = dataDir.absolutePath,
            setuidgidPath = File(appContext.applicationInfo.nativeLibraryDir, SetuidgidLibraryName).absolutePath,
            xrayCorePath = file(ResourceFileKind.XrayCore).absolutePath,
        )
    }
}

internal data class XrayResourceFilePaths(
    val dataDir: String,
    val setuidgidPath: String,
    val xrayCorePath: String,
)

internal fun Context.xrayResourceFilesDir(): File {
    return File(filesDir, "xray")
}

internal fun Context.prepareXrayResourceFilePaths(): XrayResourceFilePaths {
    return AndroidResourceFileStore(this).preparePaths()
}

private fun ResourceFileKind.bundledAssetPath(): String {
    return when (this) {
        ResourceFileKind.GeoIp -> fileName
        ResourceFileKind.GeoSite -> fileName
        ResourceFileKind.XrayCore -> error("xray-core is restored from native libraries")
    }
}

private fun currentRuntimeAbi(): String {
    return Build.SUPPORTED_ABIS.firstOrNull { abi -> abi in SupportedAndroidAbis }
        ?: error("Unsupported CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
}

private const val Arm64Abi = "arm64-v8a"
private const val SetuidgidLibraryName = "libsetuidgid.so"
private const val XrayCoreLibraryName = "libxray.so"

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
            while (true) {
                val entry = zip.nextEntry ?: return@runCatching false
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == entryName) {
                    writeAtomically(target) { output -> zip.copyTo(output) }
                    return@runCatching true
                }
                zip.closeEntry()
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
    target.parentFile?.mkdirs()
    val tempFile = File(target.parentFile, "${target.name}.tmp")
    try {
        tempFile.outputStream().use(write)
        if (tempFile.length() <= 0) {
            tempFile.delete()
            error("${target.name} is empty")
        }
        if (target.exists()) {
            target.delete()
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

private fun ResourceFileKind.applyPermissions(file: File) {
    if (this == ResourceFileKind.XrayCore) {
        file.setExecutable(true, false)
    }
}
