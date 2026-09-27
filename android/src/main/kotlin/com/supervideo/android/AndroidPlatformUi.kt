package com.supervideo.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import com.supervideo.BuildConfig
import com.supervideo.core.util.AppLog
import com.supervideo.ui.PlatformUi
import java.nio.ByteBuffer
import java.io.File

class AndroidPlatformUi(private val activity: Activity) : PlatformUi {

    override val versionName: String = BuildConfig.VERSION_NAME

    override val logsActionLabel: String = "Share logs"

    /** Shares the app log plus this app's logcat (incl. MNN native output and crash buffer) as one text file. */
    override fun openLogs() {
        Thread {
            runCatching {
                val dir = File(activity.cacheDir, "logs").apply { mkdirs() }
                val file = File(dir, "supervideo-logs.txt")
                val logcat = runCatching {
                    // No --pid: native crashes kill the process, so the failing run has another PID. Apps can
                    // only read their own UID's entries, so this stays private and includes previous runs.
                    ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", "main,system,crash")
                        .redirectErrorStream(true)
                        .start()
                        .inputStream.bufferedReader().use { it.readText() }
                }.getOrElse { "logcat unavailable: $it" }
                file.writeText(
                    "SuperVideo ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, " +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}\n\n" +
                        "==== app log ====\n${AppLog.collect()}\n\n==== logcat (this app, incl. previous runs) ====\n$logcat",
                )
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "SuperVideo logs")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.runOnUiThread { activity.startActivity(Intent.createChooser(send, "Share logs")) }
            }.onFailure { AppLog.e("UI", "Sharing logs failed", it) }
        }.start()
    }

    @Composable
    override fun rememberVideoPicker(onPicked: (uri: String, displayName: String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val name = DocumentFile.fromSingleUri(activity, uri)?.name ?: uri.lastPathSegment ?: "video.mp4"
                onPicked(uri.toString(), name)
            }
        }
        return { launcher.launch(arrayOf("video/*")) }
    }

    @Composable
    override fun rememberStartPreparation(): (String, (String?) -> Unit) -> Unit {
        val pending = remember { arrayOfNulls<(String?) -> Unit>(1) }
        // Notifications are optional: the job starts whatever the user answers.
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pending[0]?.invoke(null)
            pending[0] = null
        }
        return { _, onReady ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pending[0] = onReady
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onReady(null)
            }
        }
    }

    override fun rgbaToImageBitmap(buffer: ByteBuffer, width: Int, height: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer.duplicate().apply { clear() })
        return bitmap.asImageBitmap()
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        androidx.activity.compose.BackHandler(enabled, onBack)
    }

    @Composable
    override fun SystemBars(lightMode: Boolean) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = lightMode
                    isAppearanceLightNavigationBars = lightMode
                }
            }
        }
    }
}
