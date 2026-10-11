// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import java.io.File

plugins {
    alias(libs.plugins.android.library)
}

val enhanceSubmoduleDir = layout.projectDirectory.dir("src/main/module")
val sourceOverride = providers.gradleProperty("enhanceSourceDir").orNull?.let(::File)
val sourceDir = sourceOverride ?: enhanceSubmoduleDir.asFile
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val generatedModuleAssetsDir = layout.buildDirectory.dir("generated/moduleAssets")
val generatedModuleDir = layout.buildDirectory.dir("generated/magiskModule")

android {
    namespace = "enhance"
    compileSdk = ProjectConfig.TARGET_SDK

    defaultConfig {
        minSdk = ProjectConfig.MIN_SDK
        ndk {
            abiFilters += ProjectConfig.SUPPORTED_ANDROID_ABIS
        }
    }

    lint {
        disable += "ChromeOsAbiSupport"
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enableAndroidTest = false
        (variant as? HasHostTestsBuilder)
            ?.hostTests
            ?.get(HostTestBuilder.UNIT_TEST_TYPE)
            ?.enable = false
    }
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(
            tasks.named<PrepareEnhanceModuleTask>("prepareEnhanceModule"),
        ) { task -> task.outputDirectory }
        variant.sources.assets?.addGeneratedSourceDirectory(
            tasks.named<PrepareEnhanceModuleTask>("prepareEnhanceModule"),
        ) { task -> task.generatedAssetsDir }
    }
}

val syncEnhanceVersion = tasks.register<SyncGitSubmoduleVersionTask>("syncEnhanceVersion") {
    submoduleVersion.set(ProjectConfig.ENHANCE_VERSION)
    repositoryRootDirectory.set(rootProject.layout.projectDirectory)
    submoduleDirectory.set(enhanceSubmoduleDir)
    submodulePath.set(enhanceSubmoduleDir.asFile.relativeTo(rootProject.projectDir).invariantSeparatorsPath)
    val useSubmodule = sourceOverride == null
    onlyIf { useSubmodule }
}

val buildEnhance = tasks.register<BuildEnhanceTask>("buildEnhance") {
    dependsOn(syncEnhanceVersion)
    sourceDirectory.set(sourceDir)
    sourceFiles.from(fileTree(sourceDir) {
        include("native/**", "third_party/**", "tools/build.py", "module/module.prop")
    })
    outputDirectory.set(generatedJniLibsDir)
    workDirectory.set(layout.buildDirectory.dir("intermediates/native"))
    rootProject.layout.projectDirectory.file("local.properties")
        .takeIf { it.asFile.exists() }
        ?.let(localPropertiesFile::set)
    minSdk.set(ProjectConfig.MIN_SDK)
    targetAbis.set(ProjectConfig.SUPPORTED_ANDROID_ABIS)
}

val prepareEnhanceModule = tasks.register<PrepareEnhanceModuleTask>("prepareEnhanceModule") {
    dependsOn(buildEnhance)
    moduleDirectory.set(sourceDir.resolve("module"))
    licenseFile.set(sourceDir.resolve("LICENSE"))
    nativeLibrariesDirectory.set(generatedJniLibsDir)
    outputDirectory.set(generatedModuleDir)
    generatedAssetsDir.set(generatedModuleAssetsDir)
}

tasks.named("preBuild") {
    dependsOn(buildEnhance, prepareEnhanceModule)
}

