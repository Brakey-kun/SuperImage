plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
}

val executablesDir = layout.projectDirectory.dir("executables")

/**
 * Builds every shippable artifact and refreshes `executables/`:
 * SuperVideo-release.apk, SuperVideo-debug.apk, SuperVideo-windows-x64/ (SuperVideo.exe) and SuperVideo-windows-x64.zip.
 */
tasks.register<Sync>("packageExecutables") {
    group = "distribution"
    dependsOn(":android:assembleRelease", ":android:assembleDebug", ":desktop:createReleaseDistributable")
    into(executablesDir)
    from(project(":android").layout.buildDirectory.file("outputs/apk/release/android-release.apk")) {
        rename { "SuperVideo-release.apk" }
    }
    from(project(":android").layout.buildDirectory.file("outputs/apk/debug/android-debug.apk")) {
        rename { "SuperVideo-debug.apk" }
    }
    from(project(":desktop").layout.buildDirectory.dir("compose/binaries/main-release/app/SuperVideo")) {
        into("SuperVideo-windows-x64")
    }
    doLast {
        val zip = executablesDir.file("SuperVideo-windows-x64.zip").asFile
        val base = executablesDir.dir("SuperVideo-windows-x64").asFile
        for (apk in listOf("SuperVideo-release.apk", "SuperVideo-debug.apk")) {
            check(executablesDir.file(apk).asFile.isFile) { "$apk missing from $executablesDir" }
        }
        check(File(base, "SuperVideo.exe").isFile) { "SuperVideo.exe missing from $base" }
        ant.withGroovyBuilder {
            "zip"("destfile" to zip, "basedir" to base)
        }
    }
}
