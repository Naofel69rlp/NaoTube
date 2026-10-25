/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Every GitHub Actions build gets a new, increasing version (1.0.<run>), which is what the
// in-app updater compares against the latest GitHub release.
val ciRunNumber: Int = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
val updateRepo: String = providers.environmentVariable("GITHUB_REPOSITORY").orNull ?: "Naofel69rlp/NaoTube"

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.naotube.tv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.naotube.tv"
        minSdk {
            version = release(23)
        }
        targetSdk {
            version = release(35)
        }
        versionCode = ciRunNumber
        versionName = "1.0.$ciRunNumber"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        create("release") {
            // A fixed key so that every new build installs over the previous one.
            storeFile = file("naotube-release.jks")
            storePassword = "naotube-release-2026"
            keyAlias = "naotube"
            keyPassword = "naotube-release-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/README.md",
                "META-INF/CHANGES",
                "META-INF/COPYRIGHT",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    // YouTube extraction engine (from NewPipe)
    implementation(libs.newpipe.extractor)

    // AndroidX + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)

    // Video player
    implementation(libs.media3.player)
    implementation(libs.media3.dash)
    implementation(libs.media3.hls)
    implementation(libs.media3.ui)

    // Network, images, async, storage
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
