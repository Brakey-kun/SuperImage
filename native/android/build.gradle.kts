plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.supervideo.nativelib"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 24
        ndk {
            // bytedeco ships FFmpeg for arm64 only on Android.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                )
                targets += listOf("realesrgan", "MNN", "MNN_VK", "MNN_CL")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../core/CMakeLists.txt")
            version = "4.3.3"
        }
    }

    sourceSets["main"].assets.srcDirs("../models")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
