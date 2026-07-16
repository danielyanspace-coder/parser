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

/**
 * Listens for incoming SMS and drives the automatic cycle (case-insensitive,
 * from any number):
 *  - a message containing "символ" pauses [SmsSenderService] and replies "Ок"
 *    to the sender;
 *  - a message containing "успешно" resumes sending with the saved parameters,
 *    as long as the cycle is still enabled ([SenderState]).
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // A single SMS can arrive split into parts; join them into one body.
        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        when {
            body.contains(STOP_WORD, ignoreCase = true) -> {
                Log.i(TAG, "Stop word received from $sender; pausing sender and replying")
                pauseSenderService(context)
                replyOk(context, sender)
            }
            body.contains(RESUME_WORD, ignoreCase = true) -> {
                val allowed = SenderState.isCycleEnabled(context) &&
                    SenderState.hasConfig(context) &&
                    LicenseManager.hasValidLease(context)
                if (allowed) {
                    Log.i(TAG, "Resume word received from $sender; restarting sender")
                    resumeSenderService(context)
                } else {
                    Log.i(TAG, "Resume word received but cycle/license is not active; ignoring")
                }
            }
        }
    }

    private fun pauseSenderService(context: Context) {
        // stopService keeps the cycle flag enabled (unlike ACTION_STOP) and is
        // allowed from a background receiver, unlike a background startService.
        context.stopService(Intent(context, SmsSenderService::class.java))
    }

    private fun resumeSenderService(context: Context) {
        val startIntent = Intent(context, SmsSenderService::class.java).apply {
            putExtra(SmsSenderService.EXTRA_PHONE, SenderState.phone(context))
            putExtra(SmsSenderService.EXTRA_MESSAGE, SenderState.message(context))
        }
        // Receiving an SMS grants a temporary background foreground-service start
        // exemption, so this is allowed even when the app UI is not running.
        ContextCompat.startForegroundService(context, startIntent)
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
