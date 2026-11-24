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
        versionCode = 10
        versionName = "0.4-lan5"
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
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")
    // 旧壳 NotificationCompat 等（任务包5允许的唯一新增依赖）
    implementation("androidx.core:core:1.12.0")
}
