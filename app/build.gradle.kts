import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
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

val appVersion = "1.5.0"

// Optional release signing: the keystore lives outside the repo (in
// <repo>/../AndroidKeys/) and is never committed. Without the file, release
// builds stay unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("../AndroidKeys/keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val hasSigningConfig = keystoreProps.getProperty("storeFile") != null

val composeBomVersion = "2024.12.01"
val coreKtxVersion = "1.15.0"
val activityComposeVersion = "1.9.3"
val lifecycleVersion = "2.8.7"
val cameraVersion = "1.4.2"
val kotlinxSerializationVersion = "1.7.3"
val roomVersion = "2.6.1"
val koinVersion = "4.0.4"
val datastoreVersion = "1.1.1"
val navigationVersion = "2.8.4"
val junitVersion = "4.13.2"

android {
    namespace = "de.gurum.besseresser"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.gurum.besseresser"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:$composeBomVersion")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:$coreKtxVersion")
    implementation("androidx.activity:activity-compose:$activityComposeVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("io.insert-koin:koin-android:$koinVersion")
    implementation("io.insert-koin:koin-androidx-compose:$koinVersion")
    implementation("androidx.datastore:datastore-preferences:$datastoreVersion")
    implementation("androidx.navigation:navigation-compose:$navigationVersion")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:$junitVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
}
