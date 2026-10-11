// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.Properties
import javax.inject.Inject

abstract class BuildEnhanceTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:LocalState
    abstract val workDirectory: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Input
    abstract val targetAbis: ListProperty<String>

    init {
        group = "resources"
        description = "Build the application-list enhancement module for every app ABI."
    }

    @TaskAction
    fun build() {
        val properties = Properties()
        localPropertiesFile.orNull?.asFile?.takeIf(File::exists)?.inputStream()?.use(properties::load)
        val sdkDir = properties.getProperty("sdk.dir")?.takeIf(String::isNotBlank)?.let(::File)
            ?: listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").firstNotNullOfOrNull { name ->
                System.getenv(name)?.takeIf(String::isNotBlank)?.let(::File)
            }
            ?: throw GradleException("Android SDK location is missing.")
        val ndkDir = properties.getProperty("ndk.dir")?.takeIf(String::isNotBlank)?.let(::File)
            ?: listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT").firstNotNullOfOrNull { name ->
                System.getenv(name)?.takeIf(String::isNotBlank)?.let(::File)
            }
            ?: sdkDir.resolve("ndk").listFiles()?.filter(File::isDirectory)?.maxByOrNull(File::getName)
            ?: throw GradleException("Android NDK location is missing.")
        val sourceDir = sourceDirectory.get().asFile
        val script = sourceDir.resolve("tools/build.py")
        if (!script.isFile) throw GradleException("AsteriskEnhance source is missing: $sourceDir")
        execOperations.exec {
            workingDir(sourceDir)
            commandLine(
                "python", script.absolutePath,
                "--ndk", ndkDir.absolutePath,
                "--sdk", sdkDir.absolutePath,
                "--min-sdk", minSdk.get().toString(),
                "--work-directory", workDirectory.get().asFile.absolutePath,
                "--output-directory", outputDirectory.get().asFile.absolutePath,
                "--skip-package",
                "--abis", *targetAbis.get().toTypedArray(),
            )
        }.assertNormalExitValue()
    }
}

