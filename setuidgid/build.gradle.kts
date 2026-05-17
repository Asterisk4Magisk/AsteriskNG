plugins {
    alias(libs.plugins.android.library)
}

val generatedJniLibsDir: Provider<Directory> = layout.buildDirectory.dir("generated/jniLibs")

android {
    namespace = "setuidgid"
    compileSdk = ProjectConfig.TARGET_SDK

    defaultConfig {
        minSdk = ProjectConfig.MIN_SDK
        ndk {
            abiFilters += ProjectConfig.SUPPORTED_ANDROID_ABIS
        }
    }

    androidComponents {
        onVariants { variant ->
            variant.sources.jniLibs?.addStaticSourceDirectory("build/generated/jniLibs")
        }
    }
}

val buildSetuidgid by tasks.registering(BuildSetuidgidTask::class) {
    sourceFile.set(layout.projectDirectory.file("src/main/native/setuidgid.c"))
    outputDirectory.set(generatedJniLibsDir)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    minSdk.set(ProjectConfig.MIN_SDK)
    targetAbis.set(ProjectConfig.SUPPORTED_ANDROID_ABIS)
}

tasks.named("preBuild") {
    dependsOn(buildSetuidgid)
}
