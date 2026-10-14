package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.providers.OverpassArea
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Random mixes of the words people type (kinds, connectors, times, distances, tags, typos, punctuation) must never
 * break the parser, the evaluator, the Overpass compiler or the search box. Seeded, so a failure repeats.
 */
class FuzzTest {
    private val box = BBox(4.87, 52.37, 4.89, 52.38)

    private val words = listOf(
        "café", "restaurant", "pizza", "near", "with", "wifi", "outlet", "then", ",", "and", "or", "not", "open", "now", "tonight",
        "late", "sunday", "tomorrow", "at", "22:00", "200", "m", "km", "5", "min", "walk", "by", "bike", "dock", "park", "in", "de", "Jordaan",
        "without", "terrace", "quiet", "cheap", "nearest", "best", "3", "bakeries", "bij", "met", "terras", "nu", "mit", "nahe", "près", "d", "une",
        "amenity=cafe", "shop=bakery", "within", "of", "around", "me", "from", "nearby", "&", "+", ";", "albert", "heijn", "picture", "hooks",
        "batteries", "zwqx", "type:node", "id:n123", "limit:3", "limit:-2", "sort:distance", "newer:2026-01-01", "\"", "(", ")", "~", "=",
        "toilets", "wheelchair=yes", "step-free", "N47", "n26973327", "24h", "24/7", "westerkrk", "263", "52.3745,", "4.8838",
        "cuisine~^(pizza|pasta)$", "\"socket:type2_combo:output\">=150kW", "!near", "global", "-", "é", "ß", "☕",
    )

    private fun text(rnd: Random, max: Int) = (0 until 1 + rnd.nextInt(max)).joinToString(if (rnd.nextInt(4) == 0) "" else " ") { words[rnd.nextInt(words.size)] }

    @Test
    fun parserEvaluatorAndCompilerNeverThrow() {
        val rnd = Random(42)
        val evaluator = OfflineEvaluator(Fixtures.store)
        val ctx = Fixtures.context()
        val failures = ArrayList<String>()
        repeat(400) {
            val t = text(rnd, 8)
            try {
                val p = QueryParser.parse(t, Lexicon.LANGS[rnd.nextInt(4)], Fixtures.saturday)
                for (s in p.steps) {
                    evaluator.run(s, ctx)
                    OverpassCompiler.compile(s, OverpassArea.Box(box), Fixtures.westerkerk)
                }
                for (c in p.chips) if (c.start >= 0) require(c.start < c.end && c.end <= t.length) { "chip $c outside '$t'" }
            } catch (e: Throwable) {
                failures += "'$t' -> $e"
            }
        }
        assertEquals(emptyList<String>(), failures.take(10))
    }

    @Test
    fun theSearchBoxNeverThrows() {
        val rnd = Random(7)
        val engine = SearchEngine(Fixtures.store)
        val failures = ArrayList<String>()
        repeat(100) {
            val t = text(rnd, 6)
            val ctx = SearchContext(
                location = if (rnd.nextBoolean()) Fixtures.westerkerk else null, viewport = if (rnd.nextBoolean()) box else null,
                lang = Lexicon.LANGS[rnd.nextInt(4)], now = Fixtures.saturday,
            )
            try {
                runBlocking { engine.search(t, ctx) }
            } catch (e: Throwable) {
                failures += "'$t' -> $e"
            }
        }
        assertEquals(emptyList<String>(), failures.take(10))
    }
}
