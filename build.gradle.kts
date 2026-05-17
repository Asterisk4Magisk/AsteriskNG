plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<DownloadAndroidLibXrayLiteAarTask>("downloadAndroidLibXrayLiteAar") {
    androidLibXrayLiteVersion.set(ProjectConfig.ANDROID_LIB_XRAY_LITE_VERSION)
    outputAar.set(layout.projectDirectory.file("app/libs/AndroidLibXrayLite.aar"))
}

tasks.register<UpdateResourceFileAssetsTask>("updateResourceFileAssets") {
    xrayCoreVersion.set(ProjectConfig.XRAY_CORE_VERSION)
    xrayCoreFile.set(layout.projectDirectory.file("app/build/generated/xrayCoreJniLibs/arm64-v8a/libxray.so"))
}
