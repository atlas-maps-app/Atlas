package app.atlas.ui.local

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.atlas.R
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Notifications that are set for a time: "strawberries are back", "bins out tonight". Kept by key so each can be changed or cancelled. */
interface Reminders {
    /** Shows [title] and [text] at [at]; replaces an earlier reminder with the same [key]. */
    fun schedule(key: String, at: Instant, title: String, text: String)

    fun cancel(key: String)

    /** When the reminder [key] is set for, or null. */
    fun scheduledAt(key: String): Instant?

    /** The keys with this [prefix] that are set. */
    fun keys(prefix: String): List<String>
}

/** [Reminders] on WorkManager: a one-time job with a delay, surviving restarts, that posts the notification. */
class WorkReminders(private val context: Context, private val prefs: LocalPrefs, private val now: () -> Instant = { Instant.now() }) : Reminders {
    override fun schedule(key: String, at: Instant, title: String, text: String) {
        val delay = Duration.between(now(), at).coerceAtLeast(Duration.ZERO)
        val data = Data.Builder().putString(KEY, key).putString(TITLE, title).putString(TEXT, text).build()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).setInputData(data).build()
        WorkManager.getInstance(context).enqueueUniqueWork("local-reminder-$key", ExistingWorkPolicy.REPLACE, request)
        prefs.putString("reminder.$key", at.toEpochMilli().toString())
    }

    override fun cancel(key: String) {
        WorkManager.getInstance(context).cancelUniqueWork("local-reminder-$key")
        prefs.putString("reminder.$key", null)
    }

    override fun scheduledAt(key: String): Instant? = prefs.string("reminder.$key")?.toLongOrNull()?.let { Instant.ofEpochMilli(it) }?.takeIf { it.isAfter(now()) }

    override fun keys(prefix: String): List<String> = prefs.keys("reminder.$prefix").map { it.removePrefix("reminder.") }.filter { scheduledAt(it) != null }

    companion object {
        const val KEY = "key"
        const val TITLE = "title"
        const val TEXT = "text"
    }
}

/** Posts the notification of a [Reminders] job when its time comes. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(WorkReminders.KEY) ?: return Result.failure()
        LocalNotify.post(applicationContext, key.hashCode(), inputData.getString(WorkReminders.TITLE).orEmpty(), inputData.getString(WorkReminders.TEXT).orEmpty())
        LocalPrefs(applicationContext).putString("reminder.$key", null)
        return Result.success()
    }
}

/** The channel and the notification of the local package's reminders. */
object LocalNotify {
    const val CHANNEL = "local_reminders"

    fun post(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.local_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(id, n)
    }
}
