package com.example.messagesender

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules the periodic license re-check so revocation propagates to devices. */
object LicenseRefreshScheduler {

    private const val WORK_NAME = "license-refresh"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Lease lives 24h; refreshing every 6h keeps it valid while online and
        // detects a disabled token well within the lease window.
        val request = PeriodicWorkRequestBuilder<LicenseWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
