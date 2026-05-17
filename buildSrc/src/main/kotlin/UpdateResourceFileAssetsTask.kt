import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

abstract class UpdateResourceFileAssetsTask : DefaultTask() {
    @get:Input
    abstract val xrayCoreVersion: Property<String>

    @get:OutputFile
    abstract val xrayCoreFile: RegularFileProperty

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
    }

    private fun xrayCoreArchiveUrl(): String {
        val version = xrayCoreVersion.get()
        return "https://github.com/XTLS/Xray-core/releases/download/$version/Xray-android-arm64-v8a.zip"
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
}
