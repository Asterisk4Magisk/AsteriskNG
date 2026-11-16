import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.library)
}

val generatedJniLibsDir: Provider<Directory> = layout.buildDirectory.dir("generated/jniLibs")
val bpfMatcherSubmoduleDir = layout.projectDirectory.dir("src/main/native")

android {
    namespace = "bpfmatcher"
    buildToolsVersion = ProjectConfig.BUILD_TOOLS_VERSION
    compileSdk = ProjectConfig.TARGET_SDK
    ndkVersion = ProjectConfig.NDK_VERSION

    defaultConfig {
        minSdk = ProjectConfig.MIN_SDK
        ndk {
            abiFilters += ProjectConfig.SUPPORTED_ANDROID_ABIS
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
            variant.sources.jniLibs?.addStaticSourceDirectory("build/generated/jniLibs")
        }
    }

    lint {
        disable += "ChromeOsAbiSupport"
    }
}

val syncBpfMatcherVersion = tasks.register<SyncGitSubmoduleVersionTask>("syncBpfMatcherVersion") {
    submoduleVersion.set(ProjectConfig.BPF_MATCHER_VERSION)
    repositoryRootDirectory.set(rootProject.layout.projectDirectory)
    submoduleDirectory.set(bpfMatcherSubmoduleDir)
    submodulePath.set(bpfMatcherSubmoduleDir.asFile.relativeTo(rootProject.projectDir).invariantSeparatorsPath)
}

val buildBpfMatcher = tasks.register<BuildBpfMatcherTask>("buildBpfMatcher") {
    dependsOn(syncBpfMatcherVersion)
    sourceFile.set(bpfMatcherSubmoduleDir.file("bpf-matcher.c"))
    outputDirectory.set(generatedJniLibsDir)
    minSdk.set(ProjectConfig.MIN_SDK)
    ndkVersion.set(ProjectConfig.NDK_VERSION)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    targetAbis.set(ProjectConfig.SUPPORTED_ANDROID_ABIS)
}

tasks.named("preBuild") {
    dependsOn(buildBpfMatcher)
}
