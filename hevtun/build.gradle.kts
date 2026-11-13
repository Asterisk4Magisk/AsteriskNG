import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.library)
}

private val hevSocks5TunnelSubmoduleDir = layout.projectDirectory.dir("src/main/jni/hev-socks5-tunnel")
private val generatedJniLibsDir: Provider<Directory> = layout.buildDirectory.dir("generated/jniLibs")
private val rootLocalPropertiesFile = rootProject.layout.projectDirectory.file("local.properties")
private val hevTunArtifacts = listOf(
    HevTunArtifactSpec(
        name = "jni",
        taskSuffix = "Jni",
        outputFileName = "libhev-socks5-tunnel.so",
        displayName = "JNI library",
    ),
    HevTunArtifactSpec(
        name = "cli",
        taskSuffix = "Cli",
        outputFileName = "libhev-socks5-tunnel-cli.so",
        displayName = "CLI executable",
    ),
)

android {
    namespace = "hevtun"
    compileSdk = ProjectConfig.TARGET_SDK

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

val syncHevSocks5TunnelVersion = tasks.register<SyncGitSubmoduleVersionTask>("syncHevSocks5TunnelVersion") {
    submoduleVersion.set(ProjectConfig.HEV_SOCKS5_TUNNEL_VERSION)
    repositoryRootDirectory.set(rootProject.layout.projectDirectory)
    submoduleDirectory.set(hevSocks5TunnelSubmoduleDir)
    submodulePath.set(hevSocks5TunnelSubmoduleDir.asFile.relativeTo(rootProject.projectDir).invariantSeparatorsPath)
}

private val buildHevTunAssetTasks = ProjectConfig.SUPPORTED_ANDROID_ABIS.flatMap { abi ->
    hevTunArtifacts.map { artifact ->
        tasks.register<BuildHevTunTask>("buildHevTun${abi.capitalizedForTask()}${artifact.taskSuffix}") {
            description = "Build Hev TUN ${artifact.displayName} for Android $abi."
            dependsOn(syncHevSocks5TunnelVersion)
            sourceDirectory.set(hevSocks5TunnelSubmoduleDir)
            patchDirectory.set(layout.projectDirectory.dir("src/main/patches"))
            outputFile.set(generatedJniLibsDir.map { directory ->
                directory.file("$abi/${artifact.outputFileName}")
            })
            if (rootLocalPropertiesFile.asFile.exists()) {
                localPropertiesFile.set(rootLocalPropertiesFile)
            }
            minSdk.set(ProjectConfig.MIN_SDK)
            androidAbi.set(abi)
            this.artifact.set(artifact.name)
        }
    }
}

val buildHevTun = tasks.register("buildHevTun") {
    group = "build"
    description = "Build hev-socks5-tunnel JNI libraries and CLI executables for Android."
    dependsOn(buildHevTunAssetTasks)
}

tasks.named("preBuild") {
    dependsOn(buildHevTun)
}

private data class HevTunArtifactSpec(
    val name: String,
    val taskSuffix: String,
    val outputFileName: String,
    val displayName: String,
)

private fun String.capitalizedForTask(): String {
    return split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotEmpty)
        .joinToString("") { part ->
            part.replaceFirstChar { char -> char.uppercase() }
        }
}
