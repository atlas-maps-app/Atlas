package app.atlas.providers

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/** One section of an article: its heading, how deep it is (1 for "== History =="), and its paragraphs as plain text. */
data class WikipediaSection(val level: Int, val title: String, val paragraphs: List<String>)

/**
 * A Wikipedia article as Atlas reads it, natively and offline once read: the [title], the short [description], the
 * [lead] paragraphs, the [sections] worth reading (references, links and "see also" left out), the picture, when it
 * was last edited, its address, and the same article in other languages ([languages], language to title).
 */
data class WikipediaArticle(
    val lang: String,
    val title: String,
    val description: String?,
    val lead: List<String>,
    val sections: List<WikipediaSection>,
    val thumbnail: String?,
    val url: String,
    /** When it was last edited, as Wikipedia states it ("2026-10-20T22:02:54Z"). */
    val edited: String?,
    val languages: Map<String, String>,
) {
    /** How many words it has: for "a 4-minute read". */
    val words: Int get() = (lead + sections.flatMap { it.paragraphs }).sumOf { p -> p.count { it == ' ' } + 1 }
}

/**
 * Wikipedia through the MediaWiki API (`action=query`, plain text extracts): a place's article (OSM's `wikipedia`
 * tag, or its Wikidata item's sitelink) read in the app, in the reader's language where there is one. Text CC BY-SA.
 */
class Wikipedia(private val http: Http, private val base: (String) -> String = { lang -> "https://$lang.wikipedia.org/w/api.php" }) : Provider {
    override val info = INFO

    override fun state() = ProviderState.READY

    private val cache = Lru<String, WikipediaArticle>(64, ttlMs = 7 * 24 * 3600_000L)

    /** The article [title] of [lang]'s Wikipedia (redirects followed); null when there is none. */
    suspend fun article(lang: String, title: String): WikipediaArticle? {
        val l = lang.lowercase().takeIf { LANG.matches(it) } ?: throw ProviderException(ID, "Wikipedia: no such language $lang")
        val key = "$l:$title"
        cache.get(key)?.let { return it }
        val url = base(l).toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("prop", "extracts|pageimages|info|description|revisions|langlinks")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("exsectionformat", "wiki")
            .addQueryParameter("piprop", "thumbnail")
            .addQueryParameter("pithumbsize", "960")
            .addQueryParameter("inprop", "url")
            .addQueryParameter("rvprop", "timestamp")
            .addQueryParameter("lllimit", "500")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("titles", title)
            .build()
        val json = http.execute(http.request(url).build()).requireOk().json().asObject() ?: return null
        json.obj("error")?.let { throw ProviderException(ID, "Wikipedia: ${it.str("info") ?: it.str("code")}") }
        val page = json.obj("query")?.arr("pages")?.firstOrNull()?.asObject() ?: return null
        return parse(l, page)?.also { cache.put(key, it) }
    }

    /** The article [title] of [lang]'s Wikipedia as read before (kept a week), without asking: for when the phone is offline. */
    fun cached(lang: String, title: String): WikipediaArticle? = cache.get("${lang.lowercase()}:$title")

    /**
     * The article of a place with [tags] in the first of [langs] it has one in: its `wikipedia:xx` tags, else its
     * `wikipedia` tag ("nl:Westerkerk (Amsterdam)") in that language or, through the article's language links, in
     * the reader's.
     */
    suspend fun of(tags: Map<String, String>, langs: List<String>): WikipediaArticle? {
        for (l in langs) tags["wikipedia:$l"]?.let { t -> article(l, t)?.let { return it } }
        val (lang, title) = ref(tags["wikipedia"] ?: return null) ?: return null
        val found = article(lang, title) ?: return null
        val better = langs.takeWhile { it != lang }.firstNotNullOfOrNull { l -> found.languages[l]?.let { l to it } }
        return better?.let { (l, t) -> article(l, t) } ?: found
    }

    companion object {
        const val ID = "wikipedia"

        val INFO = ProviderInfo(
            id = ID, name = "Wikipedia", licence = "CC BY-SA 4.0", attribution = "Text from Wikipedia, CC BY-SA", url = "https://www.wikipedia.org",
            note = "The articles of places, read in the app; each names its source and can be opened on Wikipedia.",
            hosts = listOf("*.wikipedia.org"),
        )

        private val LANG = Regex("^[a-z]{2,3}(-[a-z]+)?$")

        /** A `wikipedia` tag's language and title ("nl:Westerkerk (Amsterdam)"); null when it says neither. */
        fun ref(value: String): Pair<String, String>? {
            val i = value.indexOf(':').takeIf { it in 2..12 } ?: return null
            val lang = value.substring(0, i).trim().lowercase()
            val title = value.substring(i + 1).trim().replace('_', ' ')
            return if (LANG.matches(lang) && title.isNotEmpty()) lang to title else null
        }

        /** Sections a reader of a place does not want: notes, references, links, lists of sources, in four languages. */
        private val SKIP = setOf(
            "references", "notes", "external links", "see also", "further reading", "bibliography", "sources", "literature", "citations", "footnotes", "gallery",
            "referenties", "noten", "bronnen", "externe links", "externe link", "zie ook", "literatuur", "bibliografie", "voetnoten", "bronvermelding", "galerij",
            "einzelnachweise", "weblinks", "literatur", "siehe auch", "anmerkungen", "quellen", "belege", "galerie",
            "notes et references", "references", "liens externes", "lien externe", "voir aussi", "bibliographie", "articles connexes", "sources", "galerie",
        )

        private val HEADING = Regex("""^(=+)\s*(.+?)\s*=+$""")

        internal fun parse(lang: String, page: JsonObject): WikipediaArticle? {
            if (page.bool("missing") == true || page.bool("invalid") == true) return null
            val title = page.str("title") ?: return null
            val extract = page.str("extract") ?: ""
            val lead = ArrayList<String>()
            val sections = ArrayList<WikipediaSection>()
            var heading: Pair<Int, String>? = null
            var paragraphs = ArrayList<String>()
            fun close() {
                val h = heading
                if (h == null) lead += paragraphs else if (paragraphs.isNotEmpty() && fold(h.second) !in SKIP) sections += WikipediaSection(h.first, h.second, paragraphs)
                paragraphs = ArrayList()
            }
            // a section left out takes its subsections with it
            var skipping = Int.MAX_VALUE
            for (raw in extract.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val m = HEADING.matchEntire(line)
                if (m != null) {
                    close()
                    val level = m.groupValues[1].length - 1
                    val name = m.groupValues[2]
                    if (level <= skipping) skipping = if (fold(name) in SKIP) level else Int.MAX_VALUE
                    heading = level to name
                    continue
                }
                if (heading?.first?.let { it >= skipping } == true) continue
                paragraphs += line
            }
            close()
            val links = page.arr("langlinks").mapNotNull { it.asObject() }.mapNotNull { o -> o.str("lang")?.let { l -> (o.str("title") ?: o.str("*"))?.let { l to it } } }.toMap()
            return WikipediaArticle(
                lang = lang,
                title = title,
                description = page.str("description"),
                lead = lead,
                sections = sections,
                thumbnail = page.obj("thumbnail")?.str("source"),
                url = page.str("fullurl") ?: "https://$lang.wikipedia.org/wiki/${title.replace(' ', '_')}",
                edited = page.arr("revisions").firstOrNull()?.asObject()?.str("timestamp"),
                languages = links,
            )
        }

        private fun fold(s: String): String =
            java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()
    }
}
