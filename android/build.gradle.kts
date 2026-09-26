import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose)
}

val ffmpegVersion = libs.versions.bytedeco.ffmpeg.get()
val javacppVersion = libs.versions.javacpp.get()

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "com.supervideo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.supervideo"
        minSdk = 24
        // Sideload distribution: keeps Android 14 foreground-service-type limits out of scope.
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isEmpty) {
                // No release keystore configured: sign with the debug key so the APK is always installable.
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            } else {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // The Compose compiler comes from the org.jetbrains.compose plugin (matching Kotlin 1.9.24).
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/native-image/**",
                "META-INF/versions/**",
                // bytedeco ships the ffmpeg/ffprobe CLIs next to the libraries; the app only uses libav*.
                "lib/*/ffmpeg",
                "lib/*/ffprobe",
            )
        }
        jniLibs {
            // bytedeco extracts nothing on Android: libraries must be real files in the APK lib dir.
            useLegacyPackaging = true
            excludes += listOf("lib/*/ffmpeg", "lib/*/ffprobe")
        }
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":native:android"))
    runtimeOnly("org.bytedeco:ffmpeg:$ffmpegVersion:android-arm64-gpl")
    runtimeOnly("org.bytedeco:javacpp:$javacppVersion:android-arm64")

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
}
