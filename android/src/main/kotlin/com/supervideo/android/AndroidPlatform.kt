package com.supervideo.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.supervideo.core.job.JobManifest
import com.supervideo.core.model.ModelStore
import com.supervideo.core.pipeline.PipelineGate
import com.supervideo.core.platform.OpenedSource
import com.supervideo.core.platform.Platform
import com.supervideo.core.platform.StagedSource
import com.supervideo.core.settings.SettingsStore
import com.supervideo.core.upscale.NativeLibraryLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class AndroidPlatform(
    private val context: Context,
    private val settings: () -> SettingsStore,
) : Platform {

    override val dataDir: File = context.filesDir

    override val nativeLoader = NativeLibraryLoader {
        System.loadLibrary("MNN_VK")
        System.loadLibrary("MNN_CL")
        System.loadLibrary("realesrgan")
    }

    override val modelStore = ModelStore { model -> context.assets.open(model.fileName).use { it.readBytes() } }

    override val defaultPreset: String = "fast"

    override val supportsPowerPausing: Boolean = true

    /** FFmpeg reads the picked document through its file descriptor (`fd:` protocol, no re-open). */
    override suspend fun openSource(pickedUri: String): OpenedSource = withContext(Dispatchers.IO) {
        val uri = Uri.parse(pickedUri)
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open $pickedUri")
        OpenedSource(
            path = "fd:${descriptor.fd}",
            displayName = displayName(uri),
            sizeBytes = descriptor.statSize,
            onClose = { descriptor.close() },
        )
    }

    /** Copies the document into the job directory so the job survives permission loss and process death. */
    override suspend fun stageSource(pickedUri: String, jobDir: File): StagedSource = withContext(Dispatchers.IO) {
        val uri = Uri.parse(pickedUri)
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "mp4").lowercase().filter(Char::isLetterOrDigit).ifEmpty { "mp4" }
        val target = File(jobDir, "source.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it, bufferSize = 1 shl 20) }
        } ?: throw IOException("Cannot read $pickedUri")
        StagedSource(target.absolutePath, name, target.length())
    }

    override suspend fun publishOutput(output: File, manifest: JobManifest): String = withContext(Dispatchers.IO) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, manifest.outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}${File.separator}$OUTPUT_FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { out -> output.inputStream().use { it.copyTo(out, 1 shl 20) } }
                    ?: throw IOException("Cannot write $uri")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), OUTPUT_FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            val target = newFileAutoSuffix(dir, manifest.outputName)
            output.copyTo(target)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
            target.absolutePath
        }
        output.delete()
        uri
    }

    override fun openOutput(uri: String) {
        context.startActivity(viewIntent(uri))
    }

    fun viewIntent(uri: String): Intent {
        val contentUri = if (uri.startsWith("/")) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(uri))
        } else {
            Uri.parse(uri)
        }
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun gate(): PipelineGate = AndroidPipelineGate(context) { settings().settings.value }

    private fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "video.mp4"

    private fun newFileAutoSuffix(dir: File, fileName: String): File {
        var file = File(dir, fileName)
        var suffix = 1
        val base = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "")
        while (file.exists()) {
            file = File(dir, "$base ($suffix)" + if (extension.isNotEmpty()) ".$extension" else "")
            suffix++
        }
        return file
    }

    companion object {
        const val OUTPUT_FOLDER = "SuperVideo"
    }
}
