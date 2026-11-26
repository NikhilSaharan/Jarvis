import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun getGeminiApiKey(): String {
    // 1. Try Environment Variable (GitHub Actions Secret / System env)
    val envKey = System.getenv("GEMINI_API_KEY")
    if (!envKey.isNullOrBlank()) return envKey

    // 2. Try local.properties in android directory
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        try {
            val properties = Properties()
            properties.load(FileInputStream(localPropertiesFile))
            val key = properties.getProperty("GEMINI_API_KEY")
            if (!key.isNullOrBlank()) return key
        } catch (_: Exception) {}
    }

    // 3. Try .env in root directory
    val rootEnvFile = rootProject.file("../.env")
    if (rootEnvFile.exists()) {
        try {
            for (line in rootEnvFile.readLines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("GEMINI_API_KEY=")) {
                    val key = trimmed.substringAfter("GEMINI_API_KEY=").trim().trim('"', '\'')
                    if (key.isNotBlank()) return key
                }
            }
        } catch (_: Exception) {}
    }
    return ""
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val apiKey = getGeminiApiKey()
        buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("jarvis-release.jks")
            storePassword = "jarvis12345"
            keyAlias = "jarvis"
            keyPassword = "jarvis12345"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Networking & JSON for Gemini API and ElevenLabs
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}
