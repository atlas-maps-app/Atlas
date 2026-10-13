package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The module's proof on real data: every place around the Westerkerk (data/ams/raw.json) through presets, symbols,
 * categories, facts and brands. Prints the report the brief asks for and holds the coverage to a floor.
 */
class RealDataReportTest {
    private val now = LocalDateTime.of(2026, 10, 13, 15, 0)
    private val ctx = OpeningHoursContext(publicHolidays = PublicHolidays.forCountry("NL"))

    @Test
    fun westerkerkPlacesGetPresetsSymbolsCategoriesAndFacts() {
        val pois = Fixtures.pois("ams")
        var preset = 0
        var icon = 0
        var category = 0
        var facts = 0
        var unknownAsked = 0
        val unmatched = HashMap<String, Int>()
        val generic = HashMap<String, Int>()
        val markerOnly = HashMap<String, Int>()
        val t0 = System.nanoTime()
        for (e in pois) {
            val tags = e.place.tags
            val p = Presets.match(tags, e.geometry)
            if (p != null && p.id !in setOf("point", "area", "line", "vertex", "address")) preset++ else unmatched.merge(mainTag(tags), 1, Int::plus)
            // a bare key preset ("shop", "amenity", "craft") knows the kind of place but not the value
            if (p != null && '/' !in p.id) generic.merge(mainTag(tags), 1, Int::plus)
            val s = Symbols.symbolFor(tags, e.geometry)
            if (s != Symbols.MARKER && Symbols.icon(s) != null) icon++ else markerOnly.merge(mainTag(tags), 1, Int::plus)
            if (Categories.of(tags) != null) category++
            val f = Facts.of(tags, "en", now, ctx, e.geometry)
            if (f.any { it.mapped }) facts++
            unknownAsked += f.count { !it.mapped }
        }
        val ms = (System.nanoTime() - t0) / 1e6
        val n = pois.size
        fun pct(x: Int) = "%.1f %%".format(x * 100.0 / n)
        println("Westerkerk extract: $n places of interest in ${"%.0f".format(ms)} ms")
        println("  preset ${pct(preset)} ($preset), icon ${pct(icon)} ($icon), category ${pct(category)} ($category), facts ${pct(facts)} ($facts), unmapped facts asked $unknownAsked")
        println("  top tags without a preset: " + unmatched.entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key} ${it.value}" })
        println("  matched only a bare key preset: ${generic.values.sum()}: " + generic.entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key} ${it.value}" })
        println("  top tags drawn with the plain marker: " + markerOnly.entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key} ${it.value}" })
        assertTrue("preset coverage ${pct(preset)}", preset >= n * 0.95)
        assertTrue("icon coverage ${pct(icon)}", icon >= n * 0.95)
        assertTrue("category coverage ${pct(category)}", category >= n * 0.95)
    }

    @Test
    fun knownChainsAreRecognisedWithTheirLogos() {
        val pois = Fixtures.pois("ams") + Fixtures.pois("eat")
        val seen = LinkedHashMap<String, Pair<Brand?, Int>>()
        for (e in pois) {
            val tags = e.place.tags
            val label = tags["brand"] ?: continue
            val b = Brands.forTags(tags, "nl")
            val prev = seen[label]
            seen[label] = (prev?.first ?: b) to ((prev?.second ?: 0) + 1)
        }
        val matched = seen.count { it.value.first != null }
        println("Brands in the extracts: ${seen.size} brand names, $matched matched to a brand, ${seen.count { it.value.first?.logo != null }} with a logo")
        for ((name, v) in seen.entries.sortedByDescending { it.value.second }) {
            val b = v.first
            println("  $name ×${v.second}: " + (b?.let { "${it.id} ${it.wikidata ?: ""} logo=${if (it.logo != null) it.logoSlug else "-"} colour=${it.colour?.let { c -> "%06X".format(c and 0xFFFFFF) } ?: "-"}" } ?: "not matched"))
        }
        val chains = mapOf(
            "Albert Heijn" to "Q1653985", "Jumbo" to "Q2262314", "HEMA" to "Q903805", "Starbucks" to "Q37158",
            "McDonald's" to "Q38076", "Etos" to "Q2609459", "Kruidvat" to "Q2226366", "Gall & Gall" to "Q13639185",
        )
        for ((name, qid) in chains) {
            val b = Brands.forTags(mapOf("brand" to name, "brand:wikidata" to qid), "nl")
            assertNotNull(name, b)
            assertEquals(name, qid, b!!.wikidata)
        }
        assertNotNull("Albert Heijn's logo", Brands.forTags(mapOf("brand:wikidata" to "Q1653985"))!!.logo)
        assertNotNull("McDonald's logo", Brands.forTags(mapOf("brand" to "McDonald's", "amenity" to "fast_food"), "nl")!!.logo)
        assertNotNull("Starbucks logo", Brands.forTags(mapOf("brand" to "Starbucks", "amenity" to "cafe"), "nl")!!.logo)
        assertTrue("most brands of the extract match", matched >= seen.size * 0.8)
    }

    private fun mainTag(tags: Map<String, String>): String =
        Fixtures.poiKeys.firstOrNull { it in tags }?.let { "$it=${tags[it]}" } ?: "?"
}
