// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

object ProjectConfig {
    const val JVM_VERSION = 26
    const val PROJECT_NAME = "AsteriskNG"
    const val VERSION_NAME = "1.3.6-dev"
    const val PACKAGE_NAME = "org.asterisk.zcc.ang"
    const val XRAY_CORE_VERSION = "v26.6.27"
    const val ANDROID_LIB_XRAY_LITE_VERSION = "v26.6.27"
    const val HEV_SOCKS5_TUNNEL_VERSION = "2.15.0"
    const val TARGET_SDK = 37
    const val MIN_SDK = 24
    val SUPPORTED_ANDROID_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
}

fun org.gradle.api.Project.getGitVersionCode(): Int {
    return providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

abstract class GenerateProjectInfoTask : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val projectName: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val xrayCoreVersion: Property<String>

    @get:Input
    abstract val androidLibXrayLiteVersion: Property<String>

    @get:Input
    abstract val hevSocks5TunnelVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val packagePath = packageName.get().replace('.', '/')
        val file = outputDirectory.file("$packagePath/ProjectInfo.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package ${packageName.get()}

            object ProjectInfo {
                const val PROJECT_NAME = "${projectName.get()}"
                const val VERSION_NAME = "${versionName.get()}"
                const val VERSION_CODE = ${versionCode.get()}
                const val XRAY_CORE_VERSION = "${xrayCoreVersion.get()}"
                const val ANDROID_LIB_XRAY_LITE_VERSION = "${androidLibXrayLiteVersion.get()}"
                const val HEV_SOCKS5_TUNNEL_VERSION = "${hevSocks5TunnelVersion.get()}"
            }
            """.trimIndent(),
        )
    }
}
