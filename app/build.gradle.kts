plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phoneshare.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoneshare.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-m3a"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // WebRTC：社区维护的预编译版（Maven Central），包名仍是 org.webrtc，代码不用改 import
    // 原先的 org.webrtc:google-webrtc:1.0.32006 疑似只托管在已关闭的 jCenter，拉不到（主导 11/17 决定替换）
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")
}
