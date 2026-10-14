package app.atlas.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LabelTest {
    private val id = TestKit.centre

    private fun label(text: String, x: Float, y: Float, priority: Float, kind: LabelKind = LabelKind.PLACE, line: FloatArray? = null) =
        LabelCandidate(text, kind, LabelStyle.SERIF_ITALIC, 14f, Colours.BLACK, Colours.WHITE, priority, 0f, 24f, x, y, 0f, line, 0f, text.hashCode().toLong() + priority.toLong())

    private fun place(scene: TileScene, camera: Camera, time: Long = 0L, placer: LabelPlacer = LabelPlacer(ApproximateMeasurer)) =
        placer.place(camera, listOf(DrawTile(scene)), time, FrameStyle())

    @Test
    fun theMoreImportantLabelWinsACollision() {
        val scene = Synthetic.scene(id, emptyList(), labels = listOf(label("Jordaan", 700f, 700f, 14f), label("Westerkerk", 702f, 701f, 30f)))
        val camera = Camera(scene.latLon(700f, 700f), 16.0, 0.0, 0.0, 800, 1400, 2f)
        val p = place(scene, camera)
        assertEquals(listOf("Jordaan"), p.labels.map { it.text })
        // Apart, both show.
        val apart = Synthetic.scene(id, emptyList(), labels = listOf(label("Jordaan", 700f, 700f, 14f), label("Westerkerk", 700f, 600f, 30f)))
        assertEquals(setOf("Jordaan", "Westerkerk"), place(apart, camera).labels.map { it.text }.toSet())
    }

    @Test
    fun placedLabelsNeverOverlap() {
        val labels = (0 until 200).map { i -> label("Label $i", 600f + (i % 20) * 9f, 600f + (i / 20) * 7f, i.toFloat()) }
        val scene = Synthetic.scene(id, emptyList(), labels = labels)
        val camera = Camera(scene.latLon(690f, 630f), 16.5, 0.0, 0.0, 800, 1400, 2f)
        val p = place(scene, camera)
        assertTrue(p.labels.size in 2..60)
        val boxes = p.labels.map { l ->
            val w = ApproximateMeasurer.width(l.text, l.style, l.size) / 2
            floatArrayOf(l.x - w, l.y - l.size / 2, l.x + w, l.y + l.size / 2)
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            assertTrue("${p.labels[i].text} overlaps ${p.labels[j].text}", a[2] <= b[0] || b[2] <= a[0] || a[3] <= b[1] || b[3] <= a[1])
        }
    }

    @Test
    fun streetNamesFollowTheirLineUpright() {
        // A street running west from x = 900 to x = 500 (drawn right to left): its name must still read left to right.
        val line = floatArrayOf(900f, 700f, 700f, 705f, 500f, 700f)
        val street = label("Prinsengracht", 700f, 700f, 20f, LabelKind.ROAD, line)
        val scene = Synthetic.scene(id, emptyList(), labels = listOf(street))
        val camera = Camera(scene.latLon(700f, 700f), 17.5, 0.0, 30.0, 800, 1400, 2f)
        val p = place(scene, camera)
        val l = p.labels.single()
        val g = assertNotNullGlyphs(l)
        for (k in l.text.indices) assertTrue(abs(g[3 * k + 2]) <= 90f)
        // Glyphs run left to right along the street.
        for (k in 1 until l.text.length) assertTrue(g[3 * k] > g[3 * (k - 1)])
        // And sit on the line: within a pixel or two of its projection.
        val pts = (0 until 3).map { camera.project(scene.latLon(line[2 * it], line[2 * it + 1]))!! }
        for (k in l.text.indices) {
            var d = Double.MAX_VALUE
            for (i in 0 until 2) {
                d = minOf(d, Rings.segmentDistance(g[3 * k].toDouble(), g[3 * k + 1].toDouble(),
                    pts[i].x.toDouble(), pts[i].y.toDouble(), pts[i + 1].x.toDouble(), pts[i + 1].y.toDouble()))
            }
            assertTrue("glyph $k is $d px off the line", d < 1.5)
        }
    }

    private fun assertNotNullGlyphs(l: PlacedLabel): FloatArray {
        assertNotNull(l.glyphs)
        return l.glyphs!!
    }

    @Test
    fun labelsFadeIn() {
        val scene = Synthetic.scene(id, emptyList(), labels = listOf(label("Jordaan", 700f, 700f, 14f)))
        val camera = Camera(scene.latLon(700f, 700f), 16.0, 0.0, 0.0, 800, 1400, 2f)
        val placer = LabelPlacer(ApproximateMeasurer)
        // The first frame shows everything at once.
        assertEquals(1f, place(scene, camera, 0L, placer).labels.single().alpha, 0f)
        // A label that appears later fades in.
        val two = Synthetic.scene(id, emptyList(), labels = listOf(label("Jordaan", 700f, 700f, 14f), label("Westerkerk", 700f, 500f, 30f)))
        val a = place(two, camera, 100L, placer).labels.first { it.text == "Westerkerk" }.alpha
        val b = place(two, camera, 200L, placer).labels.first { it.text == "Westerkerk" }.alpha
        assertTrue(a in 0.1f..0.9f)
        assertTrue(b > a)
    }

    @Test
    fun aLabelInTheWayOfAMoreImportantOneFadesOut() {
        val camera = Camera(Synthetic.scene(id, emptyList()).latLon(700f, 700f), 16.0, 0.0, 0.0, 800, 1400, 2f)
        val placer = LabelPlacer(ApproximateMeasurer)
        val street = label("Westerkerk", 702f, 701f, 30f)
        val poi = PoiCandidate(700f, 760f, "cafe", "cafe", "Café", 1, null, 11)
        val before = Synthetic.scene(id, emptyList(), labels = listOf(street), pois = listOf(poi))
        place(before, camera, 0L, placer)
        // A more important label arrives on top of both.
        val after = Synthetic.scene(id, emptyList(), labels = listOf(street, label("Jordaan", 700f, 700f, 14f), label("Noordermarkt", 700f, 760f, 15f)), pois = listOf(poi))
        val alphas = ArrayList<Float>()
        val poiAlphas = ArrayList<Float>()
        var t = 0L
        for (k in 1..6) {
            t += 50
            val p = place(after, camera, t, placer)
            assertTrue(p.labels.any { it.text == "Jordaan" })
            p.labels.firstOrNull { it.text == "Westerkerk" }?.let { alphas += it.alpha }
            // The fading symbol cannot be tapped.
            for (i in p.pois.indices) assertTrue(p.poiBoxes[4 * i].isNaN())
            p.pois.firstOrNull()?.let { poiAlphas += it.alpha }
        }
        // Gone after the fade (220 ms), falling all the way.
        for (a in listOf(alphas, poiAlphas)) {
            assertTrue("$a", a.size in 3..5)
            for (i in 1 until a.size) assertTrue(a[i] < a[i - 1])
        }
    }

    @Test
    fun aStreetNameInTheWayFadesOutAlongItsStreet() {
        val line = floatArrayOf(500f, 700f, 900f, 700f)
        val street = label("Rozengracht", 700f, 700f, 20f, LabelKind.ROAD, line)
        val camera = Camera(Synthetic.scene(id, emptyList()).latLon(700f, 700f), 17.0, 0.0, 0.0, 800, 1400, 2f)
        val placer = LabelPlacer(ApproximateMeasurer)
        assertEquals(1f, place(Synthetic.scene(id, emptyList(), labels = listOf(street)), camera, 0L, placer).labels.single().alpha, 0f)
        val crowded = Synthetic.scene(id, emptyList(), labels = listOf(street, label("Westermarkt", 700f, 700f, 10f)))
        val p = place(crowded, camera, 60L, placer)
        val fading = p.labels.single { it.text == "Rozengracht" }
        assertTrue(fading.alpha in 0.1f..0.9f)
        assertNotNullGlyphs(fading)
        assertTrue(place(crowded, camera, 400L, placer).labels.none { it.text == "Rozengracht" })
    }

    @Test
    fun aStreetInPiecesIsNamedOnce() {
        // A tile hands a street over as two pieces with one key: the second piece, too close to the first, must neither
        // show nor linger as a fading copy.
        val a = label("Westerstraat", 700f, 700f, 20f, LabelKind.ROAD, floatArrayOf(450f, 700f, 950f, 700f))
        val b = LabelCandidate(a.text, a.kind, a.style, a.size, a.colour, a.halo, a.priority, a.minZoom, a.maxZoom, 700f, 712f, 0f,
            floatArrayOf(450f, 712f, 950f, 712f), 0f, a.key)
        val scene = Synthetic.scene(id, emptyList(), labels = listOf(a, b))
        val camera = Camera(scene.latLon(700f, 700f), 17.0, 0.0, 0.0, 800, 1400, 2f)
        val placer = LabelPlacer(ApproximateMeasurer)
        for (k in 0..5) {
            val shown = place(scene, camera, k * 16L, placer).labels.filter { it.text == "Westerstraat" }
            assertEquals("frame $k: ${shown.map { it.alpha }}", 1, shown.size)
            assertEquals(1f, shown.single().alpha, 0f)
        }
    }

    @Test
    fun streetNamesKeepTheirFadeWhileTheViewMoves() {
        // A long street, already shown: zooming and tilting a little must not fade it in again.
        val line = floatArrayOf(300f, 700f, 1100f, 700f)
        val scene = Synthetic.scene(id, emptyList(), labels = listOf(label("Rozengracht", 700f, 700f, 20f, LabelKind.ROAD, line)))
        val placer = LabelPlacer(ApproximateMeasurer)
        val camera = Camera(scene.latLon(700f, 700f), 17.0, 0.0, 20.0, 800, 1400, 2f)
        assertEquals(1f, place(scene, camera, 0L, placer).labels.first().alpha, 0f)
        var t = 0L
        for (k in 1..12) {
            t += 16
            val moved = camera.copy(zoom = 17.0 + k * 0.05, pitch = 20.0 + k)
            val shown = place(scene, moved, t, placer).labels
            assertTrue("frame $k: ${shown.map { it.alpha }}", shown.isNotEmpty() && shown.all { it.alpha == 1f })
        }
    }

    @Test
    fun namesAlongLinesKeepTheirFadeAcrossZoomLevels() {
        // A street and a canal across the source tile, read by a zoom 17 scene and again by its four zoom 18 children:
        // zooming across the level (either way) must not fade their names in again.
        val source = TestKit.centre
        val vt = MiniMvt()
            .layer("transportation_name") { line(intArrayOf(-64, 2100, 4160, 2100), props = mapOf("class" to "minor", "name" to "Rozengracht")) }
            .layer("waterway") { line(intArrayOf(-64, 2400, 4160, 2400), props = mapOf("class" to "canal", "name" to "Lijnbaansgracht")) }
            .build()
        val z17 = app.atlas.geo.TileId(17, source.x * 8 + 3, source.y * 8 + 4)
        val parent = listOf(SceneBuilder.build(vt, z17, Look.DAY, source = source))
        val kids = z17.children().map { SceneBuilder.build(vt, it, Look.DAY, source = source) }
        val target = TileScene0.corner(z17, 0.5, 0.5)
        val placer = LabelPlacer(ApproximateMeasurer)
        fun names(zoom: Double, scenes: List<TileScene>, t: Long) =
            placer.place(Camera(target, zoom, 0.0, 0.0, 824, 1830, 2f), scenes.map { DrawTile(it) }, t, FrameStyle()).labels
        val both = setOf("Rozengracht", "Lijnbaansgracht")
        assertEquals(both, names(17.97, parent, 0L).map { it.text }.toSet())
        var t = 0L
        for ((zoom, scenes) in listOf(18.0 to kids, 17.97 to parent, 18.02 to kids)) {
            t += 16
            val shown = names(zoom, scenes, t)
            assertEquals("at $zoom", both, shown.map { it.text }.toSet())
            assertTrue("at $zoom: ${shown.map { it.text to it.alpha }}", shown.all { it.alpha == 1f })
        }
    }

    @Test
    fun aNameShownTwiceFadesInAtOneSpeed() {
        // One street name in two places far apart (two pieces of a long street): both appear together, at one pace.
        val a = label("Westerstraat", 700f, 400f, 20f)
        val b = LabelCandidate(a.text, a.kind, a.style, a.size, a.colour, a.halo, a.priority, a.minZoom, a.maxZoom, 700f, 1000f, 0f, null, 0f, a.key)
        val camera = Camera(Synthetic.scene(id, emptyList()).latLon(700f, 700f), 16.0, 0.0, 0.0, 800, 1400, 2f)
        val placer = LabelPlacer(ApproximateMeasurer)
        place(Synthetic.scene(id, emptyList()), camera, 0L, placer)
        val shown = place(Synthetic.scene(id, emptyList(), labels = listOf(a, b)), camera, 22L, placer).labels
        assertEquals(2, shown.size)
        for (l in shown) assertEquals(0.1f, l.alpha, 1e-4f)
    }

    @Test
    fun poisKeepClearOfLabels() {
        val poi = PoiCandidate(700f, 700f, "cafe", "cafe", "Café", 1, null, 11)
        val scene = Synthetic.scene(id, emptyList(), labels = listOf(label("Jordaan", 700f, 700f, 14f)), pois = listOf(poi, PoiCandidate(700f, 400f, "cafe", "cafe", "Other", 2, null, 21)))
        val camera = Camera(scene.latLon(700f, 600f), 16.0, 0.0, 0.0, 800, 1400, 2f)
        val p = place(scene, camera)
        // The place name came first; the café under it gives way, the other one shows.
        assertEquals(listOf(21L), p.pois.map { it.poi.featureId })
        assertEquals(4, p.poiBoxes.size)
        val r = Renderer()
        val f = r.frame(camera, listOf(scene), 0L)
        val hit = r.hitTest(f, f.pois.single().x, f.pois.single().y)
        assertTrue(hit is Hit.Poi)
    }
}
