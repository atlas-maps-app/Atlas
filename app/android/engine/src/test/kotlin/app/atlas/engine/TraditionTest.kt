package app.atlas.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraditionTest {
    @Test
    fun everyDrawnProfileLoads() {
        for (id in Traditions.DRAWN) {
            val t = Traditions.get(id)
            assertNotNull(id, t)
            assertEquals(id, t!!.id)
            assertTrue(t.floorHeight in 2.5..4.0)
            assertNotNull(t.wallsFor("yes", "home", null))
            assertNotNull(t.roofFor("yes", "home", null) ?: t.roofFor("yes", "default", null))
        }
        assertEquals(36, Traditions.catalogue.size)
    }

    @Test
    fun placesChooseTheirTradition() {
        assertEquals("nl_amsterdam", Traditions.idForLocation(52.3746, 4.8826))
        assertEquals("nl_amsterdam", Traditions.idForLocation(52.0116, 4.3571)) // Delft
        assertEquals("fr_paris", Traditions.idForLocation(48.8566, 2.3522))
        assertEquals("it_tuscan", Traditions.idForLocation(43.4677, 11.0432)) // San Gimignano
        assertEquals("no_timber", Traditions.idForLocation(60.3913, 5.3221)) // Bergen
        assertEquals("gb_terrace", Traditions.idForLocation(51.5074, -0.1278)) // London
        assertEquals("gb_terrace", Traditions.idForLocation(54.597, -5.930)) // Belfast
        // Elsewhere, a palette for the climate band, made from the catalogue.
        assertEquals("modern_global", Traditions.idForLocation(50.85, 4.35)) // Brussels is not Dutch
        assertEquals("sea_shophouse", Traditions.idForLocation(1.29, 103.85))
        assertEquals("levant_stone", Traditions.idForLocation(31.77, 35.21))
        assertEquals("no_timber", Traditions.idForLocation(64.15, -21.94))
        val synth = Traditions.forLocation(1.29, 103.85)
        assertEquals("sea_shophouse", synth.id)
        assertTrue(synth.wallsFor("yes", "home", null)!!.isNotEmpty())
    }

    @Test
    fun erasPickByAge() {
        val nl = Traditions.get("nl_amsterdam")!!
        val old = nl.wallsFor("yes", "home", 1700)!!
        val newer = nl.wallsFor("yes", "home", 1960)!!
        assertTrue(old.any { it.colour == Colours.parse("#6E3B2E") })
        assertTrue(newer.none { it.colour == Colours.parse("#6E3B2E") })
        // Unknown age takes the default entry.
        assertEquals(newer.map { it.colour }, nl.wallsFor("yes", "home", null)!!.map { it.colour })
        val paris = Traditions.get("fr_paris")!!
        assertEquals("concrete", paris.wallsFor("yes", "home", 1970)!!.single().material)
        assertEquals("plaster", paris.wallsFor("yes", "home", 1800)!!.first().material)
    }

    @Test
    fun rulesFitTheBuildingsTheyName() {
        val nl = Traditions.get("nl_amsterdam")!!
        val rule = nl.rules.single()
        assertTrue(rule.fits("home", "yes", "brick", 1700, 4, 120.0, 7.0, 14.0))
        // Too new, too wide, too low, unknown age, wrong kind.
        assertTrue(!rule.fits("home", "yes", "brick", 1950, 4, 120.0, 7.0, 14.0))
        assertTrue(!rule.fits("home", "yes", "brick", 1700, 4, 120.0, 12.0, 14.0))
        assertTrue(!rule.fits("home", "yes", "brick", 1700, 4, 120.0, 7.0, 7.0))
        assertTrue(!rule.fits("home", "yes", "brick", null, 4, 120.0, 7.0, 14.0))
        assertTrue(!rule.fits("industrial", "yes", "brick", 1700, 4, 120.0, 7.0, 14.0))
        assertTrue(nl.oldTownRule)
        assertEquals(1800, nl.corniceBefore)
        assertEquals(1850, nl.hoistBefore)
        assertEquals("plain", nl.chimneys!!.kind)
        assertNull(nl.windows)
        val paris = Traditions.get("fr_paris")!!
        assertTrue(paris.rules.single().fits("home", "yes", "limestone", null, 6, 300.0, 15.0, 20.0))
        assertNotNull(paris.windows)
    }

    @Test
    fun picksAreStableAndWeighted() {
        val opts = listOf(WallOption("brick", 1, 14.0), WallOption("plaster", 2, 6.0))
        val picks = (0L until 4000L).map { BuildingGuesser.pick(opts, it).material }
        val share = picks.count { it == "plaster" } / 4000.0
        assertEquals(0.3, share, 0.04)
        assertEquals(BuildingGuesser.pick(opts, 42).material, BuildingGuesser.pick(opts, 42).material)
        val c = Colours.parse("#7A4434")
        val j = BuildingGuesser.jitter(c, 5, 0.035)
        assertTrue(kotlin.math.abs(Colours.red(j) - Colours.red(c)) <= 8)
        assertEquals(j, BuildingGuesser.jitter(c, 5, 0.035))
    }
}
