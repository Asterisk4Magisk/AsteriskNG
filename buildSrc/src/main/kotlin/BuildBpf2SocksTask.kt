// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.Properties
import javax.inject.Inject

abstract class BuildBpf2SocksTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputDirectory
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Input
    abstract val targetAbis: ListProperty<String>

    init {
        group = "resources"
        description = "Build the native bpf2socks helper."
    }

    @TaskAction
    fun build() {
        val sourceDir = sourceDirectory.get().asFile
        val userSpaceSources = sourceDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.extension == "c" &&
                    !file.name.endsWith(".bpf.c")
            }
            ?.sortedBy { file -> file.name }
            .orEmpty()
        if (userSpaceSources.isEmpty()) {
            throw GradleException("No bpf2socks C sources found under ${sourceDir.absolutePath}")
        }

        val ndkDir = findNdkDir()
        val embeddedSource = buildEmbeddedBpfSource(ndkDir, sourceDir)
        val sources = userSpaceSources + embeddedSource
        val outputDir = outputDirectory.get().asFile
        outputDir.mkdirs()
        targetAbis.get().map { abi -> abi.toBpf2SocksAbiTarget() }.forEach { target ->
            val output = outputDir.resolve("${target.androidAbi}/libbpf2socks.so")
            output.parentFile.mkdirs()
            execOperations.exec {
                commandLine(
                    listOf(
                        findNdkClang(ndkDir, target).absolutePath,
                        "-O3",
                        "-Wall",
                        "-Wextra",
                        "-Werror",
                        "-fPIE",
                        "-pie",
                    ) + sources.map(File::getAbsolutePath) + listOf(
                        "-o",
                        output.absolutePath,
                    ),
                )
            }
            if (!output.exists() || output.length() <= 0) {
                throw GradleException("Failed to build bpf2socks: ${output.absolutePath}")
            }
        }
    }

    private fun buildEmbeddedBpfSource(ndkDir: File, sourceDir: File): File {
        val bpfSource = sourceDir.resolve("tc_redirect.bpf.c")
        if (!bpfSource.isFile) {
            throw GradleException("Missing bpf2socks TC source: ${bpfSource.absolutePath}")
        }
        val workDir = temporaryDir.resolve("embedded-bpf").apply { mkdirs() }
        val objectFile = workDir.resolve("tc_redirect.bpf.o")
        val hostTag = ndkHostTag()
        val clangName = if (hostTag.startsWith("windows")) "clang.exe" else "clang"
        val clang = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin/$clangName")
        val sysrootInclude = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/sysroot/usr/include")
        execOperations.exec {
            commandLine(
                clang.absolutePath,
                "-target",
                "bpfel",
                "-ffreestanding",
                "-std=c17",
                "-O2",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-I${sourceDir.absolutePath}",
                "-idirafter${sysrootInclude.absolutePath}",
                "-idirafter${sysrootInclude.resolve("aarch64-linux-android").absolutePath}",
                "-c",
                bpfSource.absolutePath,
                "-o",
                objectFile.absolutePath,
            )
        }
        val bytes = objectFile.readBytes()
        if (bytes.isEmpty()) {
            throw GradleException("Failed to compile embedded bpf2socks TC object")
        }
        val generated = workDir.resolve("embedded_bpf_object.c")
        generated.writeText(
            buildString {
                appendLine("#include <stddef.h>")
                appendLine("const unsigned char bpf2socks_embedded_bpf_object[] = {")
                bytes.asList().chunked(16).forEach { row ->
                    append("    ")
                    row.forEach { byte -> append("0x%02x,".format(byte.toInt() and 0xff)) }
                    appendLine()
                }
                appendLine("};")
                appendLine("const size_t bpf2socks_embedded_bpf_object_size = sizeof(bpf2socks_embedded_bpf_object);")
            },
        )
        return generated
    }

    private fun findNdkClang(ndkDir: File, target: Bpf2SocksAbiTarget): File {
        val hostTag = ndkHostTag()
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

    private fun ndkHostTag(): String = when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
        System.getProperty("os.name").contains("Mac", ignoreCase = true) -> "darwin-x86_64"
        else -> "linux-x86_64"
    }

    private fun findNdkDir(): File {
        listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT").forEach { name ->
            System.getenv(name)?.takeIf(String::isNotBlank)?.let { path -> return File(path) }
        }

        val localProperties = localPropertiesFile.orNull?.asFile
        if (localProperties != null && localProperties.exists()) {
            val properties = Properties()
            localProperties.inputStream().use(properties::load)
            properties.getProperty("ndk.dir")?.takeIf(String::isNotBlank)?.let { path -> return File(path) }
            properties.getProperty("sdk.dir")?.takeIf(String::isNotBlank)?.let { path ->
                File(path, "ndk").latestChildDirectoryForBpf2Socks()?.let { return it }
            }
        }

        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { name ->
            System.getenv(name)?.takeIf(String::isNotBlank)?.let { path ->
                File(path, "ndk").latestChildDirectoryForBpf2Socks()?.let { return it }
            }
        }

        throw GradleException("Android NDK not found. Set ndk.dir, ANDROID_NDK_HOME, or install an NDK under the Android SDK.")
    }

}

private fun File.latestChildDirectoryForBpf2Socks(): File? {
    return listFiles()
        ?.filter(File::isDirectory)
        ?.maxByOrNull { directory -> directory.name }
}

private enum class Bpf2SocksAbiTarget(
    val androidAbi: String,
    val clangTarget: String,
) {
    Arm64("arm64-v8a", "aarch64-linux-android"),
    Arm32("armeabi-v7a", "armv7a-linux-androideabi"),
    X86("x86", "i686-linux-android"),
    X64("x86_64", "x86_64-linux-android"),
}

private fun String.toBpf2SocksAbiTarget(): Bpf2SocksAbiTarget {
    return Bpf2SocksAbiTarget.entries.firstOrNull { target -> target.androidAbi == this }
        ?: throw GradleException("Unsupported bpf2socks ABI: $this")
}
