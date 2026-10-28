package io.github.duwenji.yorisoi.cycleguide.ways

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.cycleguide.ride.RideService
import java.util.concurrent.TimeUnit

/**
 * Searches ahead along the frequent ways (REQ-FREQUENT-WAYS F-6〜F-8, D-9): once a day, and once
 * right after a ride, so the way back is kept at the place ridden to. Only on a network that isn't
 * metered, with the battery not low, and never during a ride.
 */
class WayPrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CycleApp
        if (RideService.state.value != null) return Result.success()
        // The ways gone along last first: the likeliest to be ridden next.
        val ways = app.ways.ways.value.map { it.points }
        val searches = app.spots.prefetch(ways)
        Log.i(TAG, "Searched ahead $searches times along ${ways.size} ways")
        return Result.success()
    }

    companion object {
        private const val TAG = "WayPrefetch"
        private const val DAILY = "way-prefetch-daily"
        private const val AFTER_RIDE = "way-prefetch-after-ride"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Once a day, from the app's start on. */
        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<WayPrefetchWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Once, after a ride, when the network allows. */
        fun afterRide(context: Context) {
            val request = OneTimeWorkRequestBuilder<WayPrefetchWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(AFTER_RIDE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
