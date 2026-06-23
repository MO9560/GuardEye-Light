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
        versionCode = 9
        versionName = "2.3.9"
    }

    signingConfigs {
        getByName("debug") {
            if (file("signing.properties").exists()) {
                val props = mutableMapOf<String, String>()
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
}

dependencies {
    // ── Shared dependencies ───────────────────────────────────────
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")

    // ── CameraX (Light version) ──────────────────────────────────
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
}
