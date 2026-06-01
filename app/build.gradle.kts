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
        versionCode = 4
        versionName = "3.0.1"
    }

    signingConfigs {
        getByName("debug") {
            if (file("signing.properties").exists()) {
                val props = mapOf<String, String>()
                file("signing.properties").readLines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        when (parts[0].trim()) {
                            "storeFile" -> storeFile = file(parts[1].trim())
                            "storePassword" -> storePassword = parts[1].trim()
                            "keyAlias" -> keyAlias = parts[1].trim()
                            "keyPassword" -> keyPassword = parts[1].trim()
                        }
                    }
                }
            } else {
                storeFile = file("guard-eye.jks")
                storePassword = "GuardEye2026"
                keyAlias = "guard-eye"
                keyPassword = "GuardEye2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
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

    flavorDimensions += listOf("version")

    productFlavors {
        create("full") {
            dimension = "version"
            applicationIdSuffix = ""
            resValue("string", "app_name", "GuardEye")
            buildConfigField("boolean", "IS_LIGHT", "false")
        }
        create("light") {
            dimension = "version"
            applicationIdSuffix = ".light"
            minSdk = 21
            resValue("string", "app_name", "GuardEye Light")
            buildConfigField("boolean", "IS_LIGHT", "true")
        }
    }
}

dependencies {
    // ── Shared by both flavors ───────────────────────────────────────
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")

    // ── Full flavor only: CameraX + TFLite ────────────────────────────
    // AGP auto-creates "fullImplementation" from the "full" product flavor
    "fullImplementation"("androidx.camera:camera-core:1.3.1")
    "fullImplementation"("androidx.camera:camera-camera2:1.3.1")
    "fullImplementation"("androidx.camera:camera-lifecycle:1.3.1")
    "fullImplementation"("androidx.camera:camera-view:1.3.1")
    // ImageAnalysis is included in camera-core — no separate artifact needed
    "fullImplementation"("org.tensorflow:tensorflow-lite:2.14.0")
    "fullImplementation"("org.tensorflow:tensorflow-lite-support:0.4.4")
    // Gson for JSON serialization (rolling buffer metadata, face vectors)
    "fullImplementation"("com.google.code.gson:gson:2.10.1")

    // ── Light flavor only: CameraX (replaces deprecated Camera1) ──────
    "lightImplementation"("androidx.camera:camera-core:1.3.1")
    "lightImplementation"("androidx.camera:camera-camera2:1.3.1")
    "lightImplementation"("androidx.camera:camera-lifecycle:1.3.1")
}
