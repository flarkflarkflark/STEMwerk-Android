plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flark.stemwerk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flark.stemwerk"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.2.0"

        // We start with ARM64 only (ZenFone 10). Add armeabi-v7a later if needed.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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
    implementation("com.caverock:androidsvg:1.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Future:
    // implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
    // FFmpeg integration TBD.
}
