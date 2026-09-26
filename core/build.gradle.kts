plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

val ffmpegVersion = libs.versions.bytedeco.ffmpeg.get()
val javacppVersion = libs.versions.javacpp.get()

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.bytedeco.ffmpeg)
    api(libs.bytedeco.javacpp)

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.bytedeco:ffmpeg:$ffmpegVersion:windows-x86_64-gpl")
    testRuntimeOnly("org.bytedeco:javacpp:$javacppVersion:windows-x86_64")
}

tasks.test {
    useJUnitPlatform()
    // Native/integration tests are skipped unless the directory holding realesrgan.dll + models/ is passed in.
    System.getProperty("supervideo.nativeDir")?.let { systemProperty("supervideo.nativeDir", it) }
    systemProperty("supervideo.testMediaDir", layout.buildDirectory.dir("test-media").get().asFile.absolutePath)
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
