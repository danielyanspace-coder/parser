package com.example.messagesender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired by AlarmManager at the scheduled start time. Starts the sender if the
 * job is still configured, licensed, and not manually stopped.
 */
class StartAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduleManager.ACTION_START) return

        if (!SenderState.isCycleEnabled(context)) {
            Log.i(TAG, "Scheduled start skipped: job was stopped")
            return
        }
        if (!SenderState.hasConfig(context)) {
            Log.i(TAG, "Scheduled start skipped: no configuration")
            return
        }
        if (!LicenseManager.hasValidLease(context)) {
            Log.i(TAG, "Scheduled start skipped: no valid license")
            return
        }
        Log.i(TAG, "Scheduled time reached; starting sender")
        ScheduleManager.startNow(context)
    }

    companion object {
        private const val TAG = "StartAlarmReceiver"
    }
}
