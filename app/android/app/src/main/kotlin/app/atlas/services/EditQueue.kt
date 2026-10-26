package app.atlas.services

import app.atlas.providers.EditsSent
import app.atlas.providers.OsmType
import app.atlas.providers.TagEdit
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * The user's edits to OpenStreetMap on this phone (FillIn, and the painter after it). Each waits here until it is sent with
 * the user's own account, after their tap on the review, in one changeset with the others pending; the living map draws a
 * pending edit as the user's own until the next map update carries it. One JSON file, written whole through a temporary
 * file renamed over it, so an edit survives a restart. Writes are quick and blocking: call them off the main thread.
 */
class EditQueue private constructor(private val file: File, private val clock: () -> Instant) {
    enum class State { PENDING, SENT, CONFLICT }

    /**
     * One edit: its local [id], the [edit], what it reads as ([label], "Leliegracht 49 · colour of the front"), when it was
     * made, where it stands, and the changeset that carried it.
     */
    data class Queued(val id: Long, val edit: TagEdit, val label: String, val at: Instant, val state: State, val changeset: Long? = null)

    private val lock = Any()
    private val items = MutableStateFlow<List<Queued>>(emptyList())

    /** Every edit, oldest first; it changes after each write. */
    val all: StateFlow<List<Queued>> = items.asStateFlow()

    /** The edits still to send, oldest first. */
    val pending: List<Queued> get() = items.value.filter { it.state == State.PENDING }

    /** Queues [edit], described as [label]. */
    fun add(edit: TagEdit, label: String): Queued = synchronized(lock) {
        val q = Queued((items.value.maxOfOrNull { it.id } ?: 0) + 1, edit, label, clock(), State.PENDING)
        write(items.value + q)
        q
    }

    /** Takes back pending edit [id]; false where it is gone or already sent. */
    fun undo(id: Long): Boolean = synchronized(lock) {
        val list = items.value
        if (list.none { it.id == id && it.state == State.PENDING }) return false
        write(list.filterNot { it.id == id })
        true
    }

    /** Forgets the edits that were sent or ran into others' changes. */
    fun clearDone() = synchronized(lock) { write(items.value.filter { it.state == State.PENDING }) }

    /**
     * Sends every pending edit in one go through [send] (OsmEdits.send with the user's token and comment), then keeps
     * what came of each: sent with its changeset, or a conflict where its element changed what the user saw. Null when
     * nothing was pending. A failure of [send] leaves every edit pending.
     */
    suspend fun sendPending(send: suspend (List<TagEdit>) -> EditsSent): EditsSent? {
        val batch = pending
        if (batch.isEmpty()) return null
        val result = send(batch.map { it.edit })
        synchronized(lock) {
            val applied = result.applied.toMutableList()
            val conflicts = result.conflicts.toMutableList()
            val outcome = HashMap<Long, Queued>()
            for (q in batch) {
                outcome[q.id] = when {
                    applied.remove(q.edit) -> q.copy(state = State.SENT, changeset = result.changeset)
                    conflicts.remove(q.edit) -> q.copy(state = State.CONFLICT)
                    else -> q
                }
            }
            write(items.value.map { outcome[it.id] ?: it })
        }
        return result
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
        /** The queue kept in [file] (made on the first edit), read now. A file that cannot be read starts it empty. */
        fun open(file: File, clock: () -> Instant = Instant::now): EditQueue {
            val q = EditQueue(file, clock)
            q.items.value = runCatching { read(file) }.getOrDefault(emptyList())
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
            put("at", q.at.toString())
            put("state", q.state.name)
            q.changeset?.let { put("changeset", it) }
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
                at = Instant.parse(o.text("at")),
                state = State.valueOf(o.text("state")!!),
                changeset = o.long("changeset"),
            )
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

        private fun JsonObject.tagMap(key: String): Map<String, String?> =
            (this[key] as? JsonObject).orEmpty().mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.contentOrNull }
    }
}
