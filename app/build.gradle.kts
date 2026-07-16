@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import com.google.protobuf.gradle.id
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

val generatedSrcDir: Provider<Directory> = layout.buildDirectory.dir("generated/projectInfo")
val generatedXrayCoreJniLibsDir: Provider<Directory> = layout.buildDirectory.dir("generated/xrayCoreJniLibs")
val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val protobufVersion = versionCatalog.findVersion("protobuf").get().requiredVersion
val grpcVersion = versionCatalog.findVersion("grpc").get().requiredVersion

android {
    namespace = "app"
    compileSdk = ProjectConfig.TARGET_SDK

    defaultConfig {
        applicationId = ProjectConfig.PACKAGE_NAME
        minSdk = ProjectConfig.MIN_SDK
        targetSdk = ProjectConfig.TARGET_SDK
        versionCode = getGitVersionCode()
        versionName = ProjectConfig.VERSION_NAME
    }

    androidResources {
        localeFilters += listOf("en", "zh-rCN", "ru")
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        compose = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*ProjectConfig.SUPPORTED_ANDROID_ABIS.toTypedArray())
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/versions/**",
            )
        }
    }

    lint {
        disable += setOf(
            "ChromeOsAbiSupport",
            "IconLauncherShape",
        )
    }
}

tasks.named("preBuild") {
    dependsOn(rootProject.tasks.named("updateResourceFileAssets"))
}

dependencies {
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    //noinspection UseTomlInstead
    implementation("com.github.2dust:libv2ray:${ProjectConfig.ANDROID_LIB_XRAY_LITE_VERSION}@aar")
    implementation(dependencies.project(":setuidgid"))
    implementation(dependencies.project(":asteriskd"))
    implementation(dependencies.project(":bpfmatcher"))
    implementation(dependencies.project(":bpf2socks"))
    implementation(dependencies.project(":hevtun"))
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.ktor.http)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libsu.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3.ui)
    implementation(libs.miuix.preference)
    implementation(libs.protobuf.javalite)
    implementation(libs.reorderable)
    implementation(libs.snakeyaml.engine)
    implementation(libs.sora.editor)
    implementation(libs.zxing.android.embedded)
    compileOnly(libs.javax.annotation.api)
    ksp(libs.androidx.room.compiler)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                id("java") {
                    option("lite")
                }
            }
            plugins {
                id("grpc") {
                    option("lite")
                }
            }
        }
    }
}

val generateProjectInfo = tasks.register<GenerateProjectInfoTask>("generateProjectInfo") {
    description = "Generate ProjectInfo object for the app"
    packageName.set("app")
    projectName.set(ProjectConfig.PROJECT_NAME)
    versionName.set(ProjectConfig.VERSION_NAME)
    versionCode.set(getGitVersionCode())
    xrayCoreVersion.set(ProjectConfig.XRAY_CORE_VERSION)
    androidLibXrayLiteVersion.set(ProjectConfig.ANDROID_LIB_XRAY_LITE_VERSION)
    hevSocks5TunnelVersion.set(ProjectConfig.HEV_SOCKS5_TUNNEL_VERSION)
    outputDirectory.set(generatedSrcDir.map { it.dir("kotlin") })
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
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateProjectInfo) { task ->
            task.outputDirectory
        }
        variant.sources.assets?.addStaticSourceDirectory("build/generated/resourceFileAssets")
        variant.sources.jniLibs?.addStaticSourceDirectory("build/generated/xrayCoreJniLibs")
    }
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
    dependsOn(generateProjectInfo)
}

val aboutLibrariesJsonFile = layout.projectDirectory.file("src/main/assets/aboutlibraries.json")

val updateAboutLibrariesJson = tasks.register<GenerateAboutLibrariesJsonTask>("updateAboutLibrariesJson") {
    group = "documentation"
    description = "Update files/aboutlibraries.json from current app dependency metadata."
    outputFile.set(aboutLibrariesJsonFile)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    mustRunAfter(updateAboutLibrariesJson)
    if (!aboutLibrariesJsonFile.asFile.exists()) {
        dependsOn(updateAboutLibrariesJson)
    }
}
