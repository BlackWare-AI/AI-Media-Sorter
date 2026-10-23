plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val qaReleaseSigning = providers.gradleProperty("qaReleaseSigning").orNull == "true"
val selectedAbi = providers.gradleProperty("targetAbi").orNull ?: "arm64-v8a"
require(selectedAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) { "Unsupported targetAbi" }

android {
    namespace = "com.aimediasorter.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aimediasorter.app"
        minSdk = 34
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.3-subject-intent"
        ndk { abiFilters += selectedAbi }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("test-proguard-rules.pro")
        vectorDrawables {
            useSupportLibrary = true
        }
        resourceConfigurations += listOf("en", "tr")
    }

    testBuildType = if (qaReleaseSigning) "release" else "debug"

    buildTypes {
        release {
            if (qaReleaseSigning) signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("onnx", "tflite", "mobile_ica_8bit_with_metadata_tflite")
    }

    packaging {
        // Smaller downloads, but extracted installed size may increase.
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room Local Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager & DataStore
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Storage, EXIF Metadata & Coroutines
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // Media Thumbnails (Photos & Video Frames)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // On-Device AI Inference: ONNX Runtime Mobile + Bundled ML Kit Image Labeling
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.image.labeling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(libs.androidx.ui.tooling)
}
