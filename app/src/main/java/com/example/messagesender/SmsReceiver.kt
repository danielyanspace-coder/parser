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
 * Listens for incoming SMS. When a received message contains the trigger word
 * ("символ", case-insensitive), it stops [SmsSenderService] and replies "Ок"
 * to the number the message came from.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // A single SMS can arrive split into parts; join them into one body.
        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        if (!body.contains(TRIGGER_WORD, ignoreCase = true)) return

        Log.i(TAG, "Trigger word received from $sender; stopping sender and replying")

        stopSenderService(context)
        replyOk(context, sender)
    }

    private fun stopSenderService(context: Context) {
        val stopIntent = Intent(context, SmsSenderService::class.java).apply {
            action = SmsSenderService.ACTION_STOP
        }
        context.startService(stopIntent)
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
        private const val TRIGGER_WORD = "символ"
        private const val REPLY_TEXT = "Ок"
    }
}
