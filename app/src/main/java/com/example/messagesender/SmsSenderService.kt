package com.example.messagesender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Foreground service that sends the message to the target number every
 * [INTERVAL_MS] milliseconds until it is stopped. A partial wake lock keeps the
 * CPU awake so the 15-second loop keeps firing even when the screen is off.
 */
class SmsSenderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var phoneNumber: String = ""
    private var message: String = ""
    private var sentCount = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val sendRunnable = object : Runnable {
        override fun run() {
            sendSms()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // A real user stop (notification button or the Stop button):
                // end the automatic cycle completely.
                SenderState.setCycleEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Use the intent extras when present; otherwise fall back to the
                // saved config. The fallback matters when START_STICKY makes the
                // system recreate the service with a null intent after a kill —
                // this lets it resume sending on its own.
                phoneNumber = intent?.getStringExtra(EXTRA_PHONE)
                    ?: SenderState.phone(this)
                message = intent?.getStringExtra(EXTRA_MESSAGE)
                    ?: SenderState.message(this)

                // Always call startForeground promptly: the service was started
                // with startForegroundService and must enter the foreground
                // before it can stop, or Android 12+ terminates it with an error.
                startForeground(NOTIFICATION_ID, buildNotification())

                val allowed = phoneNumber.isNotBlank() &&
                    message.isNotBlank() &&
                    SenderState.isCycleEnabled(this) &&
                    LicenseManager.hasValidLease(this)

                if (!allowed) {
                    if (phoneNumber.isNotBlank() && !LicenseManager.hasValidLease(this)) {
                        Toast.makeText(this, R.string.license_required, Toast.LENGTH_LONG).show()
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }

                acquireWakeLock()

                // Send once immediately, then keep repeating every 15 seconds.
                handler.removeCallbacks(sendRunnable)
                handler.post(sendRunnable)
            }
        }
        return START_STICKY
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

    private fun sendSms() {
        // Enforce the lease on every send so an expired/revoked license stops
        // sending even if the app has been offline.
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
            Log.i(TAG, "Sent SMS #$sentCount to $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            Toast.makeText(this, getString(R.string.send_failed, e.message), Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun buildNotification(): Notification {
        createChannel()

        val stopIntent = Intent(this, SmsSenderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
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
        handler.removeCallbacks(sendRunnable)
        releaseWakeLock()
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
        const val INTERVAL_MS = 15_000L
    }
}
