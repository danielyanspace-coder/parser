package com.example.messagesender

import android.content.Context

/**
 * Persists the sending configuration and live cycle state so [SmsSenderService]
 * and [SmsReceiver] share one source of truth even across process restarts.
 */
object SenderState {

    private const val PREFS = "sender_state"
    private const val KEY_PHONE = "phone"
    private const val KEY_MESSAGE = "message"
    private const val KEY_INTERVAL_MS = "interval_ms"
    private const val KEY_CYCLE_ENABLED = "cycle_enabled"

    private const val KEY_WINDOWS = "windows"
    private const val KEY_REPEAT_DAILY = "repeat_daily"
    private const val KEY_START_AT = "start_at_millis" // 0 = now
    private const val KEY_TRIGGER_LIMIT = "trigger_limit" // 0 = unlimited

    // Live runtime state
    private const val KEY_TRIGGER_COUNT = "trigger_count"
    private const val KEY_PAUSED = "paused"
    private const val KEY_OVERRIDE = "override_window"

    private const val DEFAULT_INTERVAL_MS = 15_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saves the full configuration, marks the job active, and resets live state. */
    fun start(
        context: Context,
        phone: String,
        message: String,
        intervalMs: Long,
        windows: List<Window>,
        repeatDaily: Boolean,
        startAtMillis: Long,
        triggerLimit: Int,
    ) {
        prefs(context).edit()
            .putString(KEY_PHONE, phone)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_INTERVAL_MS, intervalMs)
            .putString(KEY_WINDOWS, ScheduleWindows.serialize(windows))
            .putBoolean(KEY_REPEAT_DAILY, repeatDaily)
            .putLong(KEY_START_AT, startAtMillis)
            .putInt(KEY_TRIGGER_LIMIT, triggerLimit)
            .putInt(KEY_TRIGGER_COUNT, 0)
            .putBoolean(KEY_PAUSED, false)
            .putBoolean(KEY_OVERRIDE, false)
            .putBoolean(KEY_CYCLE_ENABLED, true)
            .apply()
    }

    fun setCycleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CYCLE_ENABLED, enabled).apply()
    }

    fun isCycleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CYCLE_ENABLED, false)

    fun phone(context: Context): String = prefs(context).getString(KEY_PHONE, "").orEmpty()
    fun message(context: Context): String = prefs(context).getString(KEY_MESSAGE, "").orEmpty()
    fun intervalMs(context: Context): Long =
        prefs(context).getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)

    fun windows(context: Context): List<Window> =
        ScheduleWindows.parse(prefs(context).getString(KEY_WINDOWS, "").orEmpty())

    fun repeatDaily(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REPEAT_DAILY, false)

    fun startAtMillis(context: Context): Long = prefs(context).getLong(KEY_START_AT, 0L)

    fun triggerLimit(context: Context): Int = prefs(context).getInt(KEY_TRIGGER_LIMIT, 0)

    fun triggerCount(context: Context): Int = prefs(context).getInt(KEY_TRIGGER_COUNT, 0)
    fun setTriggerCount(context: Context, v: Int) {
        prefs(context).edit().putInt(KEY_TRIGGER_COUNT, v).apply()
    }

    fun isPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_PAUSED, false)
    fun setPaused(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, v).apply()
    }

    fun isOverride(context: Context): Boolean = prefs(context).getBoolean(KEY_OVERRIDE, false)
    fun setOverride(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERRIDE, v).apply()
    }

    fun hasConfig(context: Context): Boolean =
        phone(context).isNotBlank() && message(context).isNotBlank()
}
