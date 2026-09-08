plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<UpdateResourceFileAssetsTask>("updateResourceFileAssets") {
    xrayCoreVersion.set(ProjectConfig.XRAY_CORE_VERSION)
    xrayCoreJniLibsDir.set(layout.projectDirectory.dir("app/build/generated/xrayCoreJniLibs"))
    resourceFileAssetsDir.set(layout.projectDirectory.dir("app/build/generated/resourceFileAssets"))
}
