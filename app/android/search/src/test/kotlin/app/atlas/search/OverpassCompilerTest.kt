package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.providers.OverpassArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden tests: the QL behind the `{} QL` button, character for character. */
class OverpassCompilerTest {
    private val box = OverpassArea.Box(BBox(4.87, 52.37, 4.89, 52.38))
    private val me = LatLon(52.3745, 4.8838)

    private fun ql(text: String) = OverpassCompiler.compile(QueryParser.parse(text).query, box, me)

    @Test
    fun plainFilters() {
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="cafe"]["outdoor_seating"="yes"](52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("amenity=cafe and outdoor_seating=yes"),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["cuisine"~"pizza",i](52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("cuisine~pizza"),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            node["amenity"="bench"](52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("type:node amenity=bench"),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="cafe"](newer:"2026-01-01T00:00:00Z")(52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("amenity=cafe newer:2026-01-01"),
        )
    }

    @Test
    fun unitsOpeningHoursAndAreas() {
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["socket:type2_combo:output"](if: !is_number(t["socket:type2_combo:output"]) || number(t["socket:type2_combo:output"]) >= 150)(52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("\"socket:type2_combo:output\">=150kW"),
        )
        // toilets are open whenever their hours are not mapped: the client checks the hours, the server sends all
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="toilets"]["wheelchair"="yes"](52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("amenity=toilets wheelchair=yes open now"),
        )
        // a café is not: only those with hours can be open
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="cafe"]["opening_hours"](52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("amenity=cafe open now"),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            area["name"="Amsterdam"]["boundary"="administrative"]->.area1;
            nwr["amenity"="cafe"](area.area1)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("amenity=cafe in Amsterdam"),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="cafe"](around:500,52.3745,4.8838)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("cafe within 500 m"),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="fuel"](around:500,52.37,4.88,52.38,4.9)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            OverpassCompiler.compile(Query.and(Query.tags("amenity=fuel"), Query.AlongRoute(listOf(LatLon(52.37, 4.88), LatLon(52.38, 4.90)), 500.0)), null),
        )
    }

    @Test
    fun aNearBAndChains() {
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="bicycle_rental"](52.3682014,4.8670533,52.3817986,4.8929467)->.near1;
            nwr["shop"="bicycle"](around.near1:200)(52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            .near1 out tags bb qt;
            """.trimIndent(),
            ql("shop=bicycle near amenity=bicycle_rental 200m"),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="bicycle_rental"](52.3682014,4.8670533,52.3817986,4.8929467)->.near1;
            nwr["leisure"="park"](52.3682014,4.8670533,52.3817986,4.8929467)->.near2;
            nwr["amenity"="cafe"]["internet_access"~"^(wlan|yes|wifi|public)${'$'}",i](around.near1:200)(around.near2:200)(52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            .near1 out tags bb qt;
            .near2 out tags bb qt;
            """.trimIndent(),
            ql("wifi café near a bike dock & a park"),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="cafe"](52.37,4.87,52.38,4.89)->.s1;
            nwr["amenity"~"^(bar|pub|biergarten)${'$'}",i](52.3682014,4.8670533,52.3817986,4.8929467)->.near1;
            nwr.s1(around.near1:200)->.s2;
            (.s1; - .s2;)->.result;
            .result out tags bb qt;
            .near1 out tags bb qt;
            """.trimIndent(),
            ql("café not near a bar"),
        )
    }

    @Test
    fun brandsUnionsAndNegations() {
        assertEquals(
            """
            [out:json][timeout:60];
            (
              nwr["brand:wikidata"="Q1653985"](52.37,4.87,52.38,4.89);
              nwr["brand"="Albert Heijn"](52.37,4.87,52.38,4.89);
            )->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("albert heijn"),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            (
              nwr["amenity"="bar"]["cuisine"!="pizza"](52.37,4.87,52.38,4.89);
              nwr["amenity"="pub"]["cuisine"!="pizza"](52.37,4.87,52.38,4.89);
            )->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("(amenity=bar or amenity=pub) and not cuisine=pizza"),
        )
        // a cuisine list matches by its parts, as offline
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="restaurant"]["cuisine"~"(^|;)\\s*pizza\\s*(;|${'$'})"](52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("amenity=restaurant and cuisine=pizza"),
        )
        // what filters cannot say becomes a set difference
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["amenity"="cafe"](52.37,4.87,52.38,4.89)->.s1;
            nwr["amenity"="bar"](52.3695503,4.8692634,52.3804497,4.8907366)->.near1;
            nwr(around.near1:50)(52.37,4.87,52.38,4.89)->.s2;
            (.s1; - .s2;)->.result;
            .result out tags bb qt;
            .near1 out tags bb qt;
            """.trimIndent(),
            OverpassCompiler.compile(Query.and(Query.tags("amenity=cafe"), Query.Not(Query.Near(Query.All, Query.tags("amenity=bar"), 50.0))), box),
        )
    }

    @Test
    fun namesCategoriesAndLimits() {
        assertEquals(
            """
            [out:json][timeout:60];
            nwr[~"^(name|name:[a-z_-]+|alt_name|old_name|official_name|short_name|loc_name|brand)${'$'}"~"Café.*Molen",i](52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            OverpassCompiler.compile(Query.Name("Café Molen"), box),
        )
        assertEquals(
            """
            [out:json][timeout:60];
            (
              nwr["amenity"~"^(bar|pub|biergarten|nightclub)${'$'}",i](52.37,4.87,52.38,4.89);
              nwr["shop"~"^(alcohol|wine|beverages)${'$'}",i](52.37,4.87,52.38,4.89);
            )->.result;
            .result out tags bb qt;
            """.trimIndent(),
            OverpassCompiler.compile(Query.Category("drinks"), box),
        )
        assertTrue(OverpassCompiler.compile(Query.Limit(Query.tags("amenity=cafe"), 10), box).contains(".result out tags bb qt 10;"))
        // a client-side check (opening hours) needs every candidate: no limit then
        assertTrue(OverpassCompiler.compile(Query.Limit(Query.and(Query.tags("amenity=cafe"), Query.OpenAt(OpenTime.Now)), 10), box).contains(".result out tags bb qt;"))
        assertEquals(
            """
            [out:json][timeout:60];
            node(id:26973327)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            OverpassCompiler.compile(Query.Id(app.atlas.model.OsmId.parse("n26973327")!!), null),
        )
    }

    @Test
    fun manyAlternativesBecomeSetsInsteadOfRecursingForever() {
        // 6 × 6 alternatives are more than one statement each should spell: each disjunction becomes a set
        val big = OverpassCompiler.compile(Query.tags("(a=1 or a=2 or a=3 or a=4 or a=5 or a=6) and (b=1 or b=2 or b=3 or b=4 or b=5 or b=6) and c=x"), box)
        assertTrue(big, big.contains("->.s1;") && big.contains("->.s2;"))
        assertTrue(big, big.contains("nwr.s1.s2[\"c\"=\"x\"]->.result;"))
        for (v in 1..6) assertTrue(big, big.contains("[\"a\"=\"$v\"]") && big.contains("[\"b\"=\"$v\"]"))
        // what the fuzzing found: goods (a union of shops) with another goods word and a property
        val goods = OverpassCompiler.compile(QueryParser.parse("wifi batteries hooks wheelchair=yes").query, box)
        assertTrue(goods, goods.contains("[\"shop\"=\"electronics\"]") && goods.contains("[\"shop\"=\"variety_store\"]"))
        // filters that multiply too far next to a part that needs set algebra are kept, not dropped
        val q = Query.and(
            Query.tags("a=1 or a=2 or a=3 or a=4 or a=5 or a=6"), Query.tags("b=1 or b=2 or b=3 or b=4 or b=5 or b=6"),
            Query.NotNear(Query.tags("amenity=cafe"), Query.tags("amenity=bar"), 50.0),
        )
        val kept = OverpassCompiler.compile(q, box)
        for (v in 1..6) assertTrue(kept, kept.contains("[\"a\"=\"$v\"]") && kept.contains("[\"b\"=\"$v\"]"))
        assertTrue(kept, kept.contains("[\"amenity\"=\"cafe\"]"))
    }

    @Test
    fun aNearSetAskedTwiceIsFetchedOnce() {
        val q = Query.and(
            Query.Near(Query.tags("amenity=cafe"), Query.tags("amenity=bicycle_rental"), 200.0),
            Query.NotNear(Query.All, Query.tags("amenity=bar"), 50.0),
        )
        val ql = OverpassCompiler.compile(q, box)
        assertEquals(ql, 1, Regex("""\["amenity"="bicycle_rental"]""").findAll(ql).count())
        assertEquals(ql, 1, Regex("""\["amenity"="bar"]""").findAll(ql).count())
        assertEquals(ql, 2, Regex("""out tags bb qt;""").findAll(ql).count() - 1)
        assertTrue(ql, !ql.contains("near3"))
    }

    @Test
    fun anchoredWordsOnAListKeyMatchOnePart() {
        // offline, "italian;pizza" passes cuisine~^(pizza|pasta)$; online it must too
        assertEquals(
            """
            [out:json][timeout:60];
            nwr["cuisine"~"(^|;)\\s*(pizza|pasta)\\s*(;|${'$'})",i](52.37,4.87,52.38,4.89)->.result;
            .result out tags bb qt;
            """.trimIndent(),
            ql("cuisine~^(pizza|pasta)$"),
        )
        assertTrue(app.atlas.places.TagFilter.parse("cuisine~^(pizza|pasta)$").matches(mapOf("cuisine" to "italian;pizza")))
    }

    @Test
    fun aQueryThatSelectsEverythingNeedsAnArea() {
        for (q in listOf(Query.Not(Query.Near(Query.All, Query.tags("amenity=bar"), 50.0)), Query.OpenAt(OpenTime.Now), Query.Type(app.atlas.model.OsmType.NODE))) {
            val e = runCatching { OverpassCompiler.compile(q, null) }.exceptionOrNull()
            assertTrue("$q: $e", e is IllegalArgumentException)
        }
        // with an area it is fine
        assertTrue(OverpassCompiler.compile(Query.OpenAt(OpenTime.Now), box).contains("nwr(52.37,4.87,52.38,4.89)->.result;"))
    }
}
