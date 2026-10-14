package app.atlas.engine

import app.atlas.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

class ColoursAndLightTest {
    @Test
    fun parsingAndMixing() {
        assertEquals(0xFF7A4434.toInt(), Colours.parse("#7A4434"))
        assertEquals(0xFFAABBCC.toInt(), Colours.parse("#abc"))
        assertEquals(0x80FFFFFF.toInt(), Colours.parse("#80FFFFFF"))
        assertEquals(null, Colours.parseOrNull("#12345"))
        assertEquals(Colours.rgb(128, 128, 128), Colours.mix(Colours.BLACK, Colours.WHITE, 0.5f) or 0)
        assertEquals("#7A4434", Colours.toHex(Colours.parse("#7a4434")))
        val g = Colours.desaturate(Colours.parse("#FF0000"), 1f)
        assertEquals(Colours.red(g), Colours.green(g))
    }

    @Test
    fun paintWordsAreHousePaint() {
        // Hex stays exact; a colour word moves toward a warm white; white stays white.
        assertEquals(Colours.parse("#BD8161"), Colours.paint("#bd8161"))
        assertEquals(Colours.WHITE, Colours.paint("white"))
        val brown = Colours.paint("brown")!!
        assertTrue(Colours.luma(brown) > Colours.luma(Colours.NAMED["brown"]!!))
        assertEquals(null, Colours.paint("sparkly"))
        assertEquals(Colours.NAMED["light_grey".replace("_", "")], Colours.named("light grey"))
    }

    @Test
    fun sunAndShade() {
        // Amsterdam, 1 October 2026, 16:00: the sun stands south-west, about 26° up.
        val instant = ZonedDateTime.of(2026, 10, 1, 16, 0, 0, 0, ZoneId.of("Europe/Amsterdam")).toInstant()
        val l = Light.at(instant, 52.3746, 4.8840)
        assertEquals(222.5, l.azimuth, 1.5)
        assertEquals(25.9, l.elevation, 1.5)
        // Toward the sun: west (negative x) and south (positive y, the engine's y runs south) and up.
        assertTrue(l.x < 0f && l.y > 0f && l.z > 0f)
        // Shadows fall away from the sun.
        assertTrue(l.shadowDx(10f) > 0f && l.shadowDy(10f) < 0f)
        val s = Shading(Theme.DAY, l)
        val brick = Colours.parse("#7A4434")
        val lit = s.shade(brick, -0.7f, 0.7f, 0f, true)
        val inShadow = s.shade(brick, -0.7f, 0.7f, 0f, false)
        val away = s.shade(brick, 0.7f, -0.7f, 0f, true)
        assertTrue(Colours.luma(lit) > Colours.luma(inShadow) + 20)
        assertEquals(inShadow, away)
        // Night has no sun: every face falls toward the ambient blue.
        val n = Shading(Theme.NIGHT, l)
        assertTrue(Colours.luma(n.shade(brick, -0.7f, 0.7f, 0f, true)) < Colours.luma(brick) * 0.6f)
        // A look never shades by day with the sun under 8°.
        assertEquals(8.0, Look(Theme.DAY, Light(200.0, 2.0)).light.elevation, 0.0)
        assertEquals(LatLon(0.0, 0.0), LatLon(0.0, 0.0))
    }

    @Test
    fun calmRampsInAsYouZoomOut() {
        assertEquals(0f, Look.defaultCalm(16.0), 0f)
        assertEquals(0f, Look.defaultCalm(15.0), 0f)
        assertTrue(Look.defaultCalm(13.0) in 0.1f..0.45f)
        assertEquals(0.55f, Look.defaultCalm(10.0), 1e-6f)
        assertEquals(0.3f, Look(calm = 0.3f).calmAt(18.0), 0f)
    }
}
