package app.atlas.ui.local

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder

/** The gender Wikidata states for a person (P21). */
enum class Gender { MALE, FEMALE, OTHER }

/** What a name was given for. */
enum class NamesakeKind { PERSON, PLACE, OTHER }

/** Who or what a street is named after, from Wikidata (P138) with what the item says about it. */
class Namesake(
    val qid: String,
    val label: String,
    val description: String?,
    val kind: NamesakeKind,
    val born: Int?,
    val died: Int?,
    val gender: Gender?,
    /** A Commons file name (P18), or null. */
    val image: String?,
) {
    /** The century of birth (1606 is the 17th), or null when no birth year is known. */
    val century: Int? get() = born?.let { if (it > 0) (it - 1) / 100 + 1 else null }
}

/** The street asked about: its name and where it is mapped, and what was found about its name. */
class NamedAfter(val streetName: String?, val street: Place?, val namesakes: List<Namesake>, val note: String?, val state: State) {
    enum class State { FOUND, NOT_RECORDED, NO_STREET, OFFLINE, ERROR }
}

/** A Wikidata item reduced to the claims this package reads. */
internal class RawItem(val id: String, val labels: Map<String, String>, val descriptions: Map<String, String>, val claims: JsonObject) {
    fun refs(property: String): List<String> = statements(property).mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }

    fun statements(property: String): List<JsonElement> = ((claims[property] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { s ->
        (((s as? JsonObject)?.get("mainsnak") as? JsonObject)?.get("datavalue") as? JsonObject)?.get("value")
    }

    fun time(property: String): Int? = statements(property).firstNotNullOfOrNull { v -> ((v as? JsonObject)?.get("time") as? JsonPrimitive)?.contentOrNull?.let { yearOf(it) } }

    fun location(): LatLon? = statements("P625").firstNotNullOfOrNull { v ->
        val o = v as? JsonObject ?: return@firstNotNullOfOrNull null
        val lat = (o["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@firstNotNullOfOrNull null
        val lon = (o["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@firstNotNullOfOrNull null
        LatLon(lat, lon)
    }

    fun text(property: String): String? = statements(property).firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }

    fun label(langs: List<String>): String? = langs.firstNotNullOfOrNull { labels[it] } ?: labels["en"] ?: labels.values.firstOrNull()
    fun description(langs: List<String>): String? = langs.firstNotNullOfOrNull { descriptions[it] } ?: descriptions["en"]

    private fun yearOf(t: String): Int? = Regex("""^([+-])(\d+)-""").find(t)?.let { m -> m.groupValues[2].toIntOrNull()?.let { if (m.groupValues[1] == "-") -it else it } }
}

/**
 * "Named after": the street's own `name:etymology:wikidata` tag first; else the item its `wikidata` tag points to; else
 * Wikidata's items of that name, the one within a few km of the point that is a street or square. The namesake's era
 * and gender come from its own item. [fetch] reads a URL as text (the app's HTTP client; tests give canned answers).
 */
class Etymology(private val fetch: suspend (String) -> String) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(at: LatLon, streets: List<Place>, nameHint: String?, langs: List<String>, online: Boolean): NamedAfter {
        val street = streets.filter { it.tags["name"] != null }.let { list ->
            list.firstOrNull { nameHint != null && it.tags["name"].equals(nameHint, ignoreCase = true) } ?: list.minByOrNull { Geo.distance(at, it.location) }
        }
        val name = street?.tags?.get("name") ?: nameHint
        if (name == null) return NamedAfter(null, null, emptyList(), null, NamedAfter.State.NO_STREET)
        val tagged = street?.tags?.get("name:etymology:wikidata")?.split(';')?.map { it.trim() }?.filter { it.matches(Regex("Q\\d+")) }.orEmpty()
        val text = street?.tags?.get("name:etymology")
        try {
            var ids = tagged
            if (ids.isEmpty() && online) {
                val own = street?.tags?.get("wikidata")?.takeIf { it.matches(Regex("Q\\d+")) }
                val item = own?.let { items(listOf(it))[it] } ?: search(name, at, langs)
                ids = item?.refs("P138").orEmpty()
            }
            if (ids.isEmpty()) return NamedAfter(name, street, emptyList(), text, if (text != null) NamedAfter.State.FOUND else if (online) NamedAfter.State.NOT_RECORDED else NamedAfter.State.OFFLINE)
            if (!online) return NamedAfter(name, street, emptyList(), text, NamedAfter.State.OFFLINE)
            val found = items(ids)
            val people = ids.mapNotNull { found[it] }.map { namesake(it, langs) }
            return NamedAfter(name, street, people, text, if (people.isEmpty()) NamedAfter.State.NOT_RECORDED else NamedAfter.State.FOUND)
        } catch (e: java.io.IOException) {
            return NamedAfter(name, street, emptyList(), text, NamedAfter.State.OFFLINE)
        } catch (e: kotlinx.serialization.SerializationException) {
            return NamedAfter(name, street, emptyList(), text, NamedAfter.State.ERROR)
        }
    }

    /** What Wikidata adds about a building: its inception year, architect and style by name, and its heritage designation. */
    class BuildingItem(val year: Int?, val architect: String?, val style: String?, val heritage: String?, val image: String?)

    /** The building's Wikidata item [qid] read for its story (P571, P84, P149, P1435), linked items by their labels. */
    suspend fun building(qid: String, langs: List<String>): BuildingItem? {
        val item = items(listOf(qid))[qid] ?: return null
        val linked = items(listOf("P84", "P149", "P1435").flatMap { item.refs(it) })
        fun name(p: String) = item.refs(p).firstNotNullOfOrNull { linked[it]?.label(langs) }
        return BuildingItem(item.time("P571"), name("P84"), name("P149"), name("P1435"), item.text("P18"))
    }

    internal fun namesake(item: RawItem, langs: List<String>): Namesake {
        val types = item.refs("P31")
        val human = "Q5" in types
        val gender = item.refs("P21").firstOrNull()?.let {
            when (it) { "Q6581097", "Q2449503" -> Gender.MALE; "Q6581072", "Q1052281" -> Gender.FEMALE; else -> Gender.OTHER }
        }
        return Namesake(
            item.id, item.label(langs) ?: item.id, item.description(langs),
            when { human -> NamesakeKind.PERSON; types.any { it in PLACES } -> NamesakeKind.PLACE; else -> NamesakeKind.OTHER },
            item.time("P569"), item.time("P570"), gender, item.text("P18"),
        )
    }

    private suspend fun search(name: String, at: LatLon, langs: List<String>): RawItem? {
        val lang = langs.firstOrNull() ?: "en"
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${enc(name)}&language=$lang&type=item&limit=12&format=json&origin=*"
        val hits = (json.parseToJsonElement(fetch(url)) as? JsonObject)?.get("search") as? JsonArray ?: return null
        val ids = hits.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
        if (ids.isEmpty()) return null
        val found = items(ids)
        return ids.mapNotNull { found[it] }
            .filter { item -> item.refs("P31").none { it in DISAMBIGUATION } }
            // Only a street or a square: a hotel or a shop of the same name has a namesake of its own.
            .filter { item -> item.refs("P31").any { it in STREETS } }
            .mapNotNull { item -> item.location()?.let { item to Geo.distance(at, it) } }
            .filter { it.second < 3_000.0 }
            .minByOrNull { it.second }?.first
    }

    internal suspend fun items(ids: List<String>): Map<String, RawItem> {
        val out = LinkedHashMap<String, RawItem>()
        for (chunk in ids.distinct().chunked(40)) {
            val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${chunk.joinToString("%7C")}&props=claims%7Clabels%7Cdescriptions&languages=nl%7Cen%7Cde%7Cfr&format=json&origin=*"
            out += parseItems(fetch(url))
        }
        return out
    }

    internal fun parseItems(text: String): Map<String, RawItem> {
        val entities = (json.parseToJsonElement(text) as? JsonObject)?.get("entities") as? JsonObject ?: return emptyMap()
        return entities.mapNotNull { (id, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            fun texts(key: String) = (o[key] as? JsonObject)?.mapNotNull { (lang, t) -> ((t as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull?.let { lang to it } }?.toMap().orEmpty()
            id to RawItem(id, texts("labels"), texts("descriptions"), (o["claims"] as? JsonObject) ?: JsonObject(emptyMap()))
        }.toMap()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        /** Wikidata classes of places: city, country, settlement, region, island, river, mountain... */
        private val PLACES = setOf("Q515", "Q6256", "Q486972", "Q82794", "Q23442", "Q4022", "Q8502", "Q532", "Q3957", "Q1549591", "Q5119", "Q15284", "Q1530022")
        /** Wikidata classes of a thoroughfare: street, road, square, avenue. */
        private val STREETS = setOf("Q79007", "Q34442", "Q174782", "Q787113")
        private val DISAMBIGUATION = setOf("Q4167410", "Q4167836", "Q13406463")
    }
}
