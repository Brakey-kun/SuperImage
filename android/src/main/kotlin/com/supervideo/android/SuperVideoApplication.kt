package com.supervideo.android

import android.app.Application
import com.supervideo.core.job.JobRepository
import com.supervideo.core.job.JobService
import com.supervideo.core.settings.AppSettings
import com.supervideo.core.settings.SettingsStore
import com.supervideo.core.settings.UpscaleSettings
import android.os.Build
import android.util.Log
import com.supervideo.BuildConfig
import com.supervideo.core.util.AppLog
import java.io.File

class SuperVideoApplication : Application() {

    lateinit var platform: AndroidPlatform
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var repository: JobRepository
        private set
    lateinit var jobRunner: AndroidJobRunner
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(
            File(filesDir, "logs"),
            echo = { level, tag, message, error ->
                val priority = when (level) {
                    AppLog.Level.DEBUG -> Log.DEBUG
                    AppLog.Level.INFO -> Log.INFO
                    AppLog.Level.WARN -> Log.WARN
                    AppLog.Level.ERROR -> Log.ERROR
                }
                Log.println(priority, LOGCAT_TAG, "$tag: $message" + (error?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
            },
            header = mapOf(
                "App" to "SuperVideo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}, board ${Build.BOARD})",
                "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "ABIs" to Build.SUPPORTED_ABIS.joinToString(),
                "SoC" to if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "n/a",
            ),
        )
        platform = AndroidPlatform(this) { settingsStore }
        settingsStore = SettingsStore(
            platform.dataDir,
            defaults = AppSettings(defaultUpscale = UpscaleSettings(preset = platform.defaultPreset)),
        )
        repository = JobRepository(platform.dataDir)
        jobRunner = AndroidJobRunner(this, JobService(platform, repository), settingsStore)
        jobRunner.restoreInterruptedJobs()
    }

    private companion object {
        const val LOGCAT_TAG = "SuperVideo"
    }
}
