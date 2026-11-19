plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hifairy.ttslab"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.hifairy.ttslab"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(files("../voice-lab/vendor/sherpa-static.aar"))
}
