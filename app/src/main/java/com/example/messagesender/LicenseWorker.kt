package com.example.messagesender

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Periodically re-validates the saved token so a token disabled server-side
 * takes effect on the device (in addition to the lease simply expiring). If the
 * server reports the token is invalid/disabled, the cached lease is cleared, the
 * automatic cycle is turned off, and any running sender is stopped.
 */
class LicenseWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val token = LicenseManager.savedToken(context)
        if (token.isBlank()) return Result.success()

        val result = LicenseManager.validate(context, token)
        when (result.outcome) {
            LicenseManager.Outcome.INVALID_OR_DISABLED -> {
                Log.i(TAG, "Token disabled server-side; revoking locally")
                LicenseManager.clearLease(context)
                SenderState.setCycleEnabled(context, false)
                stopSender(context)
            }
            LicenseManager.Outcome.VALID -> {
                Log.i(TAG, "License refreshed")
            }
            else -> {
                // Network/config errors: keep the existing lease. It will simply
                // expire on its own if the device stays offline long enough.
                Log.i(TAG, "License refresh skipped: ${result.outcome}")
            }
        }
        return Result.success()
    }

    private fun stopSender(context: Context) {
        context.stopService(Intent(context, SmsSenderService::class.java))
    }

    companion object {
        private const val TAG = "LicenseWorker"
    }
}
