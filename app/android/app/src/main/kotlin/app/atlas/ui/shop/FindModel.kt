package app.atlas.ui.shop

import app.atlas.places.Presets
import app.atlas.places.TagFilter
import app.atlas.places.Text
import app.atlas.search.GoodsCatalogue
import java.time.LocalDateTime

/** One drawer of the catalogue: a kind of place that may sell what was asked for, and how likely it has it (1..5). */
internal class FindSeller(
    val id: String,
    /** "HARDWARE": the tab. */
    val label: String,
    val filter: TagFilter,
    val likelihood: Int,
    val symbol: String,
    val colour: Long,
    /** The tags as the card types them: `shop=hardware · doityourself`. */
    val tagLine: String,
)

/** What "where can I buy X?" came to: the thing, the kinds of place likeliest first, and where the answer came from. */
internal class FindAnswer(val thing: String, val sellers: List<FindSeller>, val source: Source) {
    enum class Source { LIST, GOODS, PRESET }
}

/**
 * "Where can I buy X?": the curated grocery table first (about 300 items with likelihoods), then `:search`'s goods
 * catalogue (picture hooks, batteries, a spare key), then the preset terms of `:places` ("bakery", "pharmacy": the kind
 * itself, surely). Each answer is the kinds of place likeliest first, as the drawers of the card catalogue.
 */
internal object FindModel {
    private val OPEN_NOW = Regex("(?i)[,;]?\\s*\\b(open now|nu open|geoffnet|jetzt offen|ouvert maintenant)\\b")
    private val SPLIT = Regex("(?i)\\s*(?:,|;|&|\\+|\\band\\b|\\ben\\b|\\bund\\b|\\bet\\b)\\s*")

    /** The things in [raw] ("oat milk and saffron, open now") and whether it asked for what is open now. */
    fun parts(raw: String): Pair<List<String>, Boolean> {
        val open = OPEN_NOW.containsMatchIn(raw)
        val rest = OPEN_NOW.replace(raw, " ")
        return rest.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() } to open
    }

    fun resolve(thing: String, lang: String): FindAnswer? {
        val t = thing.trim()
        if (t.isEmpty()) return null
        ShopItems.classify(t)?.let { item ->
            return FindAnswer(t, item.sellers.map { s -> fromKind(s.kind, s.likelihood, lang) }, FindAnswer.Source.LIST)
        }
        val folded = Text.fold(t)
        val goods = GoodsCatalogue.all.firstOrNull { g -> g.phrases.values.any { ps -> folded in ps } }
            ?: GoodsCatalogue.all.firstOrNull { g -> g.phrases.values.any { ps -> ps.any { p -> p.length >= 4 && (" $folded ").contains(" $p ") } } }
        if (goods != null) {
            return FindAnswer(t, goods.sellers.map { s ->
                val kind = ShopKind.entries.firstOrNull { k -> k.tagLine.removePrefix("shop=").substringBefore(" ") in s.filter.toText() && k != ShopKind.VARIETY }
                FindSeller(
                    s.filter.toText(), s.name(lang).uppercase(), s.filter, s.likelihood, s.symbol,
                    kind?.colour ?: colourOf(s.name("en")), typed(s.filter),
                )
            }, FindAnswer.Source.GOODS)
        }
        val hit = Presets.search(t, lang, limit = 1).firstOrNull()?.preset ?: return null
        val tag = hit.tags
        if (tag.isEmpty()) return null
        val filter = TagFilter.parse(tag.entries.joinToString(" and ") { (k, v) -> if (v == "*") k else "$k=$v" })
        return FindAnswer(
            t,
            listOf(FindSeller(hit.id, hit.name(lang).uppercase(), filter, 5, Presets.iconOf(hit) ?: "place.shop", colourOf(hit.id), filter.toText().replace(" and ", " · "))),
            FindAnswer.Source.PRESET,
        )
    }

    private fun fromKind(kind: ShopKind, likelihood: Int, lang: String): FindSeller {
        val key = kind.tagLine.substringBefore(" ").substringBefore("=")
        val value = kind.tagLine.substringAfter("=").substringBefore(" ").substringBefore("·").trim()
        val label = Presets.byId("$key/$value")?.name(lang) ?: value.replace('_', ' ')
        return FindSeller(kind.name.lowercase(), label.uppercase(), kind.filter, likelihood, kind.glyph, kind.colour, kind.tagLine)
    }

    /** `shop=hardware · doityourself` for the tag rules the catalogue writes as `Equals` and `Matches`. */
    private fun typed(f: TagFilter): String {
        val text = f.toText()
        val m = Regex("^(\\w+)~\\^\\(([^)]*)\\)\\$$").find(text)
        if (m != null) return m.groupValues[1] + "=" + m.groupValues[2].replace("|", " · ")
        return text
    }

    private val PALETTE = longArrayOf(0xFFC98A2B, 0xFF1F5F99, 0xFF7B4DFF, 0xFFC8322A, 0xFF1E9E5A, 0xFF12A9A6, 0xFFD2457F, 0xFF8C6A3E, 0xFF464C5A)

    private fun colourOf(seed: String): Long = PALETTE[Math.floorMod(seed.hashCode(), PALETTE.size)]

    /** The shops of [seller] among [places], nearest first, those open now before those that are not. */
    fun shops(seller: FindSeller, places: List<ShopPlace>, area: ShopArea, at: LocalDateTime, openNow: Boolean): List<ShopPlace> {
        val mine = places.filter { seller.filter.matches(it.tags) }
        val kept = if (openNow) mine.filter { it.openness(at, area.hours) != Open.CLOSED } else mine
        return kept.sortedWith(compareBy<ShopPlace> { if (it.openness(at, area.hours) == Open.CLOSED) 1 else 0 }.thenBy { it.distanceM })
    }

    /** What people try first, as the empty catalogue suggests: one item per drawer of the goods catalogue. */
    val examples = listOf("batteries", "picture hooks", "stamps", "passport photo", "painkillers", "bike light")
}
