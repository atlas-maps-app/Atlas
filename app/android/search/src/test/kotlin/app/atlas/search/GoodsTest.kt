package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.places.Symbols
import app.atlas.places.TagFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Find board: things to buy, and the kinds of shop that sell them. */
class GoodsTest {
    private val engine by lazy { SearchEngine(Fixtures.store) }
    private val ctx = SearchContext(location = Fixtures.westerkerk, now = Fixtures.saturday)

    private fun search(text: String, lang: String = "en") = runBlocking { engine.search(text, ctx.copy(lang = lang)) }

    @Test
    fun theCatalogueIsWellFormed() {
        val all = GoodsCatalogue.all
        assertTrue("items ${all.size}", all.size >= 60)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (g in all) {
            for (lang in Lexicon.LANGS) assertTrue("${g.id} in $lang", g.phrases[lang].orEmpty().isNotEmpty())
            assertTrue(g.id, g.sellers.isNotEmpty())
            assertEquals(g.id, g.sellers.map { it.likelihood }.sortedDescending(), g.sellers.map { it.likelihood })
            for (s in g.sellers) {
                assertNotNull("${g.id}: ${s.filter.toText()} has no preset", s.preset)
                assertTrue("${g.id}: ${s.symbol}", Symbols.has(s.symbol))
                assertTrue(s.name("nl").isNotBlank())
            }
        }
    }

    @Test
    fun goodsWordsTakeNoOtherWordsMeaning() {
        // a goods phrase must not hide a word the lexicon already reads otherwise (a kind, a property, a cuisine)
        val clashes = ArrayList<String>()
        for (g in GoodsCatalogue.all) for ((lang, phrases) in g.phrases) for (p in phrases) {
            val words = p.split(' ')
            val same = Lexicon.lookup(words, 0, lang).filter { it.words == words }
            val others = same.filter { e ->
                val m = e.meaning
                !(m is Meaning.Noun && m.id.startsWith("goods:")) && (m !is Meaning.Noun || m.rank <= 3)
            }
            if (others.isNotEmpty()) clashes += "$lang:$p (${g.id}) vs ${others.map { (it.meaning as? Meaning.Noun)?.id ?: it.meaning::class.simpleName }}"
        }
        assertEquals(emptyList<String>(), clashes)
    }

    @Test
    fun pictureHooksAsOnTheFindBoard() {
        val r = search("picture hooks")
        val g = r.items.first() as SearchItem.GoodsResult
        assertEquals("hooks", g.goods.id)
        assertEquals("picture hooks", g.label)
        // HARDWARE ●●●●●, VARIETY STORE ●●●●○, DEPARTMENT ●●●○○, STATIONERY ●●○○○, SUPERMARKET ●○○○○
        assertEquals(listOf(5, 4, 3, 2, 1), g.sellers.map { it.likelihood })
        assertEquals(listOf("shop~^(hardware|doityourself)$", "shop=variety_store", "shop=department_store", "shop=stationery", "shop=supermarket"),
            g.sellers.map { it.seller.filter.toText() })
        val hardware = g.sellers.first()
        assertTrue(hardware.count >= 2)
        assertTrue(hardware.nearest.all { it.tags["shop"] in setOf("hardware", "doityourself") })
        assertEquals(hardware.nearest.map { it.distance }.sortedBy { it }, hardware.nearest.map { it.distance })
        // the places: the likeliest shops first, each said why
        val first = r.places.first()
        assertEquals(MatchKind.GOODS, first.match)
        assertTrue(first.hit.tags["shop"] in setOf("hardware", "doityourself"))
        assertTrue(first.hit.why.contains("goods:hooks"))
    }

    @Test
    fun inFourLanguagesWithVerbsAndTypos() {
        for ((text, lang, id) in listOf(
            Triple("batteries", "en", "batteries"), Triple("where can I buy batteries", "en", "batteries"),
            Triple("baterries", "en", "batteries"), Triple("postzegels", "nl", "stamps"), Triple("Briefmarken kaufen", "de", "stamps"),
            Triple("où acheter des piles", "fr", "batteries"), Triple("fietsenmaker", "nl", "bike_repair"), Triple("phone charger", "en", "phone_charger"),
        )) {
            val g = search(text, lang).items.filterIsInstance<SearchItem.GoodsResult>().singleOrNull()
            assertEquals(text, id, g?.goods?.id)
        }
        // seller names in the user's language
        val nl = search("postzegels", "nl").items.first() as SearchItem.GoodsResult
        assertEquals("Postkantoor", nl.sellers.first().label)
        assertTrue(nl.sellers.first().count > 0)
    }

    @Test
    fun goodsInAQueryRankTheLikelierShopsFirst() {
        val p = QueryParser.parse("stamps near me", "en", Fixtures.saturday)
        assertTrue(p.structured)
        assertEquals("goods:stamps", p.chips.first().value)
        val hits = OfflineEvaluator(Fixtures.store).run(p.query, Fixtures.context()).hits
        assertTrue(hits.isNotEmpty())
        val sellers = GoodsCatalogue.byId("stamps")!!.sellers
        assertTrue(hits.all { h -> sellers.any { it.filter.matches(h.tags) } && h.distance!! <= 1000 })
        // a post office counts double: one 400 m away beats a bookshop next door
        val post = Query.Tags(TagFilter.parse("amenity=post_office"))
        val near = GoodsCatalogue.byId("stamps")!!.query
        val ranked = OfflineEvaluator(Fixtures.store).run(near, Fixtures.context().copy(limit = 200)).hits
        val firstPost = ranked.indexOfFirst { it.tags["amenity"] == "post_office" }
        val firstBook = ranked.indexOfFirst { it.tags["shop"] == "books" && it.tags["amenity"] == null }
        assertTrue(OfflineEvaluator(Fixtures.store).run(post, Fixtures.context()).total > 0)
        assertTrue("post $firstPost, books $firstBook", firstPost in 0 until firstBook)
    }

    @Test
    fun goodsCompileToAUnionOfShops() {
        val box = BBox(4.87, 52.37, 4.89, 52.38)
        assertEquals(
            """
            [out:json][timeout:60];
            (
              nwr["shop"~"^(hardware|doityourself)${'$'}",i](52.37,4.87,52.38,4.89);
              nwr["shop"="variety_store"](52.37,4.87,52.38,4.89);
              nwr["shop"="department_store"](52.37,4.87,52.38,4.89);
              nwr["shop"="stationery"](52.37,4.87,52.38,4.89);
              nwr["shop"="supermarket"](52.37,4.87,52.38,4.89);
            )->.result;
            .result out tags bb qt;
            """.trimIndent(),
            OverpassCompiler.compile(QueryParser.parse("picture hooks").query, box),
        )
    }
}
