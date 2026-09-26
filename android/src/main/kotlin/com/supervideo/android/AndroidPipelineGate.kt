package com.supervideo.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.supervideo.core.pipeline.PauseReason
import com.supervideo.core.pipeline.PipelineGate
import com.supervideo.core.settings.AppSettings
import kotlinx.coroutines.delay

/**
 * Holds the pipeline between frames while the device is too hot (pause at SEVERE, resume at
 * MODERATE or cooler) or the battery is below 15% and not charging.
 */
class AndroidPipelineGate(
    private val context: Context,
    private val settings: () -> AppSettings,
) : PipelineGate {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var thermalPaused = false
    private var batteryCheckedAt = 0L
    private var batteryLow = false

    override suspend fun awaitRunnable(report: (PauseReason?) -> Unit) {
        var reported = false
        while (true) {
            val reason = currentReason()
            if (reason == null) {
                if (reported) report(null)
                return
            }
            if (!reported) {
                report(reason)
                reported = true
            }
            delay(if (reason == PauseReason.THERMAL) 5_000 else BATTERY_RECHECK_MS)
        }
    }

    private fun currentReason(): PauseReason? {
        if (settings().pauseOnThermal && isThermalLimited()) return PauseReason.THERMAL
        if (isBatteryLow()) return PauseReason.BATTERY
        return null
    }

    private fun isThermalLimited(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val status = powerManager.currentThermalStatus
        thermalPaused = if (thermalPaused) {
            status > PowerManager.THERMAL_STATUS_MODERATE
        } else {
            status >= PowerManager.THERMAL_STATUS_SEVERE
        }
        return thermalPaused
    }

    private fun isBatteryLow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (batteryCheckedAt == 0L || now - batteryCheckedAt >= BATTERY_RECHECK_MS) {
            batteryCheckedAt = now
            val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
            batteryLow = percent < 15 && !plugged
        }
        return batteryLow
    }

    private companion object {
        const val BATTERY_RECHECK_MS = 30_000L
    }
}
