package app.atlas.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Timings on the real Amsterdam extracts: the import, typical queries through the evaluator, and the search box.
 * The budget is 50 ms a query (the median of repeated runs, warmed up); a CI runner gets three times as long.
 */
class BenchmarkTest {
    private val budgetMs = if (System.getProperty("atlas.ci") != null) 150.0 else 50.0

    private fun timeMs(runs: Int = 15, block: () -> Unit): Pair<Double, Double> {
        repeat(3) { block() }
        val t = DoubleArray(runs) {
            val s = System.nanoTime()
            block()
            (System.nanoTime() - s) / 1e6
        }
        t.sort()
        return t[runs / 2] to t[(runs * 95 / 100).coerceAtMost(runs - 1)]
    }

    @Test
    fun importTakesSeconds() {
        val store = PlaceStore.open(File(Fixtures.dir, "bench.db"))
        val t0 = System.nanoTime()
        val stats = store.importOverpass(Fixtures.ams)
        val ms = (System.nanoTime() - t0) / 1e6
        println("import ams/raw.json (8.7 MB): ${"%.0f".format(ms)} ms, ${stats.elements} elements, ${stats.places} places, ${stats.trees} trees; file ${File(Fixtures.dir, "bench.db").length() / 1024} KB")
        println("fixture imports: " + Fixtures.importStats.entries.joinToString { "${it.key} ${it.value.millis} ms / ${it.value.places} places" })
        assertTrue("import took $ms ms", ms < 15_000)
        store.close()
    }

    @Test
    fun typicalQueriesUnderBudget() {
        val evaluator = OfflineEvaluator(Fixtures.store)
        val ctx = Fixtures.context()
        val queries = linkedMapOf(
            "cafe near bicycle rental 200m" to QueryParser.parse("cafe near bicycle rental 200m").query,
            "café, dock and park chain" to QueryParser.parse("café 200 m from a bike dock and 400 m from a park").query,
            "vegan restaurant open now" to QueryParser.parse("vegan restaurant open now").query,
            "toilets wheelchair" to QueryParser.parse("toilets wheelchair").query,
            "amenity=cafe and outdoor_seating=yes" to QueryParser.parse("amenity=cafe and outdoor_seating=yes").query,
            "restaurant within 500 m" to QueryParser.parse("restaurant within 500 m").query,
            "eat (category group)" to Query.Category("eat"),
            "name: westerkrk" to Query.Name("westerkrk"),
            "name: albert hein" to Query.Name("albert hein"),
            "brand: Albert Heijn" to Query.Brand("albertheijn-1cf55d"),
            "café not near a bar" to QueryParser.parse("café not near a bar").query,
        )
        val report = StringBuilder("evaluator, median / p95 over 15 runs:\n")
        var worst = 0.0
        for ((label, q) in queries) {
            var total = 0
            val (median, p95) = timeMs { total = evaluator.run(q, ctx).total }
            report.append("  %-40s %6.2f ms  %6.2f ms  (%d matches)\n".format(label, median, p95, total))
            worst = maxOf(worst, median)
        }
        println(report)
        assertTrue("slowest median $worst ms", worst < budgetMs)
    }

    @Test
    fun theSearchBoxUnderBudget() = runBlocking {
        val engine = SearchEngine(Fixtures.store)
        val ctx = SearchContext(location = Fixtures.westerkerk, now = Fixtures.saturday)
        val texts = listOf(
            "pharm", "apotheek", "westerkrk", "albert hein", "prinsengracht 263", "52.3745, 4.8838", "cafe near bicycle rental 200m",
            "quiet café with an outlet, then dinner", "restaurant", "picture hooks", "where can I buy batteries",
        )
        val report = StringBuilder("search box, median / p95 over 15 runs:\n")
        var worst = 0.0
        for (t in texts) {
            val (median, p95) = timeMs { runBlocking { engine.search(t, ctx) } }
            report.append("  %-40s %6.2f ms  %6.2f ms\n".format(t, median, p95))
            worst = maxOf(worst, median)
        }
        println(report)
        assertTrue("slowest median $worst ms", worst < budgetMs * 2)
    }
}
