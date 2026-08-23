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
        versionCode = System.getenv("DRIVEAPEX_VERSION_CODE")?.toIntOrNull() ?: 49
        versionName = System.getenv("DRIVEAPEX_VERSION_NAME") ?: "0.2.49"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("DRIVEAPEX_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storeType = "JKS"
                storePassword = System.getenv("DRIVEAPEX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DRIVEAPEX_KEY_ALIAS")
                keyPassword = System.getenv("DRIVEAPEX_KEY_PASSWORD")
            }
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
    // Verified direct-ADB transport used by the reference OverDrive implementation.
    implementation("dev.mobile:dadb:1.2.8")
}
