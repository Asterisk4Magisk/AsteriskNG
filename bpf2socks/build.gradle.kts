import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.library)
}

val generatedJniLibsDir: Provider<Directory> = layout.buildDirectory.dir("generated/jniLibs")
val bpf2SocksSubmoduleDir = layout.projectDirectory.dir("src/main/native")

android {
    namespace = "bpf2socks"
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

val syncBpf2SocksVersion = tasks.register<SyncGitSubmoduleVersionTask>("syncBpf2SocksVersion") {
    submoduleVersion.set(ProjectConfig.BPF2SOCKS_VERSION)
    repositoryRootDirectory.set(rootProject.layout.projectDirectory)
    submoduleDirectory.set(bpf2SocksSubmoduleDir)
    submodulePath.set(bpf2SocksSubmoduleDir.asFile.relativeTo(rootProject.projectDir).invariantSeparatorsPath)
}

val buildBpf2Socks = tasks.register<BuildBpf2SocksTask>("buildBpf2Socks") {
    dependsOn(syncBpf2SocksVersion)
    sourceDirectory.set(bpf2SocksSubmoduleDir)
    outputDirectory.set(generatedJniLibsDir)
    minSdk.set(ProjectConfig.MIN_SDK)
    ndkVersion.set(ProjectConfig.NDK_VERSION)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    targetAbis.set(ProjectConfig.SUPPORTED_ANDROID_ABIS)
}

tasks.named("preBuild") {
    dependsOn(buildBpf2Socks)
}
