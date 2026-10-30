package app.atlas.services

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.atlas.mapview.MapEngine
import app.atlas.providers.OsmEdits
import app.atlas.providers.OsmRefusedException
import app.atlas.providers.OsmSignedOutException
import app.atlas.providers.TagEdit
import app.atlas.services
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends the pending edits with the user's own OpenStreetMap account once they are signed in and the phone is online: in
 * one changeset, as soon as the phone has a network ([schedule]). Nothing goes before the user has signed in; signing in
 * schedules a send. WorkManager keeps the request across restarts: OpenStreetMap out of reach or busy is tried again
 * later, and the edits wait meanwhile, saying why (EditStatus).
 */
object EditSender {
    private const val WORK = "atlas-osm-edits"

    /** What a [send] came to. */
    enum class Outcome {
        /** Nothing was pending. */
        NOTHING,

        /** The batch went: its edits are sent, or in conflict with others' changes. */
        SENT,

        /** OpenStreetMap refused the batch; its edits say why and wait for the user. */
        REFUSED,

        /** Not signed in, or OpenStreetMap no longer accepts the sign-in: the edits wait for the next one. */
        SIGNED_OUT,

        /** OpenStreetMap out of reach or busy: tried again later. */
        RETRY,
    }

    /**
     * Sends the pending edits once the phone is online: at once when it is. A send asked for during another waits for it
     * and then sends what is pending.
     */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<EditSendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * One send: every pending edit of [queue] in one changeset through [osm], with [account]'s token, each edit's
     * outcome kept in the queue; [onMapped] gets each edit OpenStreetMap took (the map then draws it). A sign-in
     * OpenStreetMap no longer accepts signs the user out here too, without asking OpenStreetMap to withdraw it.
     */
    suspend fun send(queue: EditQueue, account: OsmAccount, osm: OsmEdits, onMapped: (EditQueue.Queued) -> Unit = {}): Outcome {
        if (queue.pending.isEmpty()) return Outcome.NOTHING
        val token = account.token() ?: return Outcome.SIGNED_OUT
        return try {
            val done = queue.sendPending { batch -> osm.send(token, batch.map { it.edit }, comment(batch.map { it.edit })) }
                ?: return Outcome.NOTHING
            done.filter { it.state == EditQueue.State.SENT }.forEach(onMapped)
            Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (_: OsmSignedOutException) {
            account.signOut(withdraw = false)
            Outcome.SIGNED_OUT
        } catch (_: OsmRefusedException) {
            Outcome.REFUSED
        } catch (_: IOException) {
            Outcome.RETRY
        }
    }

    /**
     * The changeset's comment for [edits]: what the answers filled in, and on how many buildings when more than one
     * ("Building colour and roof shape of 2 buildings, as seen from the street").
     */
    fun comment(edits: List<TagEdit>): String {
        val keys = edits.flatMap { it.set.keys }.toSet()
        val what = buildList {
            if ("building:colour" in keys) add("building colour")
            if ("roof:shape" in keys) add("roof shape")
        }.ifEmpty { listOf("building details") }.joinToString(" and ").replaceFirstChar { it.uppercase() }
        val buildings = edits.map { it.type to it.id }.toSet().size
        return if (buildings > 1) "$what of $buildings buildings, as seen from the street" else "$what as seen from the street"
    }
}

/** One send of [EditSender]: every pending edit, with the account's token, in one changeset. */
class EditSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = applicationContext.services
        // what OpenStreetMap took is drawn as mapped at once: the windows that hold it built again with it
        val outcome = EditSender.send(services.edits, services.osmAccount, services.osmEdits) { MapEngine.edited(it.lat, it.lon) }
        return if (outcome == EditSender.Outcome.RETRY) Result.retry() else Result.success()
    }
}
