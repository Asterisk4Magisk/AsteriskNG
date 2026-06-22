plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<UpdateResourceFileAssetsTask>("updateResourceFileAssets") {
    xrayCoreVersion.set(ProjectConfig.XRAY_CORE_VERSION)
    hevSocks5TunnelVersion.set(ProjectConfig.HEV_SOCKS5_TUNNEL_VERSION)
    directCidrIpv4Url.set("https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute.txt")
    directCidrIpv6Url.set("https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute_v6.txt")
    xrayCoreFile.set(layout.projectDirectory.file("app/build/generated/xrayCoreJniLibs/arm64-v8a/libxray.so"))
    directCidrIpv4File.set(layout.projectDirectory.file("app/src/main/assets/direct-cidr-v4.txt"))
    directCidrIpv6File.set(layout.projectDirectory.file("app/src/main/assets/direct-cidr-v6.txt"))
    hevSocks5TunnelJniLibsDir.set(layout.projectDirectory.dir("app/build/generated/hevSocks5TunnelJniLibs"))
}
