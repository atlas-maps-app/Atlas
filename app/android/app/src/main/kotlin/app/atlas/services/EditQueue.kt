package app.atlas.services

import app.atlas.providers.EditsSent
import app.atlas.providers.HttpException
import app.atlas.providers.OsmRefusedException
import app.atlas.providers.OsmSignedOutException
import app.atlas.providers.OsmType
import app.atlas.providers.TagEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * The user's edits to OpenStreetMap on this phone (FillIn, and the painter after it). Each waits here until it is sent with
 * the user's own account, once they are signed in and online, in one changeset with the others pending (EditSender). The
 * map draws an edit as mapped only once OpenStreetMap took it ([mapped]), until the map's data carries it; one still
 * waiting shows as waiting, and one that could not go says why ([EditStatus]). One JSON file, written whole through a
 * temporary file renamed over it, so an edit survives a restart. Writes are quick and blocking: call them off the main
 * thread.
 */
class EditQueue private constructor(private val file: File, private val clock: () -> Instant) {
    /**
     * Where an edit stands: waiting to be sent; taken by OpenStreetMap; in conflict with what others mapped meanwhile (its
     * element no longer holds what the user saw, or changed under the upload twice); refused by OpenStreetMap.
     */
    enum class State { PENDING, SENT, CONFLICT, REFUSED }

    /** Why the last try to send a waiting edit failed, to be tried again: OpenStreetMap out of reach, or busy (429, 5xx). */
    enum class Trouble { UNREACHABLE, BUSY }

    /**
     * One edit: its local [id], the [edit], what it reads as ([label], "Leliegracht 49 · building:colour"), where its
     * element stands ([lat], [lon]), when it was made, where it stands, the changeset that carried it and when
     * ([sentAt]), OpenStreetMap's [reason] for a refusal, and the [trouble] of the last try while it waits.
     */
    data class Queued(
        val id: Long,
        val edit: TagEdit,
        val label: String,
        val lat: Double,
        val lon: Double,
        val at: Instant,
        val state: State,
        val changeset: Long? = null,
        val sentAt: Instant? = null,
        val reason: String? = null,
        val trouble: Trouble? = null,
    )

    private val lock = Any()
    private val items = MutableStateFlow<List<Queued>>(emptyList())

    /** The edits of the send under way, which cannot be taken back meanwhile. */
    private val sending = HashSet<Long>()

    /** Every edit, oldest first; it changes after each write. */
    val all: StateFlow<List<Queued>> = items.asStateFlow()

    /** The edits still to send, oldest first. */
    val pending: List<Queued> get() = items.value.filter { it.state == State.PENDING }

    /** The edits OpenStreetMap took: the map draws them as mapped until its data carries them. */
    val mapped: List<Queued> get() = items.value.filter { it.state == State.SENT }

    /**
     * Queues [edit] of the element at [lat] [lon], described as [label]. An earlier edit of the same element that ran
     * into others' changes or was refused gives way to it.
     */
    fun add(edit: TagEdit, label: String, lat: Double, lon: Double): Queued = synchronized(lock) {
        val q = Queued((items.value.maxOfOrNull { it.id } ?: 0) + 1, edit, label, lat, lon, clock(), State.PENDING)
        val stale = { o: Queued -> (o.state == State.CONFLICT || o.state == State.REFUSED) && o.edit.type == edit.type && o.edit.id == edit.id }
        write(items.value.filterNot(stale) + q)
        q
    }

    /** Takes back unsent edit [id] (waiting, in conflict or refused); false where it is gone, sent, or being sent now. */
    fun undo(id: Long): Boolean = synchronized(lock) {
        val list = items.value
        if (id in sending || list.none { it.id == id && it.state != State.SENT }) return false
        write(list.filterNot { it.id == id })
        true
    }

    /** Sends refused edit [id] again with the next send; false unless it was refused. */
    fun retry(id: Long): Boolean = synchronized(lock) {
        val list = items.value
        if (list.none { it.id == id && it.state == State.REFUSED }) return false
        write(list.map { if (it.id == id) it.copy(state = State.PENDING, reason = null) else it })
        true
    }

    /** Forgets every edit, sent ones too ("Delete everything"), but those of a send under way. */
    fun clear() = synchronized(lock) { write(items.value.filter { it.id in sending }) }

    /**
     * Sends the pending edits in one go through [send] (OsmEdits.send with the user's token and the batch's comment),
     * then keeps what came of each: sent with its changeset, or a conflict where its element changed what the user saw.
     * A refusal marks them refused with OpenStreetMap's reason; a server out of reach or busy leaves them pending with
     * that [Trouble]; a sign-in no longer accepted leaves them pending as they were. Either way the failure is thrown on.
     * Returns the batch as it ended, or null when nothing was pending.
     */
    suspend fun sendPending(send: suspend (List<Queued>) -> EditsSent): List<Queued>? {
        val batch = synchronized(lock) { pending.filter { it.id !in sending }.also { b -> b.forEach { sending += it.id } } }
        if (batch.isEmpty()) return null
        val ids = batch.map { it.id }.toSet()
        try {
            val result = send(batch)
            return synchronized(lock) {
                val applied = result.applied.toMutableList()
                val conflicts = result.conflicts.toMutableList()
                val now = clock()
                val outcome = HashMap<Long, Queued>()
                for (q in batch) {
                    outcome[q.id] = when {
                        applied.remove(q.edit) -> q.copy(state = State.SENT, changeset = result.changeset, sentAt = now, trouble = null)
                        conflicts.remove(q.edit) -> q.copy(state = State.CONFLICT, trouble = null)
                        else -> q.copy(trouble = null)
                    }
                }
                write(items.value.map { outcome[it.id] ?: it })
                batch.map { outcome.getValue(it.id) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OsmSignedOutException) {
            throw e
        } catch (e: OsmRefusedException) {
            synchronized(lock) { write(items.value.map { if (it.id in ids) it.copy(state = State.REFUSED, reason = e.reason, trouble = null) else it }) }
            throw e
        } catch (e: IOException) {
            val trouble = if (e is HttpException) Trouble.BUSY else Trouble.UNREACHABLE
            synchronized(lock) { write(items.value.map { if (it.id in ids) it.copy(trouble = trouble) else it }) }
            throw e
        } finally {
            synchronized(lock) { sending -= ids }
        }
    }

    private fun write(list: List<Queued>) {
        val json = buildJsonObject { put("edits", buildJsonArray { list.forEach { add(toJson(it)) } }) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(JsonObject.serializer(), json))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not keep the edits in $file")
        }
        items.value = list
    }

    companion object {
        /**
         * How long a sent edit is kept for the map, which draws it until its data carries it: the windows' data and the
         * country's tiles are weeks newer by then, and an edit the data carries changes nothing.
         */
        val KEEP_SENT: Duration = Duration.ofDays(90)

        /**
         * The queue kept in [file] (made on the first edit), read now, without the edits sent longer than [KEEP_SENT]
         * ago. A file that cannot be read starts it empty.
         */
        fun open(file: File, clock: () -> Instant = Instant::now): EditQueue {
            val q = EditQueue(file, clock)
            val since = clock().minus(KEEP_SENT)
            q.items.value = runCatching { read(file) }.getOrDefault(emptyList()).filterNot { it.state == State.SENT && it.sentAt?.isBefore(since) == true }
            return q
        }

        private fun read(file: File): List<Queued> {
            if (!file.exists()) return emptyList()
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            return root["edits"]?.jsonArray.orEmpty().mapNotNull { e -> runCatching { fromJson(e.jsonObject) }.getOrNull() }
        }

        private fun toJson(q: Queued): JsonObject = buildJsonObject {
            put("id", q.id)
            put("type", q.edit.type.api)
            put("osm", q.edit.id)
            put("set", tags(q.edit.set))
            put("seen", tags(q.edit.seen))
            put("label", q.label)
            put("lat", q.lat)
            put("lon", q.lon)
            put("at", q.at.toString())
            put("state", q.state.name)
            q.changeset?.let { put("changeset", it) }
            q.sentAt?.let { put("sentAt", it.toString()) }
            q.reason?.let { put("reason", it) }
            q.trouble?.let { put("trouble", it.name) }
        }

        private fun tags(m: Map<String, String?>): JsonObject =
            JsonObject(m.mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v) })

        private fun fromJson(o: JsonObject): Queued {
            val type = OsmType.entries.first { it.api == o.text("type") }
            val edit = TagEdit(type, o.long("osm")!!, o.tagMap("set"), o.tagMap("seen"))
            return Queued(
                id = o.long("id")!!,
                edit = edit,
                label = o.text("label").orEmpty(),
                lat = o.double("lat")!!,
                lon = o.double("lon")!!,
                at = Instant.parse(o.text("at")),
                state = State.valueOf(o.text("state")!!),
                changeset = o.long("changeset"),
                sentAt = o.text("sentAt")?.let(Instant::parse),
                reason = o.text("reason"),
                trouble = o.text("trouble")?.let(Trouble::valueOf),
            )
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

        private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

        private fun JsonObject.tagMap(key: String): Map<String, String?> =
            (this[key] as? JsonObject).orEmpty().mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.contentOrNull }
    }
}

/**
 * What the user is told about an edit: drawn as mapped (OpenStreetMap took it); waiting, and why (signed out, offline,
 * OpenStreetMap out of reach or busy, or on its way); or not sent, and why (a conflict with others' changes, a refusal).
 */
enum class EditStatus {
    MAPPED, SIGNED_OUT, OFFLINE, UNREACHABLE, BUSY, SENDING, CONFLICT, REFUSED;

    /** Whether the edit still waits to be sent. */
    val waiting: Boolean get() = this == SIGNED_OUT || this == OFFLINE || this == UNREACHABLE || this == BUSY || this == SENDING

    companion object {
        /** The status of [q] while the user is [signedIn] and the phone [online]. */
        fun of(q: EditQueue.Queued, signedIn: Boolean, online: Boolean): EditStatus = when (q.state) {
            EditQueue.State.SENT -> MAPPED
            EditQueue.State.CONFLICT -> CONFLICT
            EditQueue.State.REFUSED -> REFUSED
            EditQueue.State.PENDING -> when {
                !signedIn -> SIGNED_OUT
                !online -> OFFLINE
                q.trouble == EditQueue.Trouble.BUSY -> BUSY
                q.trouble == EditQueue.Trouble.UNREACHABLE -> UNREACHABLE
                else -> SENDING
            }
        }
    }
}
