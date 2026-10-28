package app.atlas.render.live

import app.atlas.render.Amsterdam
import app.atlas.render.City
import app.atlas.render.Regions
import app.atlas.render.V3
import app.atlas.render.data.PackReader
import app.atlas.render.py.Dict
import app.atlas.render.py.PyCompat
import app.atlas.render.py.PyJson
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ScreenLayers] (K5): at a render's camera, the screen layers made from the camera, the moment's theme and the camera's
 * settings alone, outside the render, are the render's own fragments, byte for byte and in their places: the
 * definitions in order in the shared `<defs>`, the sky first on the ground layer, and `wfx`, the haze (with the golden
 * wash) and `fin` closing the last layer.
 *
 * The cameras: the engine's fixed renders (the Westerkerk home at its four moments, the Main board, San Gimignano's close
 * view in the rain, as [EngineVersion.FIXED] names them), drawn through the live engine from the packs as the phone draws
 * them; and the Westerkerk's eye-level board cameras (the canal, the Westermarkt, the Herengracht's hearts), where the
 * sky layer, the sun's glow, the city's glow on the night cloud and the haze band on the horizon show.
 */
class ScreenLayersTest {
    /** One render to check: its layers, and what ScreenLayers needs from outside it. */
    class Case(val name: String, val layers: List<String>, val cfg: Dict, val themeKey: String, val theme: Dict, val layer: String?)

    companion object {
        private val repo: File = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile
        private var work: File? = null
        private var workBefore: File? = null
        private var minorBefore = 9
        private var amsRawBefore: File? = null
        private val cases = ArrayList<Case>()
        private val missing = ArrayList<String>()
        /** The canal at wet drawn with the app's Look settings' grain off (RenderOptions.grain): its layers. */
        private var grainOff: List<String> = emptyList()

        /** The moment's theme as the render takes it: the `THEMES` entry, with its sun vector. */
        private fun themeOf(key: String): Dict {
            val th: Dict = LinkedHashMap(Amsterdam.THEMES.getValue(key))
            th["_L"] = Amsterdam.sunOf(th).third
            return th
        }

        @BeforeClass
        @JvmStatic
        fun renderTheCases() {
            minorBefore = PyCompat.minor
            PyCompat.minor = 9
            workBefore = LiveEngine.workDir
            val dir = Files.createTempDirectory("screen-layers").toFile()
            work = dir
            LiveEngine.workDir = dir
            LiveEngine.forget()
            for ((region, fixed) in EngineVersion.FIXED.groupBy { it.region }) {
                val pack = EngineVersionTest.packOf(region)
                if (!pack.isFile) {
                    missing += fixed.map { it.name }
                    continue
                }
                val live = PackReader.open(pack).use { LiveEngine.open(it, "screen-layers-$region") }
                for (f in fixed) {
                    val r = LiveEngine.render(live, f.liveView(live), Moment.Named(f.moment)) ?: error("${f.name} was cancelled")
                    assertEquals("${f.name} snaps to its view", f.view, r.view)
                    // right after the render the engine's module state is the scene's: its THEMES and its clock
                    cases += Case(f.name, r.layers, r.cfg, r.themeKey, themeOf(r.themeKey), "split")
                }
                LiveEngine.forget(live)
            }
            // the eye-level board cameras on the Westerkerk's own extract: the sky layer, the sun's and the city's glow,
            // the haze band; golden for the wash, and one front layer, which leaves the sky out
            amsRawBefore = Amsterdam.amsRaw
            Amsterdam.amsRaw = File(repo, "data/ams/raw.json")
            Regions.resetPlace()
            val S = Amsterdam.load()
            val dirCfg = File(repo, "app/android/render/src/test/resources/amsterdam")
            for (view in listOf("canal", "wmarkt", "harten")) {
                @Suppress("UNCHECKED_CAST")
                val cfg = LinkedHashMap(PyJson.loads(File(dirCfg, "${view}_kit_cfg.json").readText()) as Dict)
                for (m in listOf("sunny", "grey", "wet", "golden")) {
                    val r = Amsterdam.render(S, m, cfg, null, "split")
                    cases += Case("$view-$m", listOf(r.svg) + r.objs!!, cfg, m, themeOf(m), "split")
                }
            }
            @Suppress("UNCHECKED_CAST")
            val canal = LinkedHashMap(PyJson.loads(File(dirCfg, "canal_kit_cfg.json").readText()) as Dict)
            cases += Case("canal-wet-front", listOf(Amsterdam.render(S, "wet", canal, null, "front").svg), canal, "wet", themeOf("wet"), "front")
            val plain = Amsterdam.render(S, "wet", canal, null, "split", app.atlas.render.RenderOptions(grain = false))
            grainOff = listOf(plain.svg) + plain.objs!!
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            LiveEngine.forget()
            workBefore?.let { LiveEngine.workDir = it }
            work?.deleteRecursively()
            PyCompat.minor = minorBefore
            amsRawBefore?.let { Amsterdam.amsRaw = it }
            Regions.resetPlace()
        }

        private const val DEFS_END = "</defs><g clip-path=\"url(#vb)\">"

        /** A layer's head: the svg element, the shared definitions and the clip group's start. */
        fun headOf(svg: String): String = svg.substring(0, svg.indexOf(DEFS_END) + DEFS_END.length)

        /** A layer's definitions, after the frame's clip path. */
        fun defsOf(svg: String): String = svg.substring(svg.indexOf("</clipPath>") + "</clipPath>".length, svg.indexOf(DEFS_END))
    }

    @Test
    fun theFixedRendersAreChecked() {
        assertTrue("the Westerkerk (the app's bundled pack) is always checked", EngineVersion.FIXED.filter { it.region == "amsterdam" }.all { f -> cases.any { it.name == f.name } })
        if (missing.isNotEmpty()) println("ScreenLayersTest: packs not here for ${missing.joinToString()}")
    }

    @Test
    fun everyCaseDrawsItsScreenLayersByteForByte() {
        val report = ArrayList<String>()
        for (c in cases) {
            val cam = Amsterdam.cameraOf(c.cfg)
            val th: Dict = LinkedHashMap(c.theme)
            val f = ScreenLayers.of(cam, th, c.cfg, c.themeKey, c.layer)
            // the pieces as the render writes them, each with the definitions it adds, in the render's order
            val expectedDefs = ArrayList<String>()
            if (f.weather) expectedDefs += f.finDefs()
            expectedDefs += f.skyDef()
            val skyEls = if (f.weather && f.horizon > 4) f.skyLayer(cam, th, expectedDefs) else ""
            val hazeEls = f.hazeAndWash(expectedDefs)
            val wfxEls = f.wfx(expectedDefs)
            val fin = f.fin()

            val g = c.layers.first()
            val head = headOf(g)
            for (l in c.layers) assertTrue("${c.name}: every layer shares the head", l.startsWith(head))
            val defs = defsOf(g)
            var at = -1
            for (d in expectedDefs) {
                val i = defs.indexOf(d, at + 1)
                assertTrue("${c.name}: definition missing or out of order: ${d.take(120)}", i > at)
                assertEquals("${c.name}: definition drawn twice: ${d.take(80)}", -1, defs.indexOf(d, i + 1))
                at = i
            }
            // the sky opens the ground layer (none on the front layer)
            assertTrue("${c.name}: the ground layer opens with the sky", g.startsWith(head + f.skyRect() + skyEls))
            // wfx, the haze (and the wash) and fin close the last layer
            val last = c.layers.last()
            assertTrue("${c.name}: the last layer closes with wfx, the haze and fin", last.endsWith(wfxEls + hazeEls + fin + "</g></svg>"))
            report += "%-26s sky %-5s clouds %-5s sun %-5s band %-5s wash %-5s cloudshadows %-5s rain %-4s fin %-5s".format(
                c.name, f.skyRect().isNotEmpty(), f.clouds != null, f.sun != null, f.haze.band, f.wash, f.cloudShadows, f.rainAngle ?: "-", f.drawsFin,
            )
        }
        report.forEach { println(it) }
        // the cases cover every branch the screen layers have
        val frames = cases.map { c -> ScreenLayers.of(Amsterdam.cameraOf(c.cfg), LinkedHashMap(c.theme), c.cfg, c.themeKey, c.layer) }
        assertTrue("a sky layer", frames.any { it.clouds != null })
        assertTrue("the sun's glow", frames.any { it.sun != null })
        assertTrue("a night sky (the city's glow)", frames.any { it.clouds != null && it.night })
        assertTrue("the eye-level band", frames.any { it.haze.band })
        assertTrue("the 17 stops", frames.any { !it.haze.band })
        assertTrue("the wash", frames.any { it.wash })
        assertTrue("rain", frames.any { it.rainAngle != null })
        assertTrue("fin", frames.any { it.drawsFin })
        assertTrue("a moment without weather", frames.any { !it.weather })
        assertTrue("the front layer", frames.any { it.skyRect().isEmpty() })
    }

    @Test
    fun theGrainOffLeavesTheMaterialsGrainOutOfTodaysMap() {
        // the canal at wet: the ground's and the objects' mat groups; with the grain off neither, nor the paper's grain
        val on = cases.first { it.name == "canal-wet" }.layers
        assertTrue(on.any { "url(#mat)" in it })
        assertTrue(grainOff.isNotEmpty())
        assertTrue(grainOff.none { "url(#mat)" in it })
        assertTrue(grainOff.none { "url(#grain)" in it })
    }

    @Test
    fun theAppsLookSettingsLeaveOutTheGrainTheVignetteAndTheHaze() {
        // a moment with weather at a map pitch draws fin (the grain and the vignette) and the haze
        val cam = City.Camera(V3(0.0, 0.0, 0.0), 30.0, 60.0, 430.0, 36.0, 412.0, 915.0, cy = 440.0)
        val th: Dict = LinkedHashMap(Amsterdam.THEMES.getValue("golden"))
        th["wx"] = linkedMapOf("cloud" to 0.3, "wind" to 4.0)
        val cfg: Dict = linkedMapOf("W" to 412, "H" to 915, "target" to listOf(0, 0, 0), "pitch" to 60.0)
        fun frame(haze: Boolean = true, vignette: Boolean = true, grain: Boolean = true) =
            ScreenLayers.of(cam, th, cfg, "golden", drawHaze = haze, drawVignette = vignette, drawGrain = grain)
        val all = frame()
        assertTrue(all.drawsFin)
        // all on: the renders' own text, one rect after the other
        assertEquals(
            "<rect width=\"412\" height=\"915\" filter=\"url(#grain)\" opacity=\".09\" style=\"mix-blend-mode:overlay\"/><rect width=\"412\" height=\"915\" fill=\"url(#vig)\"/>",
            all.fin(),
        )
        val defs = ArrayList<String>()
        assertTrue(all.hazeAndWash(defs).contains("url(#haze)"))
        assertTrue(defs.any { it.contains("id=\"haze\"") })
        // each off alone leaves the others as the renders draw them
        assertFalse(frame(grain = false).fin().contains("url(#grain)"))
        assertTrue(frame(grain = false).fin().contains("url(#vig)"))
        assertFalse(frame(vignette = false).fin().contains("url(#vig)"))
        assertTrue(frame(vignette = false).fin().contains("url(#grain)"))
        val none = ArrayList<String>()
        val noHaze = frame(haze = false).hazeAndWash(none)
        assertFalse(noHaze.contains("url(#haze)"))
        assertTrue(none.none { it.contains("id=\"haze\"") })
        // golden's wash stays without the haze
        assertTrue(all.wash)
        assertTrue(noHaze.contains("url(#wash)"))
        assertEquals("", frame(grain = false, vignette = false).fin())
    }

    @Test
    fun todaysMapLeavesTheCloudShadowsOut() {
        // a moment with clouds at a map pitch: wfx's cloud shadows as the renders draw them, and none when left out
        // (today's map warps its frame under the finger, where shadows fixed to the screen would ride on it)
        val cam = City.Camera(V3(0.0, 0.0, 0.0), 30.0, 60.0, 430.0, 36.0, 412.0, 915.0, cy = 440.0)
        val th: Dict = LinkedHashMap(Amsterdam.THEMES.getValue("sunny"))
        th["wx"] = linkedMapOf("cloud" to 0.42, "wind" to 4.0)
        th["cloud_shadows"] = 0.42
        val cfg: Dict = linkedMapOf("W" to 412, "H" to 915, "target" to listOf(0, 0, 0), "pitch" to 60.0)
        val drawn = ScreenLayers.of(cam, th, cfg, "sunny")
        assertTrue(drawn.cloudShadows)
        val defs = ArrayList<String>()
        assertTrue(drawn.wfx(defs).contains("url(#cloudsh)"))
        assertTrue(defs.any { it.contains("id=\"cloudsh\"") })
        val left = ScreenLayers.of(cam, th, cfg, "sunny", drawCloudShadows = false)
        assertFalse(left.cloudShadows)
        val none = ArrayList<String>()
        assertFalse(left.wfx(none).contains("url(#cloudsh)"))
        assertTrue(none.none { it.contains("id=\"cloudsh\"") })
        // the rest of the screen layers as the renders draw them
        assertEquals(drawn.fin(), left.fin())
    }

    @Test
    fun theNumbersAreTheRendersRules() {
        // straight down from 430 m over flat ground: every row sees the ground, the horizon far above the frame
        val cam = City.Camera(V3(0.0, 0.0, 0.0), 30.0, 89.0, 430.0, 36.0, 412.0, 915.0, cy = 440.0)
        val h = ScreenLayers.haze(cam, 0.4, 380.0, 800.0, 0.0, eye = false, hy = cam.horizonY(), height = 915.0)
        assertFalse(h.band)
        assertEquals(0.0, h.y0, 0.0)
        assertEquals(915.0, h.y1, 0.0)
        assertEquals(17, h.offsets.size)
        for (k in 0 until 17) {
            assertEquals(k / 16.0, h.offsets[k], 0.0)
            val gd = cam.groundDepth(915.0 * k / 16 + 0.01, 0.0)!!
            val want = 0.4 * app.atlas.render.py.PyMath.pow(City.clamp((gd - 380.0) / 800.0), 1.3)
            assertEquals("stop $k", want, h.alphas[k], 0.0)
        }
        // the rows higher up see the ground further away, so their haze is no thinner; nearer than haze0 it is clear
        for (k in 1 until 17) assertTrue("stop $k", h.alphas[k] <= h.alphas[k - 1])
        val near = ScreenLayers.haze(cam, 0.4, 2000.0, 800.0, 0.0, eye = false, hy = cam.horizonY(), height = 915.0)
        assertTrue(near.alphas.all { it == 0.0 })
        // a theme without weather draws no sky layer, no wfx and no fin; golden draws the wash
        val th: Dict = LinkedHashMap(Amsterdam.THEMES.getValue("golden"))
        val cfg: Dict = linkedMapOf("W" to 412, "H" to 915, "target" to listOf(0, 0, 0), "pitch" to 89.0)
        val f = ScreenLayers.of(cam, th, cfg, "golden")
        assertNull(f.clouds); assertNull(f.sun); assertNull(f.rainAngle)
        assertFalse(f.cloudShadows); assertFalse(f.drawsFin); assertTrue(f.wash)
        assertEquals("", f.wfx(ArrayList()))
        // the sun behind the camera has no glow; ahead and above the horizon it has one
        val low = City.Camera(V3(0.0, 0.0, 0.0), 0.0, 2.0, 150.0, 64.0, 412.0, 915.0, cy = 420.0)
        assertNull(ScreenLayers.sunPoint(low, V3(0.0, 0.3, 0.95), 412.0, low.horizonY()))
        assertNotNull(ScreenLayers.sunPoint(low, V3(0.0, 0.2, -0.98), 412.0, low.horizonY()))
        // the rain's tile is the same at every moment
        assertEquals(ScreenLayers.rainStreaks(), ScreenLayers.rainStreaks())
        assertEquals(26, Regex("<path ").findAll(ScreenLayers.rainStreaks()).count())
    }
}
