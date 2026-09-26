package com.supervideo.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.supervideo.R
import com.supervideo.core.job.JobManifest
import com.supervideo.core.job.JobStatus
import com.supervideo.core.pipeline.JobProgress
import com.supervideo.core.util.Durations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs one job in the foreground with a progress notification (adapted from SuperImage's RealESRGANWorker). */
class VideoUpscaleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as SuperVideoApplication
    private val runner = app.jobRunner
    private val repository = runner.service.repository
    private val notificationManager = NotificationManagerCompat.from(context)
    private val jobId = params.inputData.getString(JOB_ID) ?: throw IllegalArgumentException("JOB_ID missing")
    private val notificationId = jobId.hashCode()
    private val notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override suspend fun doWork(): Result {
        val manifest = repository.get(jobId)
        if (manifest == null || !manifest.status.isResumable) return Result.success()
        val cancel = runner.cancelFlag(jobId)
        if (cancel.get()) {
            repository.save(manifest.copy(status = JobStatus.CANCELLED))
            return Result.success()
        }

        createChannels()
        setForeground(foregroundInfo(buildProgressNotification(manifest, JobProgress(JobStatus.RUNNING))))
        val wakeLock = applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SuperVideo:upscale")
        wakeLock.acquire()
        val progress = runner.flowFor(jobId)
        return withContext(Dispatchers.IO) {
            val updates = launch {
                var lastUpdate = 0L
                var loggedBackend = false
                progress.collectLatest { p ->
                    if (!loggedBackend && p.activeBackend != null) {
                        Log.i(TAG, "job=$jobId backend=${p.activeBackend}")
                        loggedBackend = true
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 1000 || p.pausedReason != null) {
                        lastUpdate = now
                        notifyProgress(buildProgressNotification(manifest, p))
                    }
                }
            }
            try {
                val result = runner.service.execute(jobId, progress, cancel)
                if (result.status == JobStatus.DONE) notifyResult(result, success = true)
                Result.success()
            } catch (e: CancellationException) {
                // Stopped by WorkManager (constraints, quota): WorkManager retries; show the job as queued meanwhile.
                withContext(NonCancellable) {
                    repository.get(jobId)?.takeIf { it.status.isActive }?.let { repository.save(it.copy(status = JobStatus.QUEUED)) }
                    progress.value = progress.value.copy(status = JobStatus.QUEUED, etaMillis = null, pausedReason = null)
                }
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "job=$jobId failed", e)
                repository.get(jobId)?.let { notifyResult(it, success = false) }
                // Keep the rest of the queue running: failures are recorded in the manifest.
                Result.success()
            } finally {
                updates.cancel()
                runner.clearCancelFlag(jobId)
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun foregroundInfo(notification: Notification) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }

    private fun createChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(PROGRESS_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.progress_channel_name))
                .build()
        )
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(RESULT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(applicationContext.getString(R.string.result_channel_name))
                .build()
        )
    }

    private fun buildProgressNotification(manifest: JobManifest, progress: JobProgress): Notification {
        val total = maxOf(progress.estimatedFrames, progress.framesDone, 1)
        val text = when {
            progress.pausedReason != null -> "Paused: ${progress.pausedReason!!.label}"
            progress.status == JobStatus.MUXING -> "Muxing audio and video…"
            else -> buildString {
                append("${progress.framesDone}/$total")
                progress.etaMillis?.takeIf { progress.msPerFrame > 0 }?.let { append(" · ETA ${Durations.period(it)}") }
                progress.activeBackend?.let { append(" · ${it.displayName}") }
            }
        }
        return NotificationCompat.Builder(applicationContext, PROGRESS_CHANNEL_ID)
            .setContentTitle("Upscaling ${manifest.sourceName}")
            .setTicker("Upscaling ${manifest.sourceName}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.outline_photo_size_select_large_24)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityIntent())
            .setProgress(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), progress.framesDone.toInt(), progress.status == JobStatus.MUXING)
            .addAction(R.drawable.baseline_close_24, applicationContext.getString(R.string.cancel), cancelIntent())
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun notifyProgress(notification: Notification) {
        if (notificationPermission) notificationManager.notify(notificationId, notification)
    }

    @SuppressLint("MissingPermission")
    private fun notifyResult(manifest: JobManifest, success: Boolean) {
        // No result notification while the user is looking at the app.
        if (!notificationPermission ||
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        val builder = NotificationCompat.Builder(applicationContext, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.outline_photo_size_select_large_24)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (success) {
            val view = manifest.outputUri?.let { app.platform.viewIntent(it) }
            builder.setContentTitle("${manifest.sourceName} upscaled")
                .setContentText("Saved to Movies/${AndroidPlatform.OUTPUT_FOLDER}/${manifest.outputName}")
                .setContentIntent(
                    view?.let { PendingIntent.getActivity(applicationContext, notificationId, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
                        ?: mainActivityIntent()
                )
        } else {
            builder.setContentTitle("Upscaling ${manifest.sourceName} failed")
                .setContentText(manifest.error ?: "Unknown error")
                .setContentIntent(mainActivityIntent())
        }
        notificationManager.notify((SystemClock.elapsedRealtime() and 0x7fffffff).toInt(), builder.build())
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        applicationContext, notificationId,
        Intent(applicationContext, CancelJobReceiver::class.java).putExtra(JOB_ID, jobId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val JOB_ID = "job_id"
        const val TAG = "SuperVideo"
        private const val PROGRESS_CHANNEL_ID = "progress_ch"
        private const val RESULT_CHANNEL_ID = "result_ch"
    }
}

/** Notification "Cancel" action: cancels only this job, the queue continues. */
class CancelJobReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(VideoUpscaleWorker.JOB_ID) ?: return
        (context.applicationContext as SuperVideoApplication).jobRunner.cancel(jobId)
    }
}
