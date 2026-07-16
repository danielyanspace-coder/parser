package com.example.messagesender

import android.content.Context

/**
 * Persists the last sending configuration and whether the automatic
 * "символ" / "успешно" cycle is currently enabled. Stored in SharedPreferences
 * so [SmsReceiver] and [StartAlarmReceiver] can (re)start sending even when the
 * app UI is not running. The interval is fixed at 15 seconds.
 */
object SenderState {

    private const val PREFS = "sender_state"
    private const val KEY_PHONE = "phone"
    private const val KEY_MESSAGE = "message"
    private const val KEY_CYCLE_ENABLED = "cycle_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saves the current sending parameters and marks the cycle as enabled. */
    fun save(context: Context, phone: String, message: String) {
        prefs(context).edit()
            .putString(KEY_PHONE, phone)
            .putString(KEY_MESSAGE, message)
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

    fun hasConfig(context: Context): Boolean =
        phone(context).isNotBlank() && message(context).isNotBlank()
}
