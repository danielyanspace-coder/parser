package com.example.messagesender

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts the sender at a chosen date/time using AlarmManager. If the chosen time
 * is now or in the past, sending starts immediately.
 */
object ScheduleManager {

    private const val TAG = "ScheduleManager"
    const val ACTION_START = "com.example.messagesender.ACTION_START"
    private const val REQUEST_CODE = 100

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, StartAlarmReceiver::class.java).apply {
            action = ACTION_START
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Schedules (or immediately triggers) the start. Params must already be saved. */
    fun scheduleStart(context: Context, triggerAtMillis: Long) {
        if (triggerAtMillis <= System.currentTimeMillis()) {
            startNow(context)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent(context)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm not permitted; using inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPendingIntent(context))
    }

    /** Starts the foreground sender using the saved parameters. */
    fun startNow(context: Context) {
        if (!SenderState.hasConfig(context)) return
        val intent = Intent(context, SmsSenderService::class.java).apply {
            putExtra(SmsSenderService.EXTRA_PHONE, SenderState.phone(context))
            putExtra(SmsSenderService.EXTRA_MESSAGE, SenderState.message(context))
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
