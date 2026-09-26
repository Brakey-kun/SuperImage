package com.supervideo.core

import com.supervideo.core.job.JobManifest
import com.supervideo.core.job.JobStatus
import com.supervideo.core.job.SegmentEntry
import com.supervideo.core.job.SegmentStatus
import com.supervideo.core.model.UpscaleModel
import com.supervideo.core.settings.AppSettings
import com.supervideo.core.settings.RateControl
import com.supervideo.core.settings.SettingsStore
import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.upscale.Backend
import com.supervideo.core.util.AppJson
import com.supervideo.core.video.AudioStreamInfo
import com.supervideo.core.video.Rational
import com.supervideo.core.video.VideoInfo
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class JobManifestTest {

    private val info = VideoInfo(
        width = 1920, height = 1080, rotationDegrees = 90, streamIndex = 0,
        timeBase = Rational(1, 15360), avgFrameRate = Rational(30000, 1001), nbFrames = 900,
        durationUs = 30_030_000, startPts = 0, videoCodecName = "h264",
        audioStreams = listOf(AudioStreamInfo(1, "aac", 86018)), sizeBytes = 123, modifiedAt = 456,
    )

    @Test
    fun manifestRoundTripsIncludingSealedRateControl() {
        for (rc in listOf(RateControl.Crf(23), RateControl.Bitrate(8000))) {
            val manifest = JobManifest(
                jobId = "20260101-000000-abcd", createdAt = 1, sourcePath = "/x.mp4", sourceName = "x.mp4",
                source = info,
                settings = UpscaleSettings(model = UpscaleModel.ANIME_VIDEO_V3, rateControl = rc, backend = Backend.CPU, trimStartUs = 5),
                outputName = "x_anime.mp4", outputWidth = 4320, outputHeight = 7680, estimatedFrames = 900,
                segments = listOf(SegmentEntry(0, "seg_000000.mp4", 0, 0, 290, 30, SegmentStatus.DONE)),
                status = JobStatus.CANCELLED, warnings = listOf("w"),
            )
            val json = AppJson.encodeToString(JobManifest.serializer(), manifest)
            assertEquals(manifest, AppJson.decodeFromString(JobManifest.serializer(), json))
        }
    }

    @Test
    fun corruptSettingsFileYieldsDefaults() {
        val dir = Files.createTempDirectory("sv-settings").toFile()
        dir.resolve("settings.json").writeText("{ not json")
        assertEquals(AppSettings(), SettingsStore(dir).settings.value)

        val store = SettingsStore(dir)
        store.update { it.copy(pauseOnThermal = false) }
        assertEquals(false, SettingsStore(dir).settings.value.pauseOnThermal)
        dir.deleteRecursively()
    }

    @Test
    fun rescaleRoundsToNearest() {
        assertEquals(512, Rational.rescale(1, Rational(1, 30), Rational(1, 15360)))
        assertEquals(33_367, Rational.rescale(1001, Rational(1, 30000), Rational.MICROSECONDS))
        assertEquals(-33_367, Rational.rescale(-1001, Rational(1, 30000), Rational.MICROSECONDS))
        assertEquals(Long.MAX_VALUE / 1000 * 3, Rational.rescale(Long.MAX_VALUE / 1000, Rational(3, 1), Rational(1, 1)))
    }
}
