package app.atlas.providers

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A date as Wikidata states it, to its precision: 9 is a year, 10 a month, 11 a day. */
data class WikidataTime(val year: Int, val month: Int?, val day: Int?, val precision: Int) {
    /** "1631", "1887-05", "1887-05-27": as precise as the source, in OSM's date format. */
    override fun toString(): String = buildString {
        append(if (year < 0) "-" + (-year).toString().padStart(4, '0') else year.toString().padStart(4, '0'))
        if (precision >= 10 && month != null) append('-').append(month.toString().padStart(2, '0'))
        if (precision >= 11 && day != null) append('-').append(day.toString().padStart(2, '0'))
    }

    companion object {
        /** Parses Wikidata's "+1887-05-27T00:00:00Z" with its precision; months and days of 00 mean unknown. */
        fun parse(time: String, precision: Int): WikidataTime? {
            val m = Regex("""^([+-]?)(\d+)-(\d{2})-(\d{2})T""").find(time) ?: return null
            val year = m.groupValues[2].toIntOrNull()?.let { if (m.groupValues[1] == "-") -it else it } ?: return null
            val month = m.groupValues[3].toInt().takeIf { it in 1..12 && precision >= 10 }
            val day = m.groupValues[4].toInt().takeIf { it in 1..31 && precision >= 11 }
            return WikidataTime(year, month, day, precision)
        }
    }
}

/** A link from one item to another (an architect, a namesake, a style), with its label once resolved. */
data class WikidataRef(val id: String, val labels: Map<String, String> = emptyMap(), val descriptions: Map<String, String> = emptyMap()) {
    fun label(langs: List<String>): String? = langs.firstNotNullOfOrNull { labels[it] } ?: labels.values.firstOrNull()
}

/**
 * The facts Atlas shows from a Wikidata item: names and descriptions by language, the logo and small logo (Commons file
 * names), the brand colour, an image, the inception date, and for buildings the architect, namesake and style.
 */
data class WikidataEntity(
    val id: String,
    val labels: Map<String, String>,
    val descriptions: Map<String, String>,
    /** P154, the logo image: a Commons file name such as "Albert Heijn Logo.svg". */
    val logo: String?,
    /** P8972, the small logo or icon: better for map pins when present. */
    val smallLogo: String?,
    /** P465, sRGB colour as "#RRGGBB". */
    val colour: String?,
    /** P18, the main image. */
    val image: String?,
    /** P571, inception. */
    val inception: WikidataTime?,
    /** P84, architects. */
    val architects: List<WikidataRef>,
    /** P138, named after. */
    val namedAfter: List<WikidataRef>,
    /** P149, architectural style. */
    val styles: List<WikidataRef>,
    /** P856, official website. */
    val website: String?,
    /** Wikipedia article titles by site ("enwiki" → "Westerkerk"). */
    val sitelinks: Map<String, String>,
) {
    fun label(langs: List<String>): String? = langs.firstNotNullOfOrNull { labels[it] } ?: labels["en"] ?: labels.values.firstOrNull()
    fun description(langs: List<String>): String? = langs.firstNotNullOfOrNull { descriptions[it] } ?: descriptions["en"]

    /** The logo for a pin: the small one when present, else the main one. */
    val pinLogo: String? get() = smallLogo ?: logo
}

/**
 * Wikidata through its batched `wbgetentities` API (50 items a call): the facts behind brand logos, "named after" and
 * "a building's story". Linked items (architect, namesake, style) are resolved in a second batched call. Data CC0.
 */
class Wikidata(private val http: Http, private val base: String = "https://www.wikidata.org/w/api.php") : Provider {
    override val info = INFO

    override fun state() = ProviderState.READY

    private val cache = Lru<String, WikidataEntity>(512, ttlMs = 7 * 24 * 3600_000L)

    /** One item; null when it does not exist. */
    suspend fun entity(qid: String, langs: List<String> = listOf("en"), resolveLinks: Boolean = true): WikidataEntity? =
        entities(listOf(qid), langs, resolveLinks)[qid.uppercase()]

    /**
     * Items by id, fetched 50 at a time; ids that do not exist are left out. With [resolveLinks], the architects,
     * namesakes and styles come with their labels in [langs].
     */
    suspend fun entities(qids: Collection<String>, langs: List<String> = listOf("en"), resolveLinks: Boolean = true): Map<String, WikidataEntity> {
        val ids = qids.map { it.trim().uppercase() }.filter { QID.matches(it) }.distinct()
        val key = { id: String -> "$id|${langs.joinToString(",")}|$resolveLinks" }
        val out = LinkedHashMap<String, WikidataEntity>()
        val missing = ids.filter { id -> cache.get(key(id))?.also { out[id] = it } == null }
        if (missing.isEmpty()) return out
        val raw = fetch(missing, langs, "labels|descriptions|claims|sitelinks")
        var parsed = raw.mapValues { (_, o) -> parseEntity(o) }
        if (resolveLinks) {
            val links = parsed.values.flatMap { e -> (e.architects + e.namedAfter + e.styles).map { it.id } }.distinct()
            if (links.isNotEmpty()) {
                val named = fetch(links, langs, "labels|descriptions").mapValues { (id, o) -> refOf(id, o) }
                fun fill(list: List<WikidataRef>) = list.map { named[it.id] ?: it }
                parsed = parsed.mapValues { (_, e) -> e.copy(architects = fill(e.architects), namedAfter = fill(e.namedAfter), styles = fill(e.styles)) }
            }
        }
        for ((id, e) in parsed) {
            cache.put(key(id), e)
            out[id] = e
        }
        return out
    }

    /** The raw entity objects for [ids]; an id Wikidata does not know is dropped and the rest asked again. */
    private suspend fun fetch(ids: List<String>, langs: List<String>, props: String): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        for (chunk in ids.chunked(50)) {
            var batch = chunk
            while (batch.isNotEmpty()) {
                val url = base.toHttpUrl().newBuilder()
                    .addQueryParameter("action", "wbgetentities")
                    .addQueryParameter("ids", batch.joinToString("|"))
                    .addQueryParameter("props", props)
                    .addQueryParameter("languages", (langs + "en").distinct().joinToString("|"))
                    .addQueryParameter("languagefallback", "1")
                    .addQueryParameter("format", "json")
                    .addQueryParameter("formatversion", "2")
                    .apply { if ("sitelinks" in props) addQueryParameter("sitefilter", (langs + "en").distinct().joinToString("|") { "${it}wiki" }) }
                    .build()
                val json = http.execute(http.request(url).build()).requireOk().json().asObject() ?: break
                val error = json.obj("error")
                if (error != null) {
                    val bad = error.str("id")?.uppercase()
                    if (error.str("code") == "no-such-entity" && bad != null && bad in batch) {
                        batch = batch - bad
                        continue
                    }
                    throw ProviderException(ID, "Wikidata: ${error.str("info") ?: error.str("code")}")
                }
                json.obj("entities")?.forEach { (id, v) -> v.asObject()?.takeIf { it["missing"] == null }?.let { out[id] = it } }
                break
            }
        }
        return out
    }

    companion object {
        const val ID = "wikidata"
        private val QID = Regex("""Q[1-9]\d*""")

        val INFO = ProviderInfo(
            id = ID, name = "Wikidata", licence = "CC0", attribution = "Facts from Wikidata", url = "https://www.wikidata.org",
            note = "Logos, colours, images, dates and architects; images and logos carry their own Commons licences.",
        )

        private fun texts(o: JsonObject?): Map<String, String> =
            o?.mapNotNull { (lang, v) -> v.asObject()?.str("value")?.let { lang to it } }?.toMap() ?: emptyMap()

        private fun refOf(id: String, o: JsonObject) = WikidataRef(id, texts(o.obj("labels")), texts(o.obj("descriptions")))

        /**
         * The statements of [property] worth using: preferred ones if any, else the normal ones; never deprecated;
         * those with an end time (P582) last, as they are history (an old logo).
         */
        private fun statements(claims: JsonObject?, property: String): List<JsonObject> {
            val all = claims?.arr(property)?.mapNotNull { it.asObject() }?.filter { it.str("rank") != "deprecated" } ?: return emptyList()
            val preferred = all.filter { it.str("rank") == "preferred" }
            val chosen = preferred.ifEmpty { all }
            return chosen.sortedBy { if (it.obj("qualifiers")?.get("P582") != null) 1 else 0 }
        }

        private fun value(s: JsonObject) = s.obj("mainsnak")?.takeIf { it.str("snaktype") == "value" }?.obj("datavalue")?.get("value")

        private fun stringValue(claims: JsonObject?, property: String): String? = statements(claims, property).firstNotNullOfOrNull { value(it).asString() }

        private fun refs(claims: JsonObject?, property: String): List<WikidataRef> =
            statements(claims, property).mapNotNull { s -> value(s).asObject()?.str("id")?.let { WikidataRef(it) } }.distinctBy { it.id }

        /** One entity object of a `wbgetentities` answer. */
        fun parseEntity(o: JsonObject): WikidataEntity {
            val claims = o.obj("claims")
            val inception = statements(claims, "P571").firstNotNullOfOrNull { s ->
                value(s).asObject()?.let { v -> v.str("time")?.let { t -> WikidataTime.parse(t, v.int("precision") ?: 11) } }
            }
            val colour = stringValue(claims, "P465")?.trim()?.removePrefix("#")?.takeIf { Regex("[0-9A-Fa-f]{6}").matches(it) }?.let { "#" + it.uppercase() }
            return WikidataEntity(
                id = o.str("id") ?: "",
                labels = texts(o.obj("labels")),
                descriptions = texts(o.obj("descriptions")),
                logo = stringValue(claims, "P154"),
                smallLogo = stringValue(claims, "P8972"),
                colour = colour,
                image = stringValue(claims, "P18"),
                inception = inception,
                architects = refs(claims, "P84"),
                namedAfter = refs(claims, "P138"),
                styles = refs(claims, "P149"),
                website = stringValue(claims, "P856"),
                sitelinks = o.obj("sitelinks")?.mapNotNull { (site, v) -> v.asObject()?.str("title")?.let { site to it } }?.toMap() ?: emptyMap(),
            )
        }
    }
}

/** What Wikimedia Commons says about a file: where it is, how big, and under what licence. */
data class CommonsFile(
    val title: String,
    val url: String?,
    val thumbUrl: String?,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    /** "Public domain", "CC BY-SA 4.0" … */
    val licence: String?,
    /** Commons' machine licence code: "pd", "cc0", "cc-by-sa-4.0" … */
    val licenceCode: String?,
    val attributionRequired: Boolean,
    /** The author as plain text (Commons gives HTML). */
    val artist: String?,
) {
    /** True for licences Atlas can show without asking anyone: public domain, CC0, CC BY and CC BY-SA (with credit). */
    val free: Boolean get() = licenceCode?.let { c -> c == "pd" || c.startsWith("cc0") || c.startsWith("cc-by") || c.startsWith("cc-zero") } == true

    /** The credit line to show next to the image, or null when none is needed. */
    val credit: String? get() = if (!attributionRequired) null else listOfNotNull(artist, licence).joinToString(", ").ifEmpty { null }
}

/** Wikimedia Commons: file URLs at a width, and each file's licence, so logos and photos are shown lawfully. */
class Commons(private val http: Http, private val base: String = "https://commons.wikimedia.org") {
    /** Licence and size for up to 50 files a call, by file name ("Albert Heijn Logo.svg"); unknown files are left out. */
    suspend fun files(names: Collection<String>, thumbWidth: Int = 256): Map<String, CommonsFile> {
        val out = LinkedHashMap<String, CommonsFile>()
        for (chunk in names.map(::normaliseName).distinct().chunked(50)) {
            val url = "$base/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("titles", chunk.joinToString("|") { "File:$it" })
                .addQueryParameter("prop", "imageinfo")
                .addQueryParameter("iiprop", "url|size|mime|extmetadata")
                .addQueryParameter("iiextmetadatafilter", "LicenseShortName|License|UsageTerms|AttributionRequired|Copyrighted|Artist|LicenseUrl")
                .addQueryParameter("iiurlwidth", thumbWidth.toString())
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .build()
            val pages = http.execute(http.request(url).build()).requireOk().json().asObject()?.obj("query")?.arr("pages") ?: continue
            for (p in pages.mapNotNull { it.asObject() }) {
                if (p["missing"] != null) continue
                val title = p.str("title")?.removePrefix("File:") ?: continue
                val ii = p.arr("imageinfo").firstOrNull().asObject() ?: continue
                val meta = ii.obj("extmetadata")
                fun m(k: String) = meta?.obj(k)?.str("value")
                out[title] = CommonsFile(
                    title = title, url = ii.str("url"), thumbUrl = ii.str("thumburl")?.substringBefore("?utm_"), mime = ii.str("mime"),
                    width = ii.int("width"), height = ii.int("height"),
                    licence = m("LicenseShortName"), licenceCode = m("License")?.lowercase(),
                    attributionRequired = m("AttributionRequired") == "true",
                    artist = m("Artist")?.replace(Regex("<[^>]+>"), "")?.trim()?.ifEmpty { null },
                )
            }
        }
        return out
    }

    companion object {
        /** "Albert_Heijn Logo.svg" and "File:Albert Heijn Logo.svg" are the same file. */
        fun normaliseName(name: String): String = name.trim().removePrefix("File:").replace('_', ' ')

        /**
         * The URL that serves [fileName] (a Wikidata P154/P18 value), as a PNG thumbnail [width] pixels wide when given:
         * `Special:FilePath`, which redirects to the file on upload.wikimedia.org.
         */
        fun fileUrl(fileName: String, width: Int? = null): String {
            val b = "https://commons.wikimedia.org/wiki/".toHttpUrl().newBuilder()
                .addPathSegment("Special:FilePath")
                .addPathSegment(normaliseName(fileName).replace(' ', '_'))
            width?.let { b.addQueryParameter("width", it.toString()) }
            return b.build().toString()
        }
    }
}
