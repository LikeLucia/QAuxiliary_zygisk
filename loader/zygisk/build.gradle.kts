plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.qauxv.loader.zygisk"

    compileSdk {
        // "36.1" -> major=36, minor=1
        version = release(Version.compileSdkVersion.substringBefore('.').toInt()) {
            minorApiLevel = Version.compileSdkVersion.substringAfter('.').toIntOrNull()
        }
    }

    defaultConfig {
        minSdk = Version.minSdk

        buildConfigField("String", "VERSION_NAME", "\"${Common.getBuildVersionName(rootProject)}\"")
        buildConfigField("int", "VERSION_CODE", "${Common.getBuildVersionCode(rootProject)}")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
    // DexMaker: used to generate the bridge/backup method pair for every hooked member.
    // Note this is com.linkedin.dexmaker, NOT the dalvik-dx artifact, which ships the dx
    // toolchain but no DexMaker.
    implementation(libs.dexmaker)
    // hookapi is the shared abstraction implemented by every loader (Xposed, LSPosed, Zygisk)
    implementation(projects.loader.hookapi)
    // ModuleLoader drives the common startup chain (UnifiedEntryPoint -> StartupAgent)
    implementation(projects.loader.sbl)
}
