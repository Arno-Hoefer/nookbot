import java.util.Properties

// Private Adressen stehen NICHT im Repo, sondern in local.properties (gitignored):
//   nookbot.server=https://<pi>.<tailnet>.ts.net:8444     nookbot.mcp=https://<pi>.<tailnet>.ts.net
val lokal = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.hoefer.nookbot"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "de.hoefer.nookbot"
        minSdk = 29
        targetSdk = 36
        versionCode = 36
        versionName = "0.16.0"
        buildConfigField("String", "SERVER", "\"${lokal.getProperty("nookbot.server", "")}\"")
        buildConfigField("String", "MCP", "\"${lokal.getProperty("nookbot.mcp", "")}\"")
        // Sprachnotizen: whisper.cpp lokal auf dem Handy (wie in Echo), nur 64-bit-ARM
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Zum Selbstinstallieren: Debug-Schlüssel dieses Macs (bleibt gleich, Updates gehen also)
            signingConfig = signingConfigs.getByName("debug")
        }
        // Prüfversion: genau wie Release (R8, verkürzte Namen), aber mit Klartext für den Demo-Adapter im Emulator –
        // damit Fehler, die nur in der Release-Version auftreten (z. B. JNI-Namen), vor dem Verschicken auffallen.
        create("pruefung") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("pruefung").manifest.srcFile("src/debug/AndroidManifest.xml")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.tracing:tracing:1.2.0")
    // WebSocket zum Bot (PicoClaw-Kanal „pico“)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Text in Fotos erkennen – auf dem Handy, Modell ist in der App (kein Google-Dienst nötig)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.core:core-ktx:1.16.0")
    // Health Connect (nur lesen) und täglicher Abgleich im Hintergrund
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.core:core-telecom:1.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
