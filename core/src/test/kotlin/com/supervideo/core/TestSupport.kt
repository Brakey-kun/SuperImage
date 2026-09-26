package com.supervideo.core

import com.supervideo.core.model.ModelStore
import com.supervideo.core.upscale.NativeLibraries
import org.bytedeco.javacpp.Loader
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/** Native tests run only with `-Dsupervideo.nativeDir=<desktop/build/appResources>`. */
object TestNative {
    private val dir: File? = System.getProperty("supervideo.nativeDir")?.let(::File)

    fun requireNative() {
        assumeTrue(dir != null, "supervideo.nativeDir not set: native tests skipped")
        NativeLibraries.ensureLoaded { System.load(File(dir, "windows-x64/realesrgan.dll").absolutePath) }
    }

    val modelStore = ModelStore { model -> File(dir, "common/models/${model.fileName}").readBytes() }
}

object TestMedia {
    val dir: File = File(System.getProperty("supervideo.testMediaDir") ?: "build/test-media").apply { mkdirs() }

    private val ffmpeg: String by lazy { Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java) }
    private val ffprobe: String by lazy { Loader.load(org.bytedeco.ffmpeg.ffprobe::class.java) }

    fun ffmpeg(vararg args: String) = run(listOf(ffmpeg, "-hide_banner", "-loglevel", "error") + args)

    fun ffprobe(vararg args: String): String = run(listOf(ffprobe) + args)

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "Timed out: $command" }
        check(process.exitValue() == 0) { "Failed (${process.exitValue()}): $command\n$output" }
        return output
    }

    /** 3 s of 320x240 @ 30 fps H.264 + 440 Hz AAC. */
    val cfr: File by lazy {
        File(dir, "cfr.mp4").also {
            if (!it.isFile) ffmpeg(
                "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "3",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", it.absolutePath,
            )
        }
    }

    /** Like [cfr] with a 0.5 s timestamp gap after frame 44. */
    val vfr: File by lazy {
        File(dir, "vfr.mp4").also {
            if (!it.isFile) ffmpeg(
                // Input-side -t: exactly 90 frames, the gap then extends the stream to 3.5 s.
                "-y", "-f", "lavfi", "-t", "3", "-i", "testsrc2=size=320x240:rate=30",
                "-f", "lavfi", "-t", "3.5", "-i", "sine=frequency=440:sample_rate=44100",
                "-vf", "setpts=if(gt(N\\,44)\\,PTS+0.5/TB\\,PTS)", "-fps_mode", "passthrough",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", it.absolutePath,
            )
        }
    }

    fun videoPts(file: File): List<String> =
        ffprobe("-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts", "-of", "csv=p=0", file.absolutePath)
            .lines().map { it.trim().trimEnd(',') }.filter { it.isNotEmpty() }

    /** width,height,nb_read_frames,codec_name of the first video stream. */
    fun videoSummary(file: File): Map<String, String> =
        ffprobe(
            "-v", "error", "-count_frames", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,nb_read_frames,codec_name", "-of", "default=nw=1", file.absolutePath,
        ).lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    fun audioCodecs(file: File): List<String> =
        ffprobe("-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_name", "-of", "csv=p=0", file.absolutePath)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
}
