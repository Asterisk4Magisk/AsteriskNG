// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

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
        exclusiveContent {
            forRepository {
                ivy {
                    name = "AndroidLibXrayLiteGitHubRelease"
                    url = uri("https://github.com/Asterisk4Magisk/AndroidLibXrayLite/releases/download")
                    patternLayout {
                        artifact("[revision]/[artifact].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeModule("com.github.Asterisk4Magisk", "libv2ray")
            }
        }
    }
}

include(":app")
include(":asteriskd")
include(":bpfmatcher")
include(":bpf2socks")
include(":hevtun")
