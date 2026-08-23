plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.driveapex"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.driveapex"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("DRIVEAPEX_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("DRIVEAPEX_VERSION_NAME") ?: "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.15.0")
}
