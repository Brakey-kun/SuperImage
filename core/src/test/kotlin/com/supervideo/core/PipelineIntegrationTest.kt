package com.supervideo.core

import com.supervideo.core.job.JobManifest
import com.supervideo.core.job.JobRepository
import com.supervideo.core.job.JobStatus
import com.supervideo.core.job.SegmentStatus
import com.supervideo.core.model.UpscaleModel
import com.supervideo.core.pipeline.JobProgress
import com.supervideo.core.pipeline.PipelineGate
import com.supervideo.core.pipeline.VideoUpscalePipeline
import com.supervideo.core.settings.RateControl
import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.upscale.Backend
import com.supervideo.core.util.moveTo
import com.supervideo.core.video.VideoProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineIntegrationTest {

    private lateinit var dataDir: File
    private lateinit var repository: JobRepository
    private lateinit var pipeline: VideoUpscalePipeline

    private val settings = UpscaleSettings(
        model = UpscaleModel.GENERAL_X2V3,
        backend = Backend.CPU,
        tileSize = 128,
        segmentFrames = 30,
        rateControl = RateControl.Crf(28),
        preset = "ultrafast",
    )

    @BeforeTest
    fun setUp() {
        TestNative.requireNative()
        dataDir = Files.createTempDirectory("sv-pipeline").toFile()
        repository = JobRepository(dataDir)
        pipeline = VideoUpscalePipeline(repository, TestNative.modelStore)
    }

    @AfterTest
    fun tearDown() {
        if (::dataDir.isInitialized) dataDir.deleteRecursively()
    }

    private fun createJob(source: File): JobManifest {
        val info = VideoProbe.probe(source.absolutePath)
        return repository.create(repository.newJobId(), source.absolutePath, source.name, info, settings, null)
    }

    private suspend fun run(
        manifest: JobManifest,
        cancel: AtomicBoolean = AtomicBoolean(false),
        beforePublish: (JobManifest) -> Unit = {},
    ): JobManifest = pipeline.run(manifest, PipelineGate.ALWAYS_RUN, MutableStateFlow(JobProgress()), cancel) { output, m ->
        beforePublish(m)
        val destination = File(dataDir, m.outputName)
        output.moveTo(destination)
        destination.absolutePath
    }

    private fun assertSameTiming(source: File, output: File) {
        val summary = TestMedia.videoSummary(output)
        assertEquals("640", summary["width"])
        assertEquals("480", summary["height"])
        assertEquals("90", summary["nb_read_frames"])
        assertEquals("h264", summary["codec_name"])
        assertEquals(listOf("aac"), TestMedia.audioCodecs(output))
        assertEquals(TestMedia.videoPts(source), TestMedia.videoPts(output))
    }

    @Test
    fun cfr() = runBlocking {
        val source = TestMedia.cfr
        val done = run(createJob(source))
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(3, done.segments.count { it.status == SegmentStatus.DONE })
        assertEquals(90, done.framesDone)
        assertSameTiming(source, File(done.outputUri!!))
    }

    @Test
    fun vfr() = runBlocking {
        val source = TestMedia.vfr
        val sourcePts = TestMedia.videoPts(source)
        // The source really has the gap (otherwise this test proves nothing).
        val deltas = sourcePts.map { it.toLong() }.zipWithNext { a, b -> b - a }
        assertTrue(deltas.distinct().size > 1, "source is not VFR: $deltas")
        assertEquals(90, sourcePts.size)

        val done = run(createJob(source))
        assertEquals(JobStatus.DONE, done.status)
        assertSameTiming(source, File(done.outputUri!!))
    }

    @Test
    fun resume() = runBlocking {
        val source = TestMedia.cfr
        val job = createJob(source)
        val cancel = AtomicBoolean(false)
        val watcher = launch {
            repository.observe().first { jobs ->
                jobs.firstOrNull { it.jobId == job.jobId }?.segments?.count { it.status == SegmentStatus.DONE } == 2
            }
            cancel.set(true)
        }
        val cancelled = run(job, cancel)
        watcher.cancel()
        assertEquals(JobStatus.CANCELLED, cancelled.status)
        val saved = repository.get(job.jobId)!!
        assertEquals(JobStatus.CANCELLED, saved.status)
        assertEquals(2, saved.segments.size)
        assertTrue(saved.segments.all { it.status == SegmentStatus.DONE })
        val jobDir = repository.jobDir(job.jobId)
        assertFalse(jobDir.listFiles()!!.any { it.name.endsWith(".part") }, "leftover .part files")
        val stamps = saved.segments.associate { it.file to File(jobDir, it.file).lastModified() }

        var untouched = false
        val done = run(saved) { m ->
            untouched = stamps.all { (file, stamp) -> File(jobDir, file).lastModified() == stamp }
            assertEquals(saved.segments, m.segments.take(2))
        }
        assertTrue(untouched, "DONE segments were re-encoded")
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(90, done.framesDone)
        assertSameTiming(source, File(done.outputUri!!))
    }
}
