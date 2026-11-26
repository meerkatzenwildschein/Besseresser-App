@file:Suppress("UnstableApiUsage")

import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Deterministic build timestamp: taken from the current git HEAD commit date
// (UTC), so builds of the same commit are reproducible across machines.
val buildTime: String = run {
    val epochSeconds = providers.exec {
        commandLine("git", "log", "-1", "--format=%cd", "--date=unix")
    }.standardOutput.asText.get().trim().toLongOrNull()
    if (epochSeconds == null) return@run "unknown"
    Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
}

val appVersion = "1.8.0"

// Optional release signing: the keystore lives outside the repo (in
// <repo>/../AndroidKeys/) and is never committed. Without the file, release
// builds stay unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("../AndroidKeys/keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val hasSigningConfig = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "de.gurum.besseresser"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.gurum.besseresser"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = appVersion
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "APP_VERSION", "\"$appVersion\"")
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "META-INF/*"
        jniLibs.keepDebugSymbols += setOf(
            "**/libandroidx.graphics.path.so",
            "**/libimage_processing_util_jni.so",
            "**/libsurface_util_jni.so"
        )
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        getByName("test").assets.srcDirs(file("$projectDir/schemas"))
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
}
