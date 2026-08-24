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
        versionCode = System.getenv("DRIVEAPEX_VERSION_CODE")?.toIntOrNull() ?: 51
        versionName = System.getenv("DRIVEAPEX_VERSION_NAME") ?: "0.2.51"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // Explicit Android resource exclusions. The release merge was
            // failing on repeated JUnit license/notice resources coming from
            // the resolved runtime dependency graph.
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE",
                "META-INF/DEPENDENCIES"
            )
        }
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

// JUnit/Jupiter are test-only libraries. Keep them out of the Android
// production/runtime dependency graph so their META-INF resources cannot
// break release packaging.
configurations.configureEach {
    exclude(group = "org.junit.platform")
    exclude(group = "org.junit.jupiter")
    exclude(group = "org.junit.vintage")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // Verified direct-ADB transport used by the reference OverDrive implementation.
    implementation("dev.mobile:dadb:1.2.8")
}
