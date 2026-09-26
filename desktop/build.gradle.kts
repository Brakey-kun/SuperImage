import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
}

val ffmpegVersion = libs.versions.bytedeco.ffmpeg.get()
val javacppVersion = libs.versions.javacpp.get()

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    runtimeOnly("org.bytedeco:ffmpeg:$ffmpegVersion:windows-x86_64-gpl")
    runtimeOnly("org.bytedeco:javacpp:$javacppVersion:windows-x86_64")
}

// ---- Native realesrgan.dll (MinGW + Ninja) -------------------------------------------------------

val mingwBin = providers.gradleProperty("supervideo.mingwBin").getOrElse("C:/msys64/ucrt64/bin")
val ninjaPath = providers.gradleProperty("supervideo.ninja")
    .getOrElse("C:/Users/amine/AppData/Local/Programs/Python/Python313/Scripts/ninja.exe")
val nativeSourceDir = rootProject.layout.projectDirectory.dir("native/core")
val nativeBuildDir = layout.buildDirectory.dir("native-windows")
val jdkHome: String = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    .get().metadata.installationPath.asFile.absolutePath

fun Exec.nativeEnvironment() {
    environment("PATH", File(mingwBin).absolutePath + File.pathSeparator + System.getenv("PATH"))
    environment("JAVA_HOME", jdkHome)
}

val configureNativeWindows by tasks.registering(Exec::class) {
    group = "native"
    inputs.file(nativeSourceDir.file("CMakeLists.txt"))
    outputs.file(nativeBuildDir.map { it.file("build.ninja") })
    nativeEnvironment()
    commandLine(
        "cmake",
        "-S", nativeSourceDir.asFile.absolutePath,
        "-B", nativeBuildDir.get().asFile.absolutePath,
        "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_C_COMPILER=$mingwBin/gcc.exe",
        "-DCMAKE_CXX_COMPILER=$mingwBin/g++.exe",
        "-DCMAKE_MAKE_PROGRAM=$ninjaPath",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "-DMNN_VULKAN=ON",
        "-DMNN_OPENCL=ON",
        "-DMNN_BUILD_SHARED_LIBS=OFF",
        "-DMNN_SEP_BUILD=OFF",
        "-DMNN_BUILD_TOOLS=OFF",
        "-DMNN_USE_SYSTEM_LIB=OFF",
    )
}

val buildNativeWindows by tasks.registering(Exec::class) {
    group = "native"
    dependsOn(configureNativeWindows)
    inputs.files(fileTree(nativeSourceDir) {
        include("*.cpp", "*.h", "CMakeLists.txt", "mnn_patches/**")
    })
    outputs.file(nativeBuildDir.map { it.file("realesrgan.dll") })
    nativeEnvironment()
    commandLine("cmake", "--build", nativeBuildDir.get().asFile.absolutePath, "--target", "realesrgan", "--config", "Release")
}

val appResourcesDir = layout.buildDirectory.dir("appResources")

/** Stages realesrgan.dll + models where Compose's appResourcesRootDir expects them. */
val prepareNativeResources by tasks.registering(Sync::class) {
    group = "native"
    dependsOn(buildNativeWindows)
    into(appResourcesDir)
    from(nativeBuildDir.map { it.file("realesrgan.dll") }) { into("windows-x64") }
    from(rootProject.layout.projectDirectory.dir("native/models")) {
        include("*.mnn")
        into("common/models")
    }
}

compose.desktop {
    application {
        mainClass = "com.supervideo.desktop.MainKt"
        javaHome = jdkHome
        jvmArgs += listOf("-Xmx2g")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "SuperVideo"
            packageVersion = "0.1.0"
            description = "AI video upscaler based on SuperImage"
            vendor = "SuperVideo"
            modules("java.desktop", "java.logging", "jdk.unsupported", "java.instrument")
            appResourcesRootDir.set(appResourcesDir)
            windows {
                menuGroup = "SuperVideo"
                upgradeUuid = "4f0d3c1a-8c5e-4b7e-9a51-2f6c1d9e7b30"
            }
        }
        buildTypes.release.proguard {
            // ProGuard breaks bytedeco's reflective native loader.
            isEnabled.set(false)
        }
    }
}

// Compose's own prepareAppResources copies appResourcesRootDir into run/distribution resources.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareNativeResources)
}
