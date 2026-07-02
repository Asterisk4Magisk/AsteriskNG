@file:Suppress("UnstableApiUsage")

rootProject.name = "AsteriskNG"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://jitpack.io")
        ivy {
            name = "AndroidLibXrayLiteGitHubRelease"
            url = uri("https://github.com/2dust/AndroidLibXrayLite/releases/download")
            patternLayout {
                artifact("[revision]/[artifact].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("com.github.2dust", "libv2ray")
            }
        }
    }
}

include(":app")
include(":setuidgid")
include(":ipv6disabler")
include(":bpfmatcher")
include(":bpf2socks")
include(":hevtun")
