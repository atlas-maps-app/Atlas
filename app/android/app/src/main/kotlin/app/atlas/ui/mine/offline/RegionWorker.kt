package app.atlas.ui.mine.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import app.atlas.R
import app.atlas.services
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Schedules region downloads with WorkManager: unique per region, Wi-Fi only when asked, picked up again by the system. */
class WorkRegionScheduler(private val context: Context) : RegionScheduler {
    override fun schedule(id: String, wifiOnly: Boolean, update: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<RegionWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString(RegionWorker.ID, id).putBoolean(RegionWorker.UPDATE, update).build())
            .addTag(TAG)
            .build()
        // REPLACE, so asking again (a different Wi-Fi setting, an update after a download) starts from the new request.
        WorkManager.getInstance(context).enqueueUniqueWork(work(id), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(work(id))
    }

    private fun work(id: String) = "region-$id"

    companion object {
        const val TAG = "atlas-region"
    }
}

/**
 * Takes one region offline in the background: a foreground worker with a progress notification ("Amsterdam · 42 %")
 * that finishes the region's layers through the app's [RegionEngine]. A failure is shown on the region's card, and the
 * work is retried later (the files already fetched are kept and resumed).
 */
class RegionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val region = inputData.getString(ID) ?: return Result.failure()
        val manager = applicationContext.services.mine.offline
        val name = manager.engine.details.value.firstOrNull { it.id == region }?.name ?: region
        runCatching { setForeground(info(region, name, 0f)) }
        return coroutineScope {
            val watcher = launch {
                // Once per whole percent: a download reports many times a second and the system drops notification updates beyond a few.
                manager.details.map { list -> list.firstOrNull { it.id == region }?.progress?.fraction?.let { (it * 100).toInt() } }.distinctUntilChanged().collect { percent ->
                    if (percent != null) runCatching { setForeground(info(region, name, percent / 100f)) }
                }
            }
            try {
                val result = manager.run(region, inputData.getBoolean(UPDATE, false))
                when {
                    result.isSuccess -> Result.success()
                    runAttemptCount < MAX_ATTEMPTS && result.exceptionOrNull().let { it is java.io.IOException && it !is RegionException } -> Result.retry()
                    else -> Result.failure()
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun info(region: String, name: String, fraction: Float): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.mine_offline_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(this.id)
        val percent = (fraction * 100).toInt().coerceIn(0, 100)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.mine_offline_notification, name))
            .setContentText("$percent %")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, applicationContext.getString(R.string.mine_offline_cancel), cancel)
            .build()
        // One notification per region (never id 0, which a foreground service refuses).
        val nid = NOTIFICATION_BASE + (region.hashCode() and 0xffff)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(nid, n)
    }

    companion object {
        const val ID = "id"
        const val UPDATE = "update"
        const val CHANNEL = "offline"
        private const val NOTIFICATION_BASE = 0x4F00_0000
        private const val MAX_ATTEMPTS = 5
    }
}
