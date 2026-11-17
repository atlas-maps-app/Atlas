package app.atlas.mapview.vk

import app.atlas.mapcore.CameraState
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapview.HomeView
import app.atlas.mapview.MapPart
import app.atlas.mapview.MapParts
import app.atlas.render.live.RegionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln

/**
 * VK bench's real map ([MapBench]): the lap (fixed, from the home's look through the Westerkerk, the Dam and 120 km out
 * and back, flat at pitch 0), the setups (the core's passes by its registry's names, the sun and effects as the Look
 * settings' parts), the battery's power, the split by pass, the camera source at rest, and the table with its budgets.
 */
class MapBenchTest {
    private fun at(t: Double, setup: MapBench.Setup = MapBench.Setup.FULL) = VkBench.Camera().also { MapBench.cameraAt(t, setup, it) }

    /** Metres between two cameras' targets. */
    private fun metres(a: VkBench.Camera, b: VkBench.Camera): Double {
        val dy = (a.latitude - b.latitude) * 110_574.0
        val dx = (a.longitude - b.longitude) * 111_320.0 * cos(Math.toRadians(a.latitude))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun turn(a: Double, b: Double): Double = abs(((b - a + 540.0) % 360.0) - 180.0)

    @Test
    fun theLapGoesFromTheHomesLookThroughTheWesterkerkAndTheDamOutTo120KmAndBack() {
        val start = at(0.0)
        val end = at(1.0)
        // it begins and ends at the map home's first look, the Main board's view of the canal ring
        for (c in listOf(start, end)) {
            assertEquals(HomeView.TARGET.lat, c.latitude, 1e-9)
            assertEquals(HomeView.TARGET.lon, c.longitude, 1e-9)
            assertEquals(HomeView.BEARING, c.bearing, 1e-9)
            assertEquals(HomeView.PITCH, c.pitch, 1e-9)
            assertEquals(HomeView.DISTANCE_M, c.distance, 1e-6)
        }
        // the Westerkerk's street view (the living map's home), the Dam, the turn there and the far view, at their keys
        val living = RegionFrame.living()
        val street = at(0.16)
        assertEquals(living.lat(-10.0, -30.0), street.latitude, 1e-9)
        assertEquals(living.lon(-10.0, -30.0), street.longitude, 1e-9)
        assertEquals(430.0, street.distance, 1e-6)
        assertEquals(52.0, street.pitch, 1e-9)
        assertEquals(100.0, at(0.40).bearing, 1e-9)
        assertEquals(100.0, at(0.58).bearing, 1e-9) // a whole turn at the Dam
        assertEquals(62.0, at(0.58).pitch, 1e-9)
        assertEquals(MapBench.FAR_DISTANCE, at(0.80).distance, 1e-6)
        assertEquals(listOf("in", "along", "turn", "out", "back"), MapBench.PARTS)
        assertEquals("in", MapBench.partAt(0.1))
        assertEquals("along", MapBench.partAt(0.3))
        assertEquals("turn", MapBench.partAt(0.5))
        assertEquals("out", MapBench.partAt(0.7))
        assertEquals("back", MapBench.partAt(0.9))
        // every stop lies on the lap, in its part
        assertEquals("in", MapBench.partAt(MapBench.Stop.STREET.t))
        assertEquals("turn", MapBench.partAt(MapBench.Stop.TURN.t))
        assertEquals("out", MapBench.partAt(MapBench.Stop.FAR.t))
    }

    @Test
    fun theLapsCameraMovesSmoothlyAndTheSameEveryTime() {
        // sampled at 120 Hz over a 45 s lap: the same cameras on every call, and no step a gesture could not make (the
        // target moves less than a tenth of the view's distance a frame, the distance by less than 3 %, the bearing by
        // under a degree and a half), the distance between the street's 430 m and the far view's 120 km
        val n = 45 * 120
        var last = at(0.0)
        for (i in 1..n) {
            val t = i.toDouble() / n
            val c = at(t)
            val again = at(t)
            assertEquals(c.latitude, again.latitude, 0.0)
            assertEquals(c.bearing, again.bearing, 0.0)
            assertTrue("distance at $t: ${c.distance}", c.distance in 429.0..120_001.0)
            assertTrue("target step at $t: ${metres(last, c)} m at ${c.distance} m", metres(last, c) < 0.1 * minOf(c.distance, last.distance))
            assertTrue("zoom step at $t", abs(ln(c.distance / last.distance)) < 0.03)
            assertTrue("turn step at $t: ${turn(last.bearing, c.bearing)}", turn(last.bearing, c.bearing) < 1.5)
            assertTrue(c.bearing in 0.0..360.0)
            last = c
        }
        // flat draws the same lap at pitch 0 throughout
        for (i in 0..100) {
            val t = i / 100.0
            val flat = at(t, MapBench.Setup.FLAT)
            val full = at(t)
            assertEquals(0.0, flat.pitch, 0.0)
            assertEquals(full.latitude, flat.latitude, 0.0)
            assertEquals(full.distance, flat.distance, 0.0)
            assertEquals(full.bearing, flat.bearing, 0.0)
        }
        // a lap of 45 s: a frame 9 s in stands a fifth of the way round, and none goes past its end
        assertEquals(0.2, MapBench.phase(9.0, 45.0), 1e-12)
        assertEquals(1.0, MapBench.phase(50.0, 45.0), 0.0)
        assertEquals(0.0, MapBench.phase(3.0, 0.0), 0.0)
    }

    @Test
    fun theSetupsPassesAreTheCoresRegistrysAndTheSunAndEffectsTheLookSettingsParts() {
        // the passes by the names the core's registry gives them, in its order (CP/frame/PassRegistry.cpp)
        val registry = File("../mapcore/cpp/frame/PassRegistry.cpp").readText()
        val named = Regex("""Registration\{\.name = "([a-z_]+)"""").findAll(registry).map { it.groupValues[1] }.toList()
        assertEquals(named, MapBench.PASSES)
        assertTrue(MapBench.FLAT_PASSES.all { it in MapBench.PASSES })
        assertEquals("", MapBench.Setup.FULL.passes)
        assertEquals("", MapBench.Setup.NO_SUN.passes)
        assertEquals("composite,ground,ground_text,routes,markers,labels,overlays,world", MapBench.Setup.FLAT.passes)
        for (p in listOf("buildings", "trees", "things", "items", "doors", "shopfronts", "house", "frosted")) {
            assertFalse(p, p in MapBench.FLAT_PASSES)
        }
        // the sun and the effects: the shadows with the sun, the water's light, the weather and the night
        val off = MapBench.SUN_AND_EFFECTS
        for (p in listOf(
            MapPart.SUN, MapPart.BUILDING_SHADOWS, MapPart.SHADOW_BLUR, MapPart.PARK_SHADOWS, MapPart.CLOUD_SHADOWS,
            MapPart.REFLECTIONS, MapPart.RIPPLE, MapPart.GLITTER, MapPart.RAIN, MapPart.GOLDEN_WASH, MapPart.LIT_STREETS,
        )) {
            assertTrue(p.name, off and p.bit != 0L)
        }
        for (p in listOf(MapPart.GROUND, MapPart.WATER, MapPart.WALLS, MapPart.ROOFS, MapPart.TREES, MapPart.LABELS, MapPart.FROSTED, MapPart.SKY)) {
            assertEquals(p.name, 0L, off and p.bit)
        }
        assertEquals(0L, off ushr CameraWriter.PARTS) // the light cascades' switch never reaches the core as a part
        // each setup leaves out what the app does, and the sun and effects beside it but in full
        val app = MapParts.bits(MapParts.byDefault)
        assertEquals(MapParts.cameraBits(app), MapBench.Setup.FULL.parts(app))
        assertEquals(MapParts.cameraBits(app) or off, MapBench.Setup.NO_SUN.parts(app))
        assertEquals(MapParts.cameraBits(app) or off, MapBench.Setup.FLAT.parts(app))
        // the screen's haze, vignette and grain as the Look settings have them in full alone
        assertTrue(MapBench.Setup.FULL.screen(true))
        assertFalse(MapBench.Setup.FULL.screen(false))
        assertFalse(MapBench.Setup.NO_SUN.screen(true))
        assertFalse(MapBench.Setup.FLAT.screen(true))
    }

    @Test
    fun theSplitLeavesOutEachPassThatDrewButTheComposite() {
        val counts = mapOf("composite" to 1, "frosted" to 2, "ground" to 40, "buildings" to 81, "trees" to 0, "labels" to 3, "world" to 5)
        assertEquals(listOf("frosted", "ground", "buildings", "labels", "world"), MapBench.split(MapBench.Setup.FULL, counts))
        // flat records the buildings' pass no more: it is not split
        assertEquals(listOf("ground", "labels", "world"), MapBench.split(MapBench.Setup.FLAT, counts))
        assertEquals(MapBench.PASSES.filter { it != "buildings" }.joinToString(","), MapBench.without(MapBench.Setup.FULL, "buildings"))
        assertEquals("composite,ground,ground_text,routes,markers,labels,overlays", MapBench.without(MapBench.Setup.FLAT, "world"))
        val line = MapBench.splitLine(MapBench.Setup.FULL, MapBench.Split(MapBench.Stop.STREET, 9.1, listOf("ground" to 2.1, "buildings" to 4.25, "world" to -0.1)))
        assertEquals("vkbench real full passes at street: every 9.10 ms; ground 2.10, buildings 4.25, world -0.10", line)
        assertEquals(
            "vkbench real flat passes at far: every — ms; none drew",
            MapBench.splitLine(MapBench.Setup.FLAT, MapBench.Split(MapBench.Stop.FAR, Double.NaN, emptyList())),
        )
    }

    @Test
    fun thePowerIsTheBatterysCurrentTimesItsVoltage() {
        // CURRENT_NOW in µA, negative while discharging on a Pixel; 3.85 V
        assertEquals(1.925, MapBench.watts(-500_000, 3850), 1e-9)
        assertEquals(1.925, MapBench.watts(500_000, 3850), 1e-9)
        // a device that reports mA
        assertEquals(1.925, MapBench.watts(-500, 3850), 1e-9)
        assertTrue(MapBench.watts(Int.MIN_VALUE, 3850).isNaN())
        assertTrue(MapBench.watts(0, 3850).isNaN())
        assertTrue(MapBench.watts(-500_000, 0).isNaN())
        val p = MapBench.Power()
        p.add(2.0)
        p.add(Double.NaN)
        p.add(3.0)
        assertEquals(2, p.samples)
        assertEquals("2.50 W", p.said())
        assertEquals("— (no reading)", MapBench.Power().said())
        val plugged = MapBench.Power().apply {
            why = "plugged in: unplug the phone for the power figure"
            add(2.0)
        }
        assertTrue(plugged.mean.isNaN())
        assertEquals("— (plugged in: unplug the phone for the power figure)", plugged.said())
    }

    @Test
    fun theCameraSourceDrawsTheLapWithItsPartsAndAtRestWritesOnceThenAsksNone() {
        val shapes = ChromeShapes()
        val source = BenchSource(CameraState(), shapes)
        source.running = true
        source.widthPx = 1080
        source.heightPx = 2400
        source.density = 2.625
        source.pillsOn = false
        val out = CameraWriter.allocate()
        // the stand-in's camera writes no parts
        assertTrue(source.camera(16_666_667L, out))
        assertEquals(0L, out.getLong(CameraWriter.PARTS_AT))
        // the lap in flat: its parts, pitch 0, the lap's camera at its point
        val app = MapParts.bits(MapParts.byDefault)
        source.lap = MapBench.Setup.FLAT
        source.lapParts = MapBench.Setup.FLAT.parts(app)
        source.lapAt = 0.16
        source.worldUnder = true
        assertTrue(source.camera(33_333_333L, out))
        assertEquals(MapBench.Setup.FLAT.parts(app), out.getLong(CameraWriter.PARTS_AT))
        assertEquals(0.0, out.getDouble(CameraWriter.PITCH_AT), 0.0)
        assertEquals(430.0, out.getDouble(CameraWriter.DISTANCE_AT), 1e-6)
        assertEquals(CameraWriter.WORLD_UNDER, out.getInt(CameraWriter.FLAGS_AT) and CameraWriter.WORLD_UNDER)
        assertEquals(0, out.getInt(CameraWriter.FLAGS_AT) and CameraWriter.REST)
        // a still point sways, so that every frame is drawn
        assertTrue(source.camera(1_000_000_000L, out))
        assertTrue(source.camera(2_000_000_000L, out))
        // at rest: the camera written once, flagged at rest, then nothing asked until the window needs a frame again
        source.rest = true
        assertTrue(source.camera(3_000_000_000L, out))
        assertEquals(CameraWriter.REST, out.getInt(CameraWriter.FLAGS_AT) and CameraWriter.REST)
        assertFalse(source.camera(3_016_666_667L, out))
        assertFalse(source.camera(4_000_000_000L, out))
        source.redraw()
        assertTrue(source.camera(4_016_666_667L, out))
        assertFalse(source.camera(4_033_333_333L, out))
        source.rest = false
        assertTrue(source.camera(4_050_000_000L, out))
        // the lap over: the parts go with it
        source.lap = null
        assertTrue(source.camera(5_000_000_000L, out))
        assertEquals(0L, out.getLong(CameraWriter.PARTS_AT))
    }

    private fun result(setup: MapBench.Setup, interval: Double, gpu: Double, restFrames: Long): MapBench.Result {
        val r = MapBench.Result(setup)
        for (i in 0 until 200) r.laps.interval(i / 200.0, if (i % 13 == 12) interval * 2 else interval)
        for (i in 0 until 200) r.laps.gpu += gpu
        for (i in 0 until 200) r.laps.cpu += 5.0
        r.laps.presented = 200
        r.laps.complete = 190
        r.laps.memory(412.0, 305.0)
        r.laps.power.add(3.21)
        r.rest = MapBench.Rest(restFrames, 10.0, MapBench.Power().apply { add(1.12) }, true)
        r.splits += MapBench.Split(MapBench.Stop.STREET, gpu, listOf("ground" to 2.1, "buildings" to 4.2))
        r.splits += MapBench.Split(MapBench.Stop.FAR, gpu / 2, listOf("world" to 1.5))
        return r
    }

    @Test
    fun theTableHasARowASetupWithTheBudgetsBesideIt() {
        val head = MapBench.Head(
            date = "2026-11-17 09:12 CET", device = "Google Pixel 10", android = "17 (API 37)", gpu = "PowerVR DXT-48-1536, driver 25.3, config C",
            build = "build 0.1.0-alpha.42", timing = MapBench.timing(VkBench.Length.MAP), tiles = "212 windows and packs, 14 of the world",
            battery = "78 %, unplugged, 31.5 °C", thermal = "0.42 at the first lap, 0.71 at the end (1 is throttling)", look = "The sunny moment",
        )
        val results = listOf(
            result(MapBench.Setup.FULL, 16.6, 9.1, 0),
            result(MapBench.Setup.NO_SUN, 12.0, 6.4, 0),
            result(MapBench.Setup.FLAT, 25.0, 2.1, 3),
        )
        val text = MapBench.table(head, results)
        val lines = text.lines()
        assertTrue(lines[0], lines[0].startsWith("Atlas real-map bench · 2026-11-17 09:12 CET · Google Pixel 10 · Android 17 (API 37) · "))
        assertTrue(text, text.contains("Budgets: p50 ≤ 16.7 ms, p95 ≤ 20.0 ms (presented intervals), 0 frames at rest."))
        assertTrue(text, text.contains(MapBench.LAP_WORDS))
        val row = lines.first { it.startsWith("full ") }
        assertTrue(row, row.contains("16.60") && row.contains("33.20") && row.contains("9.10") && row.contains("3.21 W") && row.contains("412"))
        assertTrue(row, row.endsWith("p50 ok, p95 over"))
        assertTrue(lines.first { it.startsWith("nosun ") }.endsWith("p50 ok, p95 over"))
        assertTrue(lines.first { it.startsWith("flat ") }.endsWith("p50 over, p95 over"))
        // the drops: an interval of two frames' time is a frame missed, 15 of them a lap
        assertTrue(row, Regex("""\s15\s""").containsMatchIn(row))
        assertTrue(text, text.contains("full 0 frames ok, 1.12 W · nosun 0 frames ok, 1.12 W · flat 3 frames over, 1.12 W"))
        assertTrue(text, text.contains("p50/p95 ms by part of the lap (in · along · turn · out · back):"))
        assertTrue(text, text.contains("GPU ms by pass, what leaving it out saves (street: the Westerkerk at 430 m · far: 120 km over the Netherlands):"))
        val split = lines.first { it.startsWith("full ") && it.contains("every") }
        assertEquals("full  every 9.10 · 4.55; ground 2.10 · —; buildings 4.20 · —; world — · 1.50", split)
        // sent from the phone as text: no line longer than a message can take whole, every line logged for CI
        assertTrue(lines.all { it.length < 400 })
        assertEquals(lines.size, MapBench.tableLines(text).size)
        assertTrue(MapBench.tableLines(text).all { it.startsWith("vkbench table: ") })
    }

    @Test
    fun theLinesCiKeepsAreNamedBySetup() {
        // smoke.sh's checks: `vkbench real <key> lap: p50 `, `… rest: frames `, `… passes at street: every `
        val s = MapBench.Laps().apply { interval(0.5, 16.0) }.summary()
        for (setup in MapBench.Setup.entries) {
            assertTrue(VkBench.line(MapBench.lapLabel(setup), s, 100.0, 50.0, "complete 1 of 1").startsWith("vkbench real ${setup.key} lap: p50 16.00 "))
            assertTrue(MapBench.restLine(setup, MapBench.Rest(0, 3.0, MapBench.Power(), true)).startsWith("vkbench real ${setup.key} rest: frames 0 in 3 s (ok; "))
        }
        assertEquals(listOf("full", "nosun", "flat"), MapBench.Setup.entries.map { it.key })
        assertEquals(305.0, MapBench.gpuMemory("vk frame 12 cpu 4.00 ms gpu 3.00 ms record 1.00 ms draws 40 triangles 900k graphics 305.0 MB; vk tiles 3"), 0.0)
        assertTrue(MapBench.gpuMemory("vk no frames").isNaN())
        // the table says whether the lap's map was all there, and names what was not
        assertEquals("every window, level and world tile the lap draws was there", MapBench.missingWords(emptyList()))
        assertEquals("2 not there: window/14/8414/5384, world 6/32/21", MapBench.missingWords(listOf("window/14/8414/5384", "world 6/32/21")))
        assertEquals(
            "10 not there: w0, w1, w2, w3, w4, w5, w6, w7 and 2 more",
            MapBench.missingWords((0 until 10).map { "w$it" }),
        )
        // CI's run is short and at one stop; the phone's takes every stop, two rounds
        assertEquals(listOf(MapBench.Stop.STREET), MapBench.timing(VkBench.Length.CI).stops)
        assertEquals(MapBench.Stop.entries, MapBench.timing(VkBench.Length.MAP).stops)
        assertEquals(2, MapBench.timing(VkBench.Length.MAP).rounds)
    }
}
