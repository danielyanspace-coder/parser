package com.example.messagesender

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Listens for incoming SMS and drives the trigger cycle (case-insensitive, from
 * any number) while the job is active:
 *  - "символ": reply "Ок", count it, pause sending until "успешно"; if the
 *    trigger arrived inside a window, mark the window-override so the job keeps
 *    going past the window end; when the count reaches the limit, stop the job.
 *  - "успешно": resume sending.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        val jobActive = SenderState.isCycleEnabled(context) &&
            SenderState.hasConfig(context) &&
            LicenseManager.hasValidLease(context)
        if (!jobActive) return

        when {
            body.contains(STOP_WORD, ignoreCase = true) -> handleTrigger(context, sender)
            body.contains(RESUME_WORD, ignoreCase = true) -> {
                if (SenderState.isPaused(context)) {
                    Log.i(TAG, "Resume word received; continuing")
                    SenderState.setPaused(context, false)
                }
            }
        }
    }

    private fun handleTrigger(context: Context, sender: String) {
        // Ignore a second trigger while already waiting for "успешно".
        if (SenderState.isPaused(context)) return

        val now = Calendar.getInstance()
        val windows = SenderState.windows(context)
        val insideWindow = ScheduleWindows.insideAny(windows, now)
        // Only react while sending is actually active right now.
        val activeNow = SenderState.isOverride(context) ||
            (windows.isEmpty() && now.timeInMillis >= SenderState.startAtMillis(context)) ||
            insideWindow
        if (!activeNow) {
            Log.i(TAG, "Trigger outside active window; ignoring")
            return
        }

        replyOk(context, sender)

        val count = SenderState.triggerCount(context) + 1
        SenderState.setTriggerCount(context, count)

        val limit = SenderState.triggerLimit(context)

        // A trigger received inside a window lets the job run past the window end
        // until the required number of triggers is completed. Only meaningful when
        // a limit is set (otherwise there is nothing to "finish").
        if (windows.isNotEmpty() && insideWindow && limit > 0) {
            SenderState.setOverride(context, true)
        }

        if (limit > 0 && count >= limit) {
            Log.i(TAG, "Trigger limit reached ($count/$limit); stopping job")
            SenderState.setCycleEnabled(context, false)
            context.stopService(Intent(context, SmsSenderService::class.java))
        } else {
            // Pause and wait for "успешно"; the service keeps running but idle.
            SenderState.setPaused(context, true)
        }
    }

    private fun replyOk(context: Context, destination: String) {
        val canSend = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!canSend) {
            Log.w(TAG, "SEND_SMS not granted; cannot send reply")
            return
        }

        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(destination, null, REPLY_TEXT, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply", e)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private const val STOP_WORD = "символ"
        private const val RESUME_WORD = "успешно"
        private const val REPLY_TEXT = "Ок"
    }
}
