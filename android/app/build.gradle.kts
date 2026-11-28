import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun getConfigSecret(keyName: String): String {
    // 1. Try Environment Variable (GitHub Actions Secret / System env)
    val envKey = System.getenv(keyName)
    if (!envKey.isNullOrBlank()) return envKey

    // 2. Try local.properties in android directory
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        try {
            val properties = Properties()
            properties.load(FileInputStream(localPropertiesFile))
            val key = properties.getProperty(keyName)
            if (!key.isNullOrBlank()) return key
        } catch (_: Exception) {}
    }

    // 3. Try .env in root directory
    val rootEnvFile = rootProject.file("../.env")
    if (rootEnvFile.exists()) {
        try {
            for (line in rootEnvFile.readLines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("$keyName=")) {
                    val key = trimmed.substringAfter("$keyName=").trim().trim('"', '\'')
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
        versionCode = 30
        versionName = "1.3.7"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val apiKey = getConfigSecret("GEMINI_API_KEY")
        buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")
        val groqKey = getConfigSecret("GROQ_API_KEY")
        buildConfigField("String", "GROQ_API_KEY", "\"$groqKey\"")
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

    // Offline low-power wake-word detection ("Jarvis") without stealing Audio Focus from Hotstar/Reels
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
