plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guardeye"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guardeye"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }

    flavorDimensions += "version"
    productFlavors {
        create("main") {
            dimension = "version"
            applicationIdSuffix = ""
            resValue("string", "app_name", "GuardEye")
            buildConfigField("boolean", "IS_LIGHT", "false")
        }
        create("light") {
            dimension = "version"
            applicationIdSuffix = ".light"
            minSdk = 19
            resValue("string", "app_name", "GuardEye Light")
            buildConfigField("boolean", "IS_LIGHT", "true")
        }
    }
}

dependencies {
    // Shared by both flavors
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")

    // Main flavor only
    "mainImplementation"("androidx.camera:camera-core:1.3.1")
    "mainImplementation"("androidx.camera:camera-camera2:1.3.1")
    "mainImplementation"("androidx.camera:camera-lifecycle:1.3.1")
    "mainImplementation"("androidx.camera:camera-view:1.3.1")
    "mainImplementation"("org.tensorflow:tensorflow-lite:2.14.0")
    "mainImplementation"("org.tensorflow:tensorflow-lite-support:0.4.4")
}
