plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unrealsms.otprelay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.unrealsms.otprelay"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

// No dependencies needed: org.json and javax.crypto ship with the Android
// platform itself, and the UI uses plain android.app.Activity/widgets.
