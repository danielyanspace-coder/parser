package com.example.messagesender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that drives the whole sending job: it sends on the chosen
 * interval, but only while "allowed" (inside a send window, or immediately/at the
 * scheduled time when no windows are set), pauses while waiting for "успешно",
 * and stops when the trigger limit is reached or the windows are done.
 *
 * The loop runs on a dedicated timer thread and a partial wake lock keeps the CPU
 * awake so it keeps ticking with the screen off.
 */
class SmsSenderService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var executor: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SenderState.setCycleEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val ok = SenderState.hasConfig(this) &&
            SenderState.isCycleEnabled(this) &&
            LicenseManager.hasValidLease(this)
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        SenderStatus.reset()
        SenderStatus.running = true
        startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        executor?.shutdownNow()
        executor = Executors.newSingleThreadScheduledExecutor()
        scheduleTick(0)
    }

    private fun scheduleTick(delayMs: Long) {
        val exec = executor ?: return
        if (exec.isShutdown) return
        exec.schedule(
            { runCatching { tick() }.onFailure { Log.e(TAG, "tick error", it) } },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun tick() {
        if (!SenderState.isCycleEnabled(this) || !LicenseManager.hasValidLease(this)) {
            finishJob()
            return
        }

        val limit = SenderState.triggerLimit(this)
        val count = SenderState.triggerCount(this)
        if (limit > 0 && count >= limit) {
            finishJob()
            return
        }

        // Paused while waiting for the "успешно" reply.
        if (SenderState.isPaused(this)) {
            scheduleTick(IDLE_MS)
            return
        }

        val now = Calendar.getInstance()
        val windows = SenderState.windows(this)
        val override = SenderState.isOverride(this)
        val allowed = when {
            override -> true
            windows.isEmpty() -> now.timeInMillis >= SenderState.startAtMillis(this)
            else -> ScheduleWindows.insideAny(windows, now)
        }

        if (allowed) {
            sendOnce()
            scheduleTick(SenderState.intervalMs(this))
        } else {
            // Outside a window and not overriding. If windows don't repeat and the
            // day's windows are over, the job is done.
            if (windows.isNotEmpty() && !SenderState.repeatDaily(this) &&
                ScheduleWindows.pastAllWindowsToday(windows, now)
            ) {
                finishJob()
                return
            }
            scheduleTick(IDLE_MS)
        }
    }

    private fun finishJob() {
        SenderState.setCycleEnabled(this, false)
        stopSelf()
    }

    private fun sendOnce() {
        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val phone = SenderState.phone(this)
            val message = SenderState.message(this)
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null)
            }
            SenderStatus.sentCount += 1
            SenderStatus.lastSentAt = System.currentTimeMillis()
            SenderStatus.lastError = null
            Log.i(TAG, "Sent SMS #${SenderStatus.sentCount} to $phone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            SenderStatus.lastError = e.message ?: e.javaClass.simpleName
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlfaSms::sender").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // safety timeout: 12 hours
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        createChannel()

        val stopIntent = Intent(this, SmsSenderService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, SenderState.phone(this)))
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop),
                stopPendingIntent
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        executor?.shutdownNow()
        executor = null
        releaseWakeLock()
        SenderStatus.running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SmsSenderService"
        const val CHANNEL_ID = "sms_sender_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.example.messagesender.ACTION_STOP"

        /** How often to re-check while idle (outside a window or paused). */
        private const val IDLE_MS = 10_000L
    }
}
