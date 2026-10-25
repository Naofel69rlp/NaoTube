/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Build from the repository root with:  ./gradlew -p desktop createDistributable
 */
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is distributed through JitPack
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "NaoTube"
