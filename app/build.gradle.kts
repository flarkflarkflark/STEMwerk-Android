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
        versionCode = 22
        versionName = "0.4.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // We start with ARM64 only (ZenFone 10). Add armeabi-v7a later if needed.
        ndk {
            abiFilters += listOf("arm64-v8a")
            if (project.hasProperty("testEmulator")) abiFilters += "x86_64"
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

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // This slice targets QNN's float GPU backend. HTP/DSP binaries are
            // only useful for separately quantized NPU models and add roughly
            // 170 MB to an APK, so do not ship them in the GPU build.
            excludes += setOf(
                "**/libQnnHtp*.so",
                "**/libQnnDsp*.so",
            )
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("com.caverock:androidsvg:1.4")

    // The delivered ARM64 APK includes the official Qualcomm QNN execution
    // provider and runtime. The x86_64 CI emulator uses regular ORT because
    // QNN targets Snapdragon Android hardware.
    if (project.hasProperty("testEmulator")) {
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    } else {
        implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    }

    // Arbitrary-length real/complex FFT used by the MDX STFT/ISTFT path.
    implementation("com.github.wendykierp:JTransforms:3.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
