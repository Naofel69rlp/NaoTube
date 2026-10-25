/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

// Same version numbers as the Android app: every GitHub build is 1.0.<run number>.
val ciRunNumber: Int = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
val appVersion = "1.0.$ciRunNumber"
val updateRepo: String = providers.environmentVariable("GITHUB_REPOSITORY").orNull ?: "Naofel69rlp/NaoTube"

kotlin {
    jvmToolchain(21)
}

// ------------------------------------------------------------------ shared code & assets
// The YouTube engine, data models, SponsorBlock client and formatting helpers are the very same
// files as in the Android TV app; fonts, logos and icons too.
val syncSharedSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        include(
            "com/naotube/tv/core/Fmt.kt",
            "com/naotube/tv/core/NaoDownloader.kt",
            "com/naotube/tv/data/Models.kt",
            "com/naotube/tv/data/YouTubeRepository.kt",
            "com/naotube/tv/data/SponsorBlock.kt",
        )
    }
    into(layout.buildDirectory.dir("shared/kotlin"))
}

val syncSharedResources by tasks.registering(Sync::class) {
    from("../app/src/main/res/font") { into("fonts") }
    from("../app/src/main/res/drawable") {
        include("ic_*.xml")
        into("icons")
    }
    from("../app/src/main/res/drawable-nodpi") { into("images") }
    into(layout.buildDirectory.dir("shared/resources"))
}

val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val version = appVersion
    val repo = updateRepo
    inputs.property("version", version)
    inputs.property("repo", repo)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/naotube/desktop/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.naotube.desktop

            object BuildInfo {
                const val VERSION = "$version"
                const val UPDATE_REPO = "$repo"
            }
            """.trimIndent()
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(syncSharedSources)
    kotlin.srcDir(generateBuildInfo)
}

sourceSets.named("main") {
    resources.srcDir(syncSharedResources)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
    implementation("org.jetbrains.compose.ui:ui:1.12.2")
    implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
    implementation("org.jetbrains.compose.animation:animation:1.12.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // YouTube extraction engine (from NewPipe), same commit as the Android app
    implementation("com.github.TeamNewPipe:NewPipeExtractor:e8f89145a830b4d6cae32412007f24ea8b36c73a")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.json:json:20240303")
    implementation("org.jsoup:jsoup:1.23.2")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // Video playback: libVLC (bundled with the app) driven through vlcj
    implementation("uk.co.caprica:vlcj:4.12.1")
}

compose.desktop {
    application {
        mainClass = "com.naotube.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xss2m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "NaoTube"
            packageVersion = appVersion
            description = "NaoTube — YouTube sans publicité"
            vendor = "NaoTube"
            copyright = "GPL-3.0-or-later"
            includeAllModules = true
            // VLC (downloaded by the GitHub build into resources/windows/vlc) ships with the app.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                iconFile.set(project.file("packaging/naotube.ico"))
                menuGroup = "NaoTube"
                upgradeUuid = "8f3c2a51-6d4e-4b7a-9c0d-1e2f3a4b5c6d"
            }
        }
    }
}
