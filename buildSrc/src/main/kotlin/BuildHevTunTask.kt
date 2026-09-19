// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.Properties
import javax.inject.Inject

abstract class BuildHevTunTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Input
    abstract val androidAbi: Property<String>

    @get:Input
    abstract val artifact: Property<String>

    init {
        group = "build"
        description = "Build hev-socks5-tunnel JNI library and CLI executable for Android."
    }

    @TaskAction
    fun build() {
        val artifact = HevTunArtifact.fromName(artifact.get())
        val abi = androidAbi.get()
        val ndkBuild = findNdkBuild(findNdkDir())
        val sourceDir = prepareBuildSource(sourceDirectory.get().asFile)
        val finalOutput = outputFile.get().asFile
        val outputDir = finalOutput.parentFile
        val ndkLibsOutDir = temporaryDir.resolve("libs")
        val projectDir = temporaryDir.resolve("ndk-project")
        val jniDir = projectDir.resolve("jni")
        val appBuildScript = jniDir.resolve("Android.mk")

        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
        jniDir.mkdirs()
        writeGeneratedAndroidMk(appBuildScript, sourceDir)
        outputDir.mkdirs()
        ndkLibsOutDir.mkdirs()

        execOperations.exec {
            workingDir = projectDir
            commandLine(
                ndkBuild.absolutePath,
                "NDK_PROJECT_PATH=${projectDir.absolutePath}",
                "APP_BUILD_SCRIPT=${appBuildScript.absolutePath}",
                "APP_ABI=$abi",
                "APP_MODULES=${artifact.moduleName}",
                "APP_PLATFORM=android-${minSdk.get()}",
                "NDK_LIBS_OUT=${ndkLibsOutDir.absolutePath}",
                "NDK_OUT=${temporaryDir.resolve("obj").absolutePath}",
                "APP_CFLAGS=-O3 -DPKGNAME=engine/vpn/hevtun -DCLSNAME=HevTunNative",
                "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu",
            )
        }

        val builtOutput = ndkLibsOutDir.resolve("$abi/${artifact.builtFileName}")
        if (!builtOutput.exists() || builtOutput.length() <= 0) {
            throw GradleException("Failed to build Hev TUN ${artifact.displayName}: ${builtOutput.absolutePath}")
        }
        if (builtOutput != finalOutput) {
            if (finalOutput.exists() && !finalOutput.delete()) {
                throw GradleException("Failed to replace ${finalOutput.absolutePath}")
            }
            if (!builtOutput.renameTo(finalOutput)) {
                builtOutput.inputStream().use { input ->
                    finalOutput.outputStream().use { output -> input.copyTo(output) }
                }
                builtOutput.delete()
            }
        }
        if (!finalOutput.exists() || finalOutput.length() <= 0) {
            throw GradleException("Failed to package Hev TUN ${artifact.displayName}: ${finalOutput.absolutePath}")
        }
    }

    private fun writeGeneratedAndroidMk(target: File, sourceDir: File) {
        val sourcePath = sourceDir.absolutePath.replace('\\', '/')
        target.writeText(
            """
            HEV_TUNNEL_PATH := $sourcePath

            include $(HEV_TUNNEL_PATH)/Android.mk

            LOCAL_PATH := $(HEV_TUNNEL_PATH)
            SRCDIR := $(LOCAL_PATH)/src

            include $(CLEAR_VARS)
            include $(LOCAL_PATH)/build.mk
            LOCAL_MODULE := hev-socks5-tunnel-cli
            LOCAL_SRC_FILES := $(filter-out src/hev-jni.c,$(patsubst $(SRCDIR)/%,src/%,$(SRCFILES)))
            LOCAL_C_INCLUDES := \
                $(LOCAL_PATH)/src \
                $(LOCAL_PATH)/src/misc \
                $(LOCAL_PATH)/src/core/include \
                $(LOCAL_PATH)/third-part/yaml/include \
                $(LOCAL_PATH)/third-part/lwip/src/include \
                $(LOCAL_PATH)/third-part/lwip/src/ports/include \
                $(LOCAL_PATH)/third-part/hev-task-system/include
            LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED
            LOCAL_CFLAGS += $(VERSION_CFLAGS)
            ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
            LOCAL_CFLAGS += -mfpu=neon
            endif
            LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
            LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
            LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
            include $(BUILD_EXECUTABLE)
            """.trimIndent(),
        )
    }

    private fun prepareBuildSource(sourceDir: File): File {
        val patchedSourceDir = temporaryDir.resolve("patched-source")
        if (patchedSourceDir.exists()) {
            patchedSourceDir.deleteRecursively()
        }
        sourceDir.copyRecursively(patchedSourceDir, overwrite = true)
        replaceSymlinkPlaceholderFiles(patchedSourceDir)
        return patchedSourceDir
    }

    private fun replaceSymlinkPlaceholderFiles(sourceDir: File) {
        val sourceRoot = sourceDir.canonicalFile
        sourceDir.walkTopDown()
            .filter { file -> file.isFile && file.length() in 1..256 }
            .forEach { file ->
                val targetPath = runCatching { file.readText().trim() }.getOrNull()
                    ?.takeIf { value -> value.startsWith("../") && !value.contains('\n') }
                    ?: return@forEach
                val target = file.parentFile.resolve(targetPath).canonicalFile
                if (target.isFile && target.isInsideDirectory(sourceRoot)) {
                    target.copyTo(file, overwrite = true)
                }
            }
    }

    private fun File.isInsideDirectory(directory: File): Boolean {
        var current: File? = canonicalFile
        while (current != null) {
            if (current == directory) {
                return true
            }
            current = current.parentFile
        }
        return false
    }

    private fun findNdkBuild(ndkDir: File): File {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "ndk-build.cmd"
        } else {
            "ndk-build"
        }
        val ndkBuild = ndkDir.resolve(executable)
        if (!ndkBuild.exists()) {
            throw GradleException("Android NDK ndk-build not found: ${ndkBuild.absolutePath}")
        }
        return ndkBuild
    }

    private fun findNdkDir(): File {
        listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT").forEach { name ->
            System.getenv(name)?.takeIf(String::isNotBlank)?.let { return File(it) }
        }

        val localProperties = localPropertiesFile.orNull?.asFile
        if (localProperties != null && localProperties.exists()) {
            val properties = Properties()
            localProperties.inputStream().use(properties::load)
            properties.getProperty("ndk.dir")?.takeIf(String::isNotBlank)?.let { return File(it) }
            properties.getProperty("sdk.dir")?.takeIf(String::isNotBlank)?.let { path ->
                File(path, "ndk").latestChildDirectoryForHevTun()?.let { return it }
            }
        }

        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { name ->
            System.getenv(name)?.takeIf(String::isNotBlank)?.let { path ->
                File(path, "ndk").latestChildDirectoryForHevTun()?.let { return it }
            }
        }

        throw GradleException("Android NDK not found. Set ndk.dir, ANDROID_NDK_HOME, or install an NDK under the Android SDK.")
    }
}

private fun File.latestChildDirectoryForHevTun(): File? {
    return listFiles()
        ?.filter(File::isDirectory)
        ?.maxByOrNull { directory -> directory.name }
}

private enum class HevTunArtifact(
    val artifactName: String,
    val moduleName: String,
    val builtFileName: String,
    val displayName: String,
) {
    JniLibrary(
        artifactName = "jni",
        moduleName = "hev-socks5-tunnel",
        builtFileName = "libhev-socks5-tunnel.so",
        displayName = "JNI library",
    ),
    CliExecutable(
        artifactName = "cli",
        moduleName = "hev-socks5-tunnel-cli",
        builtFileName = "hev-socks5-tunnel-cli",
        displayName = "CLI executable",
    );

    companion object {
        fun fromName(name: String): HevTunArtifact {
            return entries.firstOrNull { artifact -> artifact.artifactName == name }
                ?: throw GradleException("Unsupported Hev TUN artifact: $name")
        }
    }
}
