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
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Foreground service that sends a user-provided SMS message to a user-provided
 * phone number on a fixed interval until it is stopped.
 */
class SmsSenderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var phoneNumber: String = ""
    private var message: String = ""
    private var intervalMs: Long = DEFAULT_INTERVAL_MS
    private var sentCount = 0

    private val sendRunnable = object : Runnable {
        override fun run() {
            sendSms()
            handler.postDelayed(this, intervalMs)
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
                phoneNumber = intent?.getStringExtra(EXTRA_PHONE).orEmpty()
                message = intent?.getStringExtra(EXTRA_MESSAGE).orEmpty()
                intervalMs = intent?.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS)
                    ?: DEFAULT_INTERVAL_MS

                if (phoneNumber.isBlank() || message.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification())
                // Send once immediately, then keep repeating on the interval.
                handler.removeCallbacks(sendRunnable)
                handler.post(sendRunnable)
            }
        }
        return START_STICKY
    }

    private fun sendSms() {
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
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SmsSenderService"
        const val CHANNEL_ID = "sms_sender_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.example.messagesender.ACTION_STOP"
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_INTERVAL_MS = "extra_interval_ms"

        const val DEFAULT_INTERVAL_MS = 15_000L
    }
}
