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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that sends the message to the target number every
 * [INTERVAL_SECONDS] seconds until it is stopped. The repeating loop runs on a
 * dedicated timer thread (not the main looper) and a partial wake lock keeps the
 * CPU awake, so it keeps firing even when the screen is off.
 */
class SmsSenderService : Service() {

    private var phoneNumber: String = ""
    private var message: String = ""
    private var sentCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var executor: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // A real user stop (notification button or the Stop button).
            SenderState.setCycleEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        // A fresh start passes the phone/message as extras. A system restart via
        // START_STICKY passes a null intent — resume from the saved config, but
        // only if the job was not stopped by the user.
        val fromUser = intent?.hasExtra(EXTRA_PHONE) == true
        phoneNumber = intent?.getStringExtra(EXTRA_PHONE) ?: SenderState.phone(this)
        message = intent?.getStringExtra(EXTRA_MESSAGE) ?: SenderState.message(this)

        // Must call startForeground quickly after startForegroundService.
        startForeground(NOTIFICATION_ID, buildNotification())

        val configured = phoneNumber.isNotBlank() && message.isNotBlank()
        val licensed = LicenseManager.hasValidLease(this)
        val notStopped = fromUser || SenderState.isCycleEnabled(this)

        if (!configured || !licensed || !notStopped) {
            if (configured && !licensed) {
                Toast.makeText(this, R.string.license_required, Toast.LENGTH_LONG).show()
            }
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
        executor = Executors.newSingleThreadScheduledExecutor().also { exec ->
            // First send immediately, then every INTERVAL_SECONDS after each one
            // finishes. The task must never throw or the schedule would stop.
            exec.scheduleWithFixedDelay(
                { runCatching { sendOnce() }.onFailure { Log.e(TAG, "loop error", it) } },
                0,
                INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    private fun sendOnce() {
        // Stop if the license expired or was revoked.
        if (!LicenseManager.hasValidLease(this)) {
            Log.i(TAG, "Lease expired; stopping sender")
            stopSelf()
            return
        }
        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
            sentCount++
            SenderStatus.sentCount = sentCount
            SenderStatus.lastSentAt = System.currentTimeMillis()
            SenderStatus.lastError = null
            Log.i(TAG, "Sent SMS #$sentCount to $phoneNumber")
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
            acquire(6 * 60 * 60 * 1000L) // safety timeout: 6 hours
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
            .setContentText(getString(R.string.notification_text, phoneNumber))
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
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_MESSAGE = "extra_message"

        /** Fixed sending interval: 15 seconds. */
        const val INTERVAL_SECONDS = 15L
    }
}
