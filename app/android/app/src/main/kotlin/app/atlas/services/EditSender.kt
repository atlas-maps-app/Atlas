package app.atlas.services

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.atlas.providers.ProviderException
import app.atlas.services
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends the pending edits once the user confirmed their review (FillIn's "Send", the painter's): with their own
 * OpenStreetMap account, in one changeset, as soon as the phone is on Wi-Fi ([wifiOnly], the board's "next time you are on
 * Wi-Fi") or at once on any network when they ask for it. WorkManager keeps the request across restarts; a failure of
 * the network is retried later, and the edits stay pending until a send goes through.
 */
object EditSender {
    private const val WORK = "atlas-osm-edits"
    internal const val COMMENT = "comment"

    fun schedule(context: Context, comment: String, wifiOnly: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<EditSendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putString(COMMENT, comment).build())
            .build()
        // APPEND_OR_REPLACE: a later review's send waits for the one before it, and sends what is pending then.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/** One send of [EditSender]: every pending edit, with the account's token, in one changeset. */
class EditSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = applicationContext.services
        // Signed out meanwhile: the edits wait for the next sign-in and its send.
        val token = services.osmAccount.token() ?: return Result.failure()
        val comment = inputData.getString(EditSender.COMMENT) ?: "Surveyed with Atlas"
        return try {
            services.edits.sendPending { edits -> services.osmEdits.send(token, edits, comment) }
            Result.success()
        } catch (e: ProviderException) {
            // OpenStreetMap refused (a sign-in it no longer accepts, a changed map twice): shown with the edits, not retried
            android.util.Log.w("Atlas", "OpenStreetMap edits: ${e.message}")
            Result.failure()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}
