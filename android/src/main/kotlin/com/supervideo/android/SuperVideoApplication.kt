package com.supervideo.android

import android.app.Application
import com.supervideo.core.job.JobRepository
import com.supervideo.core.job.JobService
import com.supervideo.core.settings.AppSettings
import com.supervideo.core.settings.SettingsStore
import com.supervideo.core.settings.UpscaleSettings

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
        platform = AndroidPlatform(this) { settingsStore }
        settingsStore = SettingsStore(
            platform.dataDir,
            defaults = AppSettings(defaultUpscale = UpscaleSettings(preset = platform.defaultPreset)),
        )
        repository = JobRepository(platform.dataDir)
        jobRunner = AndroidJobRunner(this, JobService(platform, repository), settingsStore)
        jobRunner.restoreInterruptedJobs()
    }
}
