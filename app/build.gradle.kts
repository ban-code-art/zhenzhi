plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Explicit opt-in debug experiment; Gecko is excluded from normal debug and release.
val m03GeckoPoc = providers.gradleProperty("m03GeckoPoc").orNull == "true"
// Personal device deliveries keep the existing install identity while using release runtime behavior.
val personalOptimized = providers.gradleProperty("personalOptimized").orNull == "true"
val personalUpdateRepository = "ban-code-art/zhenzhi-releases"
val updateRepository = providers.gradleProperty("s1UpdateRepository")
    .orElse(if (personalOptimized) personalUpdateRepository else "")
    .get()
val updateSigner = providers.gradleProperty("s1UpdateSignerSha256")
    .orElse(
        if (personalOptimized && updateRepository == personalUpdateRepository) {
            "2de2d3a2a9044622562ac8556692a4126e2c0e1b92edf4b3a1a6f2498a10f263"
        } else "",
    )
    .get()
require((updateRepository.isEmpty() && updateSigner.isEmpty()) ||
    (updateRepository.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) &&
        updateSigner.matches(Regex("[0-9a-f]{64}")))) {
    "A Gitee owner/repository and lowercase signer SHA-256 must be supplied together."
}
val suppliedVersionCode = providers.gradleProperty("s1VersionCode").orElse("1").get()
val buildVersionCode = requireNotNull(suppliedVersionCode.toIntOrNull()?.takeIf { it > 0 }) {
    "s1VersionCode must be a positive integer."
}
val buildVersionName = providers.gradleProperty("s1VersionName").orElse("1.0").get()
require(buildVersionName.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}"))) {
    "s1VersionName must contain numeric version segments."
}
val updateChannel = if (personalOptimized) "debug-compatible" else "release"
val releaseSmokeSources = tasks.register<Sync>("prepareReleaseSmokeSources") {
    from("src/androidTest/java") {
        include(
            "**/InteractionStreamingDeviceTest.kt",
            "**/CourseNavigationTest.kt",
            "**/UiNavigationDeviceTest.kt",
            "**/BackGhostDeviceTest.kt",
            "**/NavigationFrameDeviceTest.kt",
            "**/AboutUpdateDeviceTest.kt",
            "**/LauncherIconSwitchDeviceTest.kt",
            "**/IconAboutAndGlassDeviceTest.kt",
            "**/GlassCoverageAndBackupAnimDeviceTest.kt",
            "**/OrdinaryQuestionBankDeviceTest.kt",
            "**/EncyclopediaMigrationDeviceTest.kt",
            "**/EncyclopediaDeviceTest.kt",
        )
    }
    into(layout.buildDirectory.dir("generated/releaseSmokeSources"))
}
if (personalOptimized) tasks.configureEach {
    if (name == "kspReleaseAndroidTestKotlin" || name == "compileReleaseAndroidTestKotlin") dependsOn(releaseSmokeSources)
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 37
        versionCode = buildVersionCode
        versionName = buildVersionName
        buildConfigField("String", "S1_UPDATE_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("String", "S1_UPDATE_SIGNER", "\"$updateSigner\"")
        buildConfigField("String", "S1_UPDATE_CHANNEL", "\"$updateChannel\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            if (personalOptimized) {
                signingConfig = signingConfigs.getByName("debug")
            }
            optimization {
                // Non-debug execution is the performance requirement; keep shared APIs available
                // for the same-artifact device tests instead of shrinking their runtime dependencies.
                enable = !personalOptimized
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    // Compress native libraries for the signed personal update downloads. Android extracts them at install.
    if (personalOptimized) packaging.jniLibs.useLegacyPackaging = true
    testBuildType = if (personalOptimized) "release" else "debug"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        if (personalOptimized) {
            // Release smoke tests use the actual activity; legacy engine probes depend on debug-only hosts.
            getByName("androidTest").java.directories.clear()
            getByName("androidTest").kotlin.directories.clear()
            getByName("androidTest").kotlin.directories.add(layout.buildDirectory.dir("generated/releaseSmokeSources").get().asFile.path)
        }
        if (m03GeckoPoc) {
            getByName("androidTest").kotlin.directories.add("src/geckoPoc/androidTest")
            getByName("debug") {
                kotlin.directories.add("src/geckoPoc/java")
                assets.directories.add("src/geckoPoc/assets")
                manifest.srcFile("src/geckoPoc/AndroidManifest.xml")
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    if (m03GeckoPoc) {
        debugImplementation("org.mozilla.geckoview:geckoview:143.0.20251003115653")
    }
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    // 离线 OCR：bundled Chinese 模型随 APK 分发，不依赖 Play 服务在线下载（W5-03）。
    implementation(libs.mlkit.text.chinese)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.core)
}
