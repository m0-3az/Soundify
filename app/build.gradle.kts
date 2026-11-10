plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is read from the environment so no key or password is
// committed. See docs/releases/README.md for the required variables.
val releaseKeystoreFile = System.getenv("SOUNDIFY_KEYSTORE")
    ?.let { File(it) }
    ?.takeIf { it.isFile }
val releaseStorePassword = System.getenv("SOUNDIFY_KEYSTORE_PASSWORD_FILE")
    ?.let { File(it) }
    ?.takeIf { it.isFile }
    ?.readText()
    ?.trim()
val releaseKeyPassword = System.getenv("SOUNDIFY_KEY_PASSWORD_FILE")
    ?.let { File(it) }
    ?.takeIf { it.isFile }
    ?.readText()
    ?.trim()
    ?: releaseStorePassword
val releaseKeyAlias = System.getenv("SOUNDIFY_KEY_ALIAS") ?: "soundify"
val releaseSigningReady = releaseKeystoreFile != null && !releaseStorePassword.isNullOrEmpty()

android {
    namespace = "com.soundify.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soundify.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady && releaseKeystoreFile != null && releaseStorePassword != null) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            // Public APKs use the release key. Without the environment
            // variables the release build is left unsigned instead of silently
            // falling back to a debug key that cannot update a published app.
            signingConfig = if (releaseSigningReady) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Clean Video: on-device separation + FFmpeg
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
    implementation("com.arthenica:smart-exception-java:0.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
