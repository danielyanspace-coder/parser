package com.example.messagesender

import android.content.Context

/**
 * Persists the last sending configuration and whether the automatic
 * "символ" / "успешно" cycle is currently enabled. Stored in SharedPreferences
 * so [SmsReceiver] can resume sending even when the app UI is not running.
 */
object SenderState {

    private const val PREFS = "sender_state"
    private const val KEY_PHONE = "phone"
    private const val KEY_MESSAGE = "message"
    private const val KEY_INTERVAL_MS = "interval_ms"
    private const val KEY_CYCLE_ENABLED = "cycle_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saves the current sending parameters and marks the cycle as enabled. */
    fun save(context: Context, phone: String, message: String, intervalMs: Long) {
        prefs(context).edit()
            .putString(KEY_PHONE, phone)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_INTERVAL_MS, intervalMs)
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
        prefs(context).getLong(KEY_INTERVAL_MS, SmsSenderService.DEFAULT_INTERVAL_MS)

    fun hasConfig(context: Context): Boolean =
        phone(context).isNotBlank() && message(context).isNotBlank()
}
