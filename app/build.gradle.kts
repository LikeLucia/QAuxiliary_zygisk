/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

import android.databinding.tool.ext.capitalizeUS
import com.android.build.gradle.internal.dsl.SigningConfig
import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.android.tools.build.apkzlib.sign.SigningExtension
import com.android.tools.build.apkzlib.sign.SigningOptions
import com.android.tools.build.apkzlib.zfile.ZFiles
import com.android.tools.build.apkzlib.zip.AlignmentRule
import com.android.tools.build.apkzlib.zip.CompressionMethod
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.Properties
import java.util.UUID

plugins {
    id("build-logic.android.application")
    alias(libs.plugins.changelog)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.serialization)
    alias(libs.plugins.aboutlibraries)
}

// ------ buildscript config ------

val buildAllAbiForDebug = Version.getLocalProperty(project, "qauxv.override.forceallabi")
    ?.toBoolean() ?: false
val isNewXposedApiEnabled = Version.getLocalProperty(project, "qauxv.override.newxposedapi")
    ?.toBoolean() ?: true
val isNativeFullDebugMode = Version.getLocalProperty(project, "qauxv.override.nativefulldebug")
    ?.toBoolean() ?: false

val currentBuildUuid = UUID.randomUUID().toString()
println("Current build ID is $currentBuildUuid")

val ccacheExecutablePath = Common.findInPath("ccache")

if (ccacheExecutablePath != null) {
    println("Found ccache at $ccacheExecutablePath")
} else {
    println("No ccache found.")
}

fun getSignatureKeyDigest(signConfig: SigningConfig?): String? {
    val key1: String? = if (signConfig?.storeFile != null) {
        // extract certificate digest
        val key = signConfig.storeFile
        val keyStore = KeyStore.getInstance(signConfig.storeType ?: KeyStore.getDefaultType())
        FileInputStream(key!!).use {
            keyStore.load(it, signConfig.storePassword!!.toCharArray())
        }
        val cert = keyStore.getCertificate(signConfig.keyAlias!!)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(cert.encoded)
        digest.joinToString("") { "%02X".format(it) }
    } else null
    val key2: String? = Version.getLocalProperty(project, "qauxv.signature.md5digest")
        ?.uppercase(Locale.ROOT)?.ifEmpty { null }
    // check if key1 and key2 are the same
    if (key1 != null && key2 != null && key1 != key2) {
        error(
            "The signature key digest in the signing config and local.properties are different, " +
                "got $key1 and $key2, please make sure they are the same."
        )
    }
    return (key1 ?: key2)?.also {
        check(it.matches(Regex("[0-9A-F]{32}"))) {
            "Invalid signature key digest: $it"
        }
    }
}

android {
    namespace = "io.github.qauxv"
    ndkVersion = Version.getNdkVersion(rootProject)
    defaultConfig {
        applicationId = "io.github.qauxv"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_UUID", "\"$currentBuildUuid\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")

        targetSdk = Version.targetSdk
        versionCode = Common.getBuildVersionCode(rootProject)
        versionName = Common.getBuildVersionName(rootProject)
        resourceConfigurations += listOf("zh", "en")

        externalNativeBuild {
            cmake {
                ccacheExecutablePath?.let {
                    // requires ccache 4.8+, multiple options are separated by ';'
                    // hash_dir is only useful when "-g" debug info across multiple copy of the same project
                    // disable it to improve cache hit rate
                    val ccacheOptions = "hash_dir=false"
                    arguments += listOf(
                        "-DCMAKE_C_COMPILER_LAUNCHER=$it;$ccacheOptions",
                        "-DCMAKE_CXX_COMPILER_LAUNCHER=$it;$ccacheOptions",
                        "-DNDK_CCACHE=$it",
                        "-DANDROID_CCACHE=$it",
                    )
                }
                Version.getNinjaPathOrNull(rootProject)?.let {
                    arguments += "-DCMAKE_MAKE_PROGRAM=$it"
                }
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                val flags = arrayOf(
                    "-Qunused-arguments",
                    "-fno-rtti",
                    "-fvisibility=protected",
                    "-fvisibility-inlines-hidden",
                    "-fno-omit-frame-pointer",
                    "-Wno-unused-value",
                    "-Wno-unused-variable",
                    "-Wno-unused-command-line-argument",
                    "-DMMKV_DISABLE_CRYPT",
                )
                // do not add -std=c++20 here, it should be added in the CMakeLists.txt where each module is defined
                // some modules uses features that are REMOVED or deprecated in C++20
                cppFlags(*flags)
                cFlags("-std=c18", *flags)
                targets += "qauxv-core0"
            }
        }

        ndk.debugSymbolLevel = "FULL"
    }

    sourceSets {
        configureEach {
            kotlin.directories += "$buildDir/generated/ksp/$name/kotlin/"
        }
        named("main") {
            kotlin.directories += "$rootDir/libs/ezxhelper/src/main/java"
        }
    }

    externalNativeBuild {
        cmake {
            path = File(projectDir, "src/main/cpp/CMakeLists.txt")
            version = Version.getCMakeVersion(project)
        }
    }
    buildTypes {
        val signatureDigest: String? = getSignatureKeyDigest(signingConfigs.findByName("release"))
        if (signatureDigest != null) {
            println("Signature Digest: $signatureDigest")
        } else {
            println("No Signature Digest Configured")
        }
        getByName("release") {
            //noinspection NotShrinkingResources
            isShrinkResources = false
            isMinifyEnabled = false
            proguardFiles("proguard-rules.pro")
            val ltoCacheFlags = listOf(
                "-flto=thin",
                "-Wl,--thinlto-cache-policy,cache_size_bytes=300m",
                "-Wl,--thinlto-cache-dir=${buildDir.absolutePath}/.lto-cache",
            )
            var releaseFlags = arrayOf(
                "-ffunction-sections",
                "-fdata-sections",
                "-Wl,--gc-sections",
                "-O3",
                "-Wl,--exclude-libs,ALL",
                "-DNDEBUG",
            )
            if (signatureDigest != null) {
                releaseFlags += "-DMODULE_SIGNATURE=$signatureDigest"
            }
            externalNativeBuild.cmake {
                arguments += "-DQAUXV_VERSION=${defaultConfig.versionName}"
                cFlags += releaseFlags
                cppFlags += releaseFlags
                cFlags += ltoCacheFlags
                cppFlags += ltoCacheFlags
            }
        }
        getByName("debug") {
            ndk {
                if (isNativeFullDebugMode) {
                    isJniDebuggable = true
                } else {
                    if (!buildAllAbiForDebug) {
                        @Suppress("ChromeOsAbiSupport")
                        abiFilters += arrayOf("arm64-v8a", "armeabi-v7a")
                    }
                }
            }
            //noinspection NotShrinkingResources
            isShrinkResources = false
            isMinifyEnabled = false
            isCrunchPngs = false
            proguardFiles("proguard-rules.pro")
            var debugFlags = arrayOf<String>(
//                "-DTEST_SIGNATURE",
            )
            if (signatureDigest != null) {
                debugFlags += "-DMODULE_SIGNATURE=$signatureDigest"
            }
            externalNativeBuild.cmake {
                arguments.addAll(
                    arrayOf(
                        "-DQAUXV_VERSION=${Version.versionName}.debug",
                    )
                )
                arguments.addAll(
                    if (isNativeFullDebugMode) arrayOf(
                        "-DCMAKE_CXX_FLAGS_DEBUG=-O0",
                        "-DCMAKE_C_FLAGS_DEBUG=-O0",
                    )
                    else arrayOf(
                        "-DCMAKE_CXX_FLAGS_DEBUG=-Og",
                        "-DCMAKE_C_FLAGS_DEBUG=-Og",
                    )
                )
                cFlags += debugFlags
                cppFlags += debugFlags
            }
        }
    }
    androidResources {
        additionalParameters += arrayOf(
            "--allow-reserved-package-id",
            "--package-id", "0x39"
        )
    }
    packaging {
        // libxposed API uses META-INF/xposed
        resources.excludes.addAll(
            arrayOf(
                "kotlin/**",
                "**.bin",
                "kotlin-tooling-metadata.json"
            )
        )
        if (!isNewXposedApiEnabled) {
            resources.excludes.add("META-INF/xposed/**")
        }
        // The Zygisk injector is compiled by CMake but must NOT be shipped inside the APK's
        // lib/ directory: it is only ever loaded by the zygote. The Gradle task
        // prepareZygiskModule<Variant> stages it as zygisk/arm64-v8a.so in the module instead.
        jniLibs.excludes += "**/libqauxv-zygisk.so"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }
    lint {
        checkDependencies = true
    }
    applicationVariants.all {
        val variantCapped = name.capitalizeUS()
        tasks.findByName("lintVitalAnalyze${variantCapped}")?.dependsOn(mergeAssetsProvider)
        tasks.findByName("generate${variantCapped}LintVitalReportModel")?.dependsOn(mergeAssetsProvider)
        mergeAssetsProvider.dependsOn(generateEulaAndPrivacy)
    }

    if (isNativeFullDebugMode) {
        packagingOptions.jniLibs {
            // be aware that some SIGSEGVs and SIGBUSes are only reproducible with "useLegacyPackaging = false"
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }
    // not use embedded dex
    packagingOptions.dex.useLegacyPackaging = true

    /**
     * There's some issues with the lint of AGP [8.9.x-8.11.1], causing the ':app:lintVitalAnalyzeRelease' task to fail.
     * Unexpected failure during lint analysis (this is a bug in lint or one of the libraries it depends on)
     * Message: Unexpected failure during lint analysis (this is a bug in lint or one of the libraries it depends on)
     * Message: Unexpected failure during lint analysis of QSecO3AddRiskRequestMitigation.kt (this is a bug in lint or one of the libraries it depends on)
     * Message: Incorrect type 'com/tencent/mobileqq/profilecard/base/framework/impl/AbsComponent' (JDK_24)
     * The crash seems to involve the detector \\\`com.android.tools.lint.checks.PrivateApiDetector\\\`.
     * You can try disabling it with something like this:
     */
    lint {
        disable += arrayOf("BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            listOf(
                "-Xno-call-assertions",
                "-Xno-receiver-assertions",
                "-Xno-param-assertions",
            )
        )
    }
}

dependencies {
    // loader
    compileOnly(projects.loader.hookapi)
    runtimeOnly(projects.loader.sbl)
    // Zygisk loader: its classes end up in the APK dex, which is exactly what the
    // Zygisk injector loads into the host process.
    runtimeOnly(projects.loader.zygisk)
    implementation(projects.loader.startup)
    // ksp
    ksp(projects.libs.ksp)
    // host stub
    compileOnly(projects.libs.stub)
    // libraries
    implementation(projects.libs.mmkv)
    implementation(projects.libs.dexkit)
    implementation(projects.libs.xView)
    // for get activation status
    implementation(libs.libxposed.service)
    implementation(libs.hiddenapibypass)
    implementation(libs.appcenter.analytics)
    implementation(libs.appcenter.crashes)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.common)
    implementation(libs.lifecycle.runtime)
    implementation(libs.flexbox)
    implementation(libs.colorpicker)
    // festival title
    implementation(libs.confetti)
    implementation(libs.glide)
    implementation(libs.material)
    implementation(libs.material.dialogs.core)
    implementation(libs.material.dialogs.input)
    implementation(libs.weatherView)
    implementation(libs.google.protobuf.java)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.byte.buddy)
    implementation(libs.dalvik.dx)
    implementation(libs.dexlib2)
    // I don't know why, but without this, compilation will fail
    implementation(libs.google.guava)
    implementation(libs.sealedEnum.runtime)
    ksp(libs.sealedEnum.ksp)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath
val packageName = "com.tencent.mobileqq"
val killQQ = tasks.register<Exec>("killQQ") {
    group = "qauxv"
    commandLine(adb, "shell", "am", "force-stop", packageName)
    isIgnoreExitValue = true
}

val openQQ = tasks.register<Exec>("openQQ") {
    group = "qauxv"
    commandLine(adb, "shell", "am", "start", "$(pm resolve-activity --components $packageName)")
    isIgnoreExitValue = true
}

val restartQQ = tasks.register<Exec>("restartQQ") {
    group = "qauxv"
    commandLine(adb, "shell", "am", "start", "$(pm resolve-activity --components $packageName)")
    isIgnoreExitValue = true
}.dependsOn(killQQ)

androidComponents.onVariants { variant ->
    val variantCapped = variant.name.capitalizeUS()
    task("install${variantCapped}AndRestartQQ") {
        group = "qauxv"
        dependsOn(":app:install$variantCapped")
        finalizedBy(restartQQ)
    }
}

tasks.register<task.ReplaceIcon>("replaceIcon") {
    group = "qauxv"
    projectDir.set(project.projectDir)
    commitHash = Common.getGitHeadRefsSuffix(rootProject)
    config()
}.also { tasks.preBuild.dependsOn(it) }

tasks.register<Delete>("cleanCxxIntermediates") {
    group = "qauxv"
    delete(file(".cxx"))
}.also { tasks.clean.dependsOn(it) }

tasks.register<Delete>("cleanOldIcon") {
    group = "qauxv"
    val drawableDir = File(projectDir, "src/main/res/drawable")
    drawableDir
        .listFiles()
        ?.filter { it.isFile && it.name.startsWith("icon") }
        ?.forEach(::delete)
    delete(file("src/main/res/drawable-anydpi-v26/icon.xml"))
}.also { tasks.clean.dependsOn(it) }

tasks.register("checkGitSubmodule") {
    group = "qauxv"
    val projectDir = rootProject.projectDir
    doLast {
        val submoduleContentLines = File(projectDir, ".gitmodules").readText().replace('\r', '\n').split('\n')
        // regex '[submodule "(.+)"]'
        val prefix = "[submodule \""
        val suffix = "\"]"
        val capturedSubmodulePaths = submoduleContentLines
            .filter { it.startsWith(prefix) && it.endsWith(suffix) }
            .map { it.substring(prefix.length, it.length - suffix.length) }
        capturedSubmodulePaths.forEach {
            val submoduleDir = File(projectDir, "$it/.git")
            if (!submoduleDir.exists()) {
                error(
                    "submodule dir not found: $submoduleDir" +
                        "\nPlease run 'git submodule init' and 'git submodule update' manually."
                )
            }
        }
    }
}.also { tasks.preBuild.dependsOn(it) }

val synthesizeDistReleaseApksCI by tasks.registering {
    group = "build"
    // use :app:assembleRelease output apk as input
    dependsOn(":app:packageRelease")
    inputs.files(tasks.named("packageRelease").get().outputs.files)
    val srcApkDir = File(project.buildDir, "outputs" + File.separator + "apk" + File.separator + "release")
    if (srcApkDir !in tasks.named("packageRelease").get().outputs.files) {
        val msg = "srcApkDir should be in packageRelease outputs, srcApkDir: $srcApkDir, " +
            "packageRelease outputs: ${tasks.named("packageRelease").get().outputs.files.files}"
        logger.error(msg)
    }
    // output name format: "QAuxv-v${defaultConfig.versionName}-${productFlavors.first().name}.apk"
    val outputAbiVariants = mapOf(
        "arm32" to arrayOf("armeabi-v7a"),
        "arm64" to arrayOf("arm64-v8a"),
        "armAll" to arrayOf("armeabi-v7a", "arm64-v8a"),
        "universal" to arrayOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    )
    val versionName = android.defaultConfig.versionName
    val outputDir = File(project.buildDir, "outputs" + File.separator + "ci")
    // declare output files
    outputAbiVariants.forEach { (variant, _) ->
        val outputName = "QAuxv-v${versionName}-${variant}.apk"
        outputs.file(File(outputDir, outputName))
    }
    val signConfig = android.signingConfigs.findByName("release")
    val minSdk = android.defaultConfig.minSdk!!
    doLast {
        if (signConfig == null) {
            logger.error("Task :app:synthesizeDistReleaseApksCI: No release signing config found, skip signing")
        }
        val requiredAbiList = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        outputDir.mkdir()
        val options = ZFileOptions().apply {
            alignmentRule = object : AlignmentRule {
                override fun alignment(path: String): Int {
                    if (path.endsWith(".so")) {
                        if (path.contains("arm64-v8a") || path.contains("x86_64") || path.contains("riscv64")) {
                            // for 64-bit so files, we use 16k alignment in case of 16k page size
                            return 16384
                        } else {
                            // for 32-bit so files, we use 4k alignment
                            // will there be any 16k-page-size devices supporting 32-bit abi?
                            return 4096
                        }
                    } else {
                        // no alignment for other files
                        return AlignmentRule.NO_ALIGNMENT
                    }
                }
            }
            noTimestamps = true
            autoSortFiles = true
        }
        require(srcApkDir.exists()) { "srcApkDir not found: $srcApkDir" }
        // srcApkDir should have one apk file
        val srcApkFiles = srcApkDir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") } ?: emptyList()
        require(srcApkFiles.size == 1) { "input apk should have one apk file, but found ${srcApkFiles.size}" }
        val inputApk = srcApkFiles.single()
        val startTime = System.currentTimeMillis()
        ZFile.openReadOnly(inputApk).use { srcApk ->
            // check whether all required abis are in the apk
            requiredAbiList.forEach { abi ->
                val path = "lib/$abi/libqauxv-core0.so"
                require(srcApk.get(path) != null) { "input apk should contain $path, but not found" }
            }
            outputAbiVariants.forEach { (variant, abis) ->
                val outputApk = File(outputDir, "QAuxv-v${versionName}-${variant}.apk")
                if (outputApk.exists()) {
                    outputApk.delete()
                }
                ZFiles.apk(outputApk, options).use { dstApk ->
                    if (signConfig != null) {
                        val keyStore = KeyStore.getInstance(signConfig.storeType ?: KeyStore.getDefaultType())
                        FileInputStream(signConfig.storeFile!!).use {
                            keyStore.load(it, signConfig.storePassword!!.toCharArray())
                        }
                        val protParam = KeyStore.PasswordProtection(signConfig.keyPassword!!.toCharArray())
                        val keyEntry = keyStore.getEntry(signConfig.keyAlias!!, protParam)
                        val privateKey = keyEntry as KeyStore.PrivateKeyEntry
                        val signingOptions = SigningOptions.builder()
                            .setMinSdkVersion(minSdk)
                            .setV1SigningEnabled(minSdk < 24)
                            .setV2SigningEnabled(true)
                            .setKey(privateKey.privateKey)
                            .setCertificates(privateKey.certificate as X509Certificate)
                            .setValidation(SigningOptions.Validation.ASSUME_INVALID)
                            .build()
                        SigningExtension(signingOptions).register(dstApk)
                    }
                    // add input apk to the output apk
                    srcApk.entries().forEach { entry ->
                        val cdh = entry.centralDirectoryHeader
                        val name = cdh.name
                        val isCompressed = cdh.compressionInfoWithWait.method != CompressionMethod.STORE
                        if (name.startsWith("lib/")) {
                            val abi = name.substring(4).split('/').first()
                            if (abis.contains(abi)) {
                                dstApk.add(name, entry.open(), isCompressed)
                            }
                        } else if (name.startsWith("META-INF/com/android/")) {
                            // drop gradle version
                        } else {
                            // add all other entries to the output apk
                            dstApk.add(name, entry.open(), isCompressed)
                        }
                    }
                    dstApk.update()
                }
            }
        }
        val endTime = System.currentTimeMillis()
        logger.info("Task :app:synthesizeDistReleaseApksCI: completed in ${endTime - startTime}ms")
    }
}

val generateEulaAndPrivacy by tasks.registering {
    inputs.files("${rootDir}/LICENSE.md", "${rootDir}/PRIVACY_LICENSE.md")
    outputs.file("${projectDir}/src/main/assets/eulaAndPrivacy.html")

    doFirst {
        val html = inputs.files.map { markdownToHTML(it.readText()) }
        outputs.files.forEach {
            val output = buildString {
                append("<!DOCTYPE html ><head><meta charset=\"UTF-8\"></head><html><body>")
                html.forEach(::append)
                append("</body></html>")
            }.lines().joinToString("")
            it.writeText(output)
        }
    }
}

tasks.matching { it.name.startsWith("lint") || it.name.endsWith("LintReportModel") }.configureEach {
    dependsOn(generateEulaAndPrivacy)
}

// see https://github.com/google/protobuf-gradle-plugin/issues/518
protobuf {
    protoc {
        artifact = libs.google.protobuf.protoc.get().toString()
    }
    plugins {
        generateProtoTasks {
            all().forEach {
                it.builtins {
                    create("java") {
                        option("lite")
                    }
                }
            }
        }
    }
}

// force kotlin to produce java 11 class files
tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// force javac to produce java 11 class files
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// javac should be able to read java 17 class files, although we force it to produce java 11 class files for this module
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// Fix for Gradle 9.1.0 validation: KSP task must declare dependency on protobuf generation
afterEvaluate {
    android.buildTypes.forEach { buildType ->
        val variantName = buildType.name.capitalizeUS()
        tasks.named("ksp${variantName}Kotlin").configure {
            dependsOn("generate${variantName}Proto")
        }
    }
}

// ── Zygisk module packaging ──────────────────────────────────────────────────
// Produces a dual-format package: the release APK stays an installable Xposed module,
// while the same file — renamed to .zip — is a flashable Magisk/KernelSU/APatch module.
// The module files (customize.sh, module.prop, zygisk/arm64-v8a.so, META-INF/...) are
// placed at the zip root next to the APK entries; nothing is nested inside.

val zygiskVersionName = android.defaultConfig.versionName
    ?: error("versionName must be set to package the Zygisk module")
val zygiskVersionCode = android.defaultConfig.versionCode
val zygiskLibName = "libqauxv-zygisk.so"

fun resolveSdkDirForZygisk(): File {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { f ->
            Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
        ?.trim()?.let { return File(it) }
    System.getenv("ANDROID_HOME")?.let { return File(it) }
    System.getenv("ANDROID_SDK_ROOT")?.let { return File(it) }
    error("Unable to locate the Android SDK: set ANDROID_HOME or sdk.dir in local.properties")
}

fun registerPrepareZygiskModuleTask(
    taskName: String,
    variant: String,
    stagingDirName: String,
    objPath: String,
): TaskProvider<Task> {
    return tasks.register(taskName) {
        group = "zygisk"
        description = "Assembles the Zygisk module directory ($variant)"
        notCompatibleWithConfigurationCache("stages the Zygisk module")
        dependsOn("externalNativeBuild${variant.replaceFirstChar { it.uppercase() }}")

        val stageDirProvider = layout.buildDirectory.dir(stagingDirName)
        val templateDir = layout.projectDirectory.dir("src/main/zygisk-template")
        val objDirProvider = layout.buildDirectory.dir(objPath)
        val versionName = zygiskVersionName
        val versionCode = zygiskVersionCode
        val libName = zygiskLibName
        val ndkVersion = android.ndkVersion

        inputs.dir(templateDir)
        inputs.dir(objDirProvider)
        outputs.dir(stageDirProvider)

        doLast {
            // Resolved lazily: failing here only breaks the Zygisk task, not the whole build.
            val sdkDir = resolveSdkDirForZygisk()
            val ndkDir = File(sdkDir, "ndk/$ndkVersion")
            val osName = System.getProperty("os.name")?.lowercase().orEmpty()
            val osArch = System.getProperty("os.arch")?.lowercase().orEmpty()
            val hostDir = when {
                osName.contains("mac") ->
                    if (osArch.contains("aarch64") || osArch.contains("arm64")) "darwin-arm64"
                    else "darwin-x86_64"
                osName.contains("win") -> "windows-x86_64"
                else -> "linux-x86_64"
            }
            val stripExe = File(
                ndkDir,
                "toolchains/llvm/prebuilt/$hostDir/bin/llvm-strip" +
                    if (osName.contains("win")) ".exe" else ""
            )

            val stageDir = stageDirProvider.get().asFile
            stageDir.deleteRecursively()
            stageDir.mkdirs()

            templateDir.asFile.copyRecursively(stageDir)
            // the installer runs under /system/bin/sh, CRLF would break it
            stageDir.walkTopDown().forEach { f ->
                if (f.isFile && f.extension == "sh") {
                    f.writeText(f.readText(Charsets.UTF_8).replace("\r\n", "\n"), Charsets.UTF_8)
                }
            }

            val propFile = File(stageDir, "module.prop")
            propFile.writeText(
                propFile.readText()
                    .replace("@VERSION@", versionName)
                    .replace("@VERSION_CODE@", versionCode.toString())
            )

            val candidates = mutableListOf<File>()
            val stableObj = File(objDirProvider.get().asFile, "arm64-v8a/$libName")
            if (stableObj.isFile) candidates += stableObj
            listOf("Debug", "RelWithDebInfo", "Release").forEach { buildType ->
                File(layout.buildDirectory.get().asFile, "intermediates/cxx/$buildType")
                    .listFiles()
                    ?.forEach { hashDir ->
                        val f = File(hashDir, "obj/arm64-v8a/$libName")
                        if (f.isFile) candidates += f
                    }
            }
            val objSo = candidates.maxByOrNull { it.lastModified() }
                ?: error(
                    "$libName not found (looked in $objPath and intermediates/cxx). " +
                        "Run externalNativeBuild${variant.replaceFirstChar { it.uppercase() }} first."
                )
            if (!stripExe.isFile) {
                error("NDK llvm-strip not found: $stripExe")
            }

            val targetSo = File(stageDir, "zygisk/arm64-v8a.so")
            targetSo.parentFile.mkdirs()
            val p = ProcessBuilder(
                stripExe.absolutePath, "-o", targetSo.absolutePath, objSo.absolutePath
            ).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { r ->
                r.forEachLine { line -> if (line.isNotBlank()) logger.lifecycle("  $line") }
            }
            if (p.waitFor() != 0) {
                error("llvm-strip failed: ${objSo.absolutePath}")
            }
            logger.lifecycle("Zygisk module ($variant) staged at $stageDir")
        }
    }
}

val prepareZygiskModuleRelease = registerPrepareZygiskModuleTask(
    "prepareZygiskModuleRelease", "release", "zygisk-module-release",
    "intermediates/cmake/release/obj"
)
val prepareZygiskModuleDebug = registerPrepareZygiskModuleTask(
    "prepareZygiskModuleDebug", "debug", "zygisk-module-debug",
    "intermediates/cmake/debug/obj"
)

/**
 * Merges the staged module files into a signed APK, producing one file that is both an
 * installable APK and a flashable module zip.
 */
fun registerBuildDualApkTask(
    taskName: String,
    variant: String,
    stagingDirName: String,
    prepareTask: TaskProvider<Task>,
): TaskProvider<Task> {
    return tasks.register(taskName) {
        group = "zygisk"
        description = "Builds the dual-format package ($variant)"
        notCompatibleWithConfigurationCache("builds the dual-format APK")
        dependsOn("package${variant.replaceFirstChar { it.uppercase() }}")
        dependsOn(prepareTask)

        val srcApkDir = File(layout.buildDirectory.get().asFile, "outputs" + File.separator + "apk" + File.separator + variant)
        val stageDirProvider = layout.buildDirectory.dir(stagingDirName)
        val outDir = File(layout.buildDirectory.get().asFile, "outputs" + File.separator + "zygisk")
        val versionName = zygiskVersionName
        val outFile = File(outDir, "QAuxv-zygisk-v$versionName-$variant.apk")

        inputs.dir(srcApkDir)
        inputs.dir(stageDirProvider)
        outputs.file(outFile)

        val signConfig = android.signingConfigs.findByName(variant)
        val minSdk = android.defaultConfig.minSdk!!

        doLast {
            val srcApks = srcApkDir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") } ?: emptyList()
            val inputApk = srcApks.singleOrNull()
                ?: error("expected exactly one APK in $srcApkDir, found ${srcApks.size}")
            val stageDir = stageDirProvider.get().asFile

            outFile.parentFile.mkdirs()
            if (outFile.exists()) outFile.delete()

            val options = ZFileOptions().apply {
                alignmentRule = AlignmentRule { path ->
                    if (path.endsWith(".so")) {
                        // 16k alignment for 64-bit ABIs in case of 16k page size devices
                        if (path.contains("arm64-v8a") || path.contains("x86_64") || path.contains("riscv64")) {
                            16384
                        } else {
                            4096
                        }
                    } else {
                        AlignmentRule.NO_ALIGNMENT
                    }
                }
                noTimestamps = true
                autoSortFiles = true
            }

            ZFile.openReadOnly(inputApk).use { srcApk ->
                ZFiles.apk(outFile, options).use { dstApk ->
                    if (signConfig != null && signConfig.storeFile != null) {
                        val keyStore = KeyStore.getInstance(signConfig.storeType ?: KeyStore.getDefaultType())
                        FileInputStream(signConfig.storeFile!!).use {
                            keyStore.load(it, signConfig.storePassword!!.toCharArray())
                        }
                        val protParam = KeyStore.PasswordProtection(signConfig.keyPassword!!.toCharArray())
                        val privateKey = keyStore.getEntry(signConfig.keyAlias!!, protParam) as KeyStore.PrivateKeyEntry
                        val signingOptions = SigningOptions.builder()
                            .setMinSdkVersion(minSdk)
                            .setV1SigningEnabled(false)
                            .setV2SigningEnabled(true)
                            .setKey(privateKey.privateKey)
                            .setCertificates(privateKey.certificate as X509Certificate)
                            .setValidation(SigningOptions.Validation.ASSUME_INVALID)
                            .build()
                        SigningExtension(signingOptions).register(dstApk)
                    } else {
                        logger.warn("No signing config for '$variant': the dual-format package will be unsigned")
                    }
                    // 1. copy every APK entry
                    srcApk.entries().forEach { entry ->
                        val cdh = entry.centralDirectoryHeader
                        val isCompressed = cdh.compressionInfoWithWait.method != CompressionMethod.STORE
                        dstApk.add(cdh.name, entry.open(), isCompressed)
                    }
                    // 2. inject the module files at the zip root
                    stageDir.walkTopDown().forEach { f ->
                        if (!f.isFile) return@forEach
                        val rel = f.relativeTo(stageDir).path.replace('\\', '/')
                        // .so must stay uncompressed and page-aligned so that the zygote
                        // can mmap it directly; everything else is fine compressed.
                        val store = rel.startsWith("zygisk/") && rel.endsWith(".so")
                        dstApk.add(rel, f.inputStream(), !store)
                    }
                    dstApk.update()
                }
            }
            logger.lifecycle("Dual-format package ($variant): $outFile")
        }
    }
}

val buildDualApkRelease = registerBuildDualApkTask(
    "buildDualApkRelease", "release", "zygisk-module-release", prepareZygiskModuleRelease
)
val buildDualApkDebug = registerBuildDualApkTask(
    "buildDualApkDebug", "debug", "zygisk-module-debug", prepareZygiskModuleDebug
)

// A plain .zip copy of the dual-format release package, ready to be flashed.
val packageZygiskModule by tasks.registering {
    group = "zygisk"
    description = "Copies the dual-format release package to outputs/zygisk as a .zip"
    notCompatibleWithConfigurationCache("packages the Zygisk module")
    dependsOn(buildDualApkRelease)

    val outFile = File(layout.buildDirectory.get().asFile, "outputs/zygisk/QAuxv-zygisk-v$zygiskVersionName.zip")
    inputs.file(File(layout.buildDirectory.get().asFile, "outputs/zygisk/QAuxv-zygisk-v$zygiskVersionName-release.apk"))
    outputs.file(outFile)

    doLast {
        outFile.parentFile.mkdirs()
        inputs.files.singleFile.copyTo(outFile, overwrite = true)
        logger.lifecycle("Zygisk module zip: $outFile")
    }
}

tasks.named("assemble") {
    dependsOn(packageZygiskModule)
}
