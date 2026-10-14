package app.atlas.ui.search

import app.atlas.providers.WikidataEntity
import app.atlas.providers.WikidataRef
import app.atlas.providers.WikidataTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * Wikidata facts kept on the phone once fetched (the data is CC0), one small JSON file per item, so a place shows its
 * architect, year and namesake offline too. A file that cannot be read is treated as missing.
 */
class KeptFacts(private val dir: File) {
    /** The kept item [qid], or null when it was never kept. */
    fun read(qid: String): WikidataEntity? {
        val f = fileOf(qid) ?: return null
        if (!f.isFile) return null
        return try {
            decode(Json.parseToJsonElement(f.readText()).jsonObject)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Keeps [entity], replacing what was kept for it. */
    fun keep(entity: WikidataEntity) {
        val f = fileOf(entity.id) ?: return
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(encode(entity).toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun fileOf(qid: String): File? = qid.trim().uppercase().takeIf { QID.matches(it) }?.let { File(dir, "$it.json") }

    companion object {
        private val QID = Regex("^Q\\d+$")

        fun encode(e: WikidataEntity): JsonObject = buildJsonObject {
            put("id", e.id)
            put("labels", strings(e.labels))
            put("descriptions", strings(e.descriptions))
            e.logo?.let { put("logo", it) }
            e.smallLogo?.let { put("smallLogo", it) }
            e.colour?.let { put("colour", it) }
            e.image?.let { put("image", it) }
            e.inception?.let { t ->
                put("inception", buildJsonObject {
                    put("year", t.year)
                    t.month?.let { put("month", it) }
                    t.day?.let { put("day", it) }
                    put("precision", t.precision)
                })
            }
            put("architects", refs(e.architects))
            put("namedAfter", refs(e.namedAfter))
            put("styles", refs(e.styles))
            e.website?.let { put("website", it) }
            put("sitelinks", strings(e.sitelinks))
        }

        fun decode(o: JsonObject): WikidataEntity = WikidataEntity(
            id = o.str("id") ?: throw IllegalArgumentException("no id"),
            labels = o.map("labels"),
            descriptions = o.map("descriptions"),
            logo = o.str("logo"),
            smallLogo = o.str("smallLogo"),
            colour = o.str("colour"),
            image = o.str("image"),
            inception = (o["inception"] as? JsonObject)?.let { t ->
                val year = t["year"]?.jsonPrimitive?.intOrNull ?: return@let null
                WikidataTime(year, t["month"]?.jsonPrimitive?.intOrNull, t["day"]?.jsonPrimitive?.intOrNull, t["precision"]?.jsonPrimitive?.intOrNull ?: 9)
            },
            architects = o.refs("architects"),
            namedAfter = o.refs("namedAfter"),
            styles = o.refs("styles"),
            website = o.str("website"),
            sitelinks = o.map("sitelinks"),
        )

        private fun strings(m: Map<String, String>) = JsonObject(m.mapValues { JsonPrimitive(it.value) })

        private fun refs(list: List<WikidataRef>) = JsonArray(list.map { r ->
            buildJsonObject {
                put("id", r.id)
                put("labels", strings(r.labels))
                put("descriptions", strings(r.descriptions))
            }
        })

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.map(k: String): Map<String, String> =
            (this[k] as? JsonObject)?.entries?.mapNotNull { (key, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { key to it } }?.toMap() ?: emptyMap()

        private fun JsonObject.refs(k: String): List<WikidataRef> = (this[k] as? JsonArray)?.mapNotNull { e: JsonElement ->
            val r = e as? JsonObject ?: return@mapNotNull null
            WikidataRef(r.str("id") ?: return@mapNotNull null, r.map("labels"), r.map("descriptions"))
        } ?: emptyList()
    }
}
