import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

abstract class DownloadAndroidLibXrayLiteAarTask : DefaultTask() {
    @get:Input
    abstract val androidLibXrayLiteVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val aarUrl: Property<String>

    @get:OutputFile
    abstract val outputAar: RegularFileProperty

    init {
        group = "build"
        description = "Download the prebuilt AndroidLibXrayLite AAR into app/libs."
    }

    @TaskAction
    fun downloadAar() {
        val version = androidLibXrayLiteVersion.get()
        val url = aarUrl.orNull?.takeIf(String::isNotBlank) ?: defaultAarUrl(version)
        val destination = outputAar.get().asFile
        val temporaryFile = temporaryDir.resolve("AndroidLibXrayLite-$version.aar.download")

        logger.lifecycle("Downloading AndroidLibXrayLite $version from $url")
        download(url, temporaryFile)
        if (!temporaryFile.isFile || temporaryFile.length() <= 0) {
            temporaryFile.delete()
            throw GradleException("Downloaded AndroidLibXrayLite AAR is empty: $url")
        }

        destination.parentFile.mkdirs()
        temporaryFile.copyTo(destination, overwrite = true)
        logger.lifecycle("Copied ${temporaryFile.absolutePath} to ${destination.absolutePath}")
    }

    private fun download(url: String, destination: File) {
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AsteriskNG Gradle")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw GradleException("Failed to download AndroidLibXrayLite AAR: HTTP $code from $url")
            }
            destination.parentFile.mkdirs()
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun defaultAarUrl(version: String): String {
        return "https://github.com/2dust/AndroidLibXrayLite/releases/download/$version/libv2ray.aar"
    }
}
