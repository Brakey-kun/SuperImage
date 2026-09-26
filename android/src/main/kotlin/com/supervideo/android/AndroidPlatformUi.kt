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
import com.supervideo.BuildConfig
import com.supervideo.ui.PlatformUi
import java.nio.ByteBuffer

class AndroidPlatformUi(private val activity: Activity) : PlatformUi {

    override val versionName: String = BuildConfig.VERSION_NAME

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
