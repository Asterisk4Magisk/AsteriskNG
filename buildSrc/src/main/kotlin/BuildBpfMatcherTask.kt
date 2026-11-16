// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

abstract class BuildBpfMatcherTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    abstract val targetAbis: ListProperty<String>

    init {
        group = "resources"
        description = "Build the native bpf-matcher helper."
    }

    @TaskAction
    fun build() {
        val source = sourceFile.get().asFile
        val ndkDir = ndkDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile
        outputDir.mkdirs()
        targetAbis.get().map { abi -> abi.toBpfMatcherAbiTarget() }.forEach { target ->
            val output = outputDir.resolve("${target.androidAbi}/libbpf-matcher.so")
            output.parentFile.mkdirs()
            execOperations.exec {
                commandLine(
                    findNdkClang(ndkDir, target).absolutePath,
                    "-O2",
                    "-flto=full",
                    "-Wall",
                    "-Wextra",
                    "-fPIE",
                    "-pie",
                    source.absolutePath,
                    "-o",
                    output.absolutePath,
                )
            }
            if (!output.exists() || output.length() <= 0) {
                throw GradleException("Failed to build bpf-matcher: ${output.absolutePath}")
            }
        }
    }

    private fun findNdkClang(ndkDir: File, target: BpfMatcherAbiTarget): File {
        val hostTag = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
            System.getProperty("os.name").contains("Mac", ignoreCase = true) -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val executableName = if (hostTag.startsWith("windows")) {
            "${target.clangTarget}${minSdk.get()}-clang.cmd"
        } else {
            "${target.clangTarget}${minSdk.get()}-clang"
        }
        val clang = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin/$executableName")
        if (!clang.exists()) {
            throw GradleException("Android NDK clang not found: ${clang.absolutePath}")
        }
        return clang
    }
}

private enum class BpfMatcherAbiTarget(
    val androidAbi: String,
    val clangTarget: String,
) {
    Arm64("arm64-v8a", "aarch64-linux-android"),
    Arm32("armeabi-v7a", "armv7a-linux-androideabi"),
    X86("x86", "i686-linux-android"),
    X64("x86_64", "x86_64-linux-android"),
}

private fun String.toBpfMatcherAbiTarget(): BpfMatcherAbiTarget {
    return BpfMatcherAbiTarget.entries.firstOrNull { target -> target.androidAbi == this }
        ?: throw GradleException("Unsupported bpf-matcher ABI: $this")
}
