package app.atlas.mapview.vk

import app.atlas.geo.LatLon
import app.atlas.mapcore.CameraState
import app.atlas.mapcore.FrameRecords
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapview.VkFactsLines
import app.atlas.render.live.RegionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * VK bench's plan and arithmetic (plan-vector K1): the paths (the stand-in's, and the app's map's at its real views), the
 * matrix, the colour stage, the statistics, the report lines and the report as recorded while the bench runs.
 */
class VkBenchTest {
    @Test
    fun theSurfaceViewHostWritesItsPillsFrostedAndThePlainTintAndTheTextureViewClear() {
        // VK bench's pills as its screen places them (pillBoxes, the chrome's shape of each, frosted on the SurfaceView)
        // and as its camera source writes them into each frame's camera state: frosted keep-outs, so the core draws
        // their glass, only on the SurfaceView host and not for the pill check's plain tint.
        val d = 2.625
        for ((host, plain, frosted) in listOf(
            Triple(VkHost.SURFACE, false, true),
            Triple(VkHost.SURFACE, true, false),
            Triple(VkHost.TEXTURE, false, false),
        )) {
            val shapes = ChromeShapes()
            val source = BenchSource(CameraState(), shapes)
            source.running = true
            source.widthPx = 1080
            source.heightPx = 1400
            source.density = d
            shapes.surfaceAt(0f, 233f)
            for (b in pillBoxes(1080 / d.toFloat(), 1400 / d.toFloat(), 0f)) {
                val slot = shapes.add()
                shapes.place(
                    slot, b[0] * d.toFloat(), 233f + b[1] * d.toFloat(), b[2] * d.toFloat(), 233f + b[3] * d.toFloat(),
                    26f * d.toFloat(), 20f * d.toFloat(), 1.8f, host == VkHost.SURFACE, 0L,
                )
            }
            shapes.coreDraws = coreDrawsGlass(host, plain)
            val out = CameraWriter.allocate()
            assertTrue(source.camera(16_666_667L, out))
            assertEquals(2, out.getInt(CameraWriter.KEEP_OUT_COUNT_AT))
            for (i in 0 until 2) {
                val o = CameraWriter.KEEP_OUT_AT + i * CameraWriter.KEEP_OUT_SIZE
                assertEquals("$host plain=$plain pill $i", if (frosted) 1 else 0, out.get(o + 23).toInt() and 0xFF)
                // In dp from the surface's top left, as the core places its glass: the top pill 40 dp down.
                if (i == 0) assertEquals(40f, out.getFloat(o + 4), 1e-3f)
            }
        }
    }

    @Test
    fun thePathsMoveTheCameraAsScripted() {
        val c = VkBench.Camera()
        VkBench.cameraAt(VkBench.Path.ORBIT, VkBench.View.BOARD, 7.5, 30.0, c)
        assertEquals(18.0 + 90.0, c.bearing, 1e-9)
        assertEquals(46.0, c.pitch, 0.0)
        VkBench.cameraAt(VkBench.Path.TILT, VkBench.View.BOARD, 15.0, 30.0, c)
        assertEquals(70.0, c.pitch, 1e-9) // half way: the steepest pitch
        VkBench.cameraAt(VkBench.Path.TILT, VkBench.View.BOARD, 0.0, 30.0, c)
        assertEquals(20.0, c.pitch, 1e-9)
        VkBench.cameraAt(VkBench.Path.PINCH, VkBench.View.BOARD, 15.0, 30.0, c)
        assertEquals(6000.0, c.distance, 1e-6)
        VkBench.cameraAt(VkBench.Path.PINCH, VkBench.View.BOARD, 0.0, 30.0, c)
        assertEquals(600.0, c.distance, 1e-9)
        VkBench.cameraAt(VkBench.Path.PAN, VkBench.View.BOARD, 7.5, 30.0, c)
        assertTrue(c.latitude != VkBench.ORIGIN_LATITUDE && c.longitude != VkBench.ORIGIN_LONGITUDE)
        VkBench.cameraAt(VkBench.Path.STILL, VkBench.View.STEEP, 0.0, 30.0, c)
        assertEquals(70.0, c.pitch, 0.0)
        assertEquals(2300.0, c.distance, 0.0)
    }

    @Test
    fun theMapsViewsAreTheHomeAndTheMainBoardAsTheEnginesReferencesFrameThem() {
        // The bundled canal ring's `home` view (its manifest's config.views), in its region's frame.
        val region = RegionFrame.region(LatLon(52.372, 4.891))
        val board = PackList.ViewCamera(-177.0, -521.0, 52.0, 44.0, 2300.0, 440.0)
        val views = VkBench.mapViews(RegionFrame.living(), region, board)
        assertEquals(listOf("home", "board", "pitch70", "world72"), views.map { it.label })
        val (home, main, steep, far) = views
        // The home: 10 m west and 30 m north of the Westerkerk, bearing 52°, map pitch 52°, 430 m (RenderTool's `home`).
        assertEquals(RegionFrame.living().lat(-10.0, -30.0), home.latitude, 0.0)
        assertEquals(RegionFrame.living().lon(-10.0, -30.0), home.longitude, 0.0)
        assertTrue(home.latitude > 52.3746 && home.longitude < 4.8840)
        assertEquals(listOf(52.0, 52.0, 430.0), listOf(home.bearing, home.pitch, home.distance))
        assertEquals(listOf(VkBench.Path.STILL, VkBench.Path.ORBIT, VkBench.Path.PAN, VkBench.Path.PINCH), home.paths)
        // The Main board: as EngineVersion.knotView stands on grachten_home (the renders' pitch from the horizon).
        assertEquals(region.lat(-177.0, -521.0), main.latitude, 0.0)
        assertEquals(region.lon(-177.0, -521.0), main.longitude, 0.0)
        assertEquals(listOf(52.0, 46.0, 2300.0), listOf(main.bearing, main.pitch, main.distance))
        assertEquals(listOf(main.latitude, main.longitude, main.bearing, 70.0, 2300.0), listOf(steep.latitude, steep.longitude, steep.bearing, steep.pitch, steep.distance))
        // The governor's view: the board's target from 25 km at 72°, turning.
        assertEquals(listOf(main.latitude, main.longitude, main.bearing, 72.0, 25000.0), listOf(far.latitude, far.longitude, far.bearing, far.pitch, far.distance))
        assertEquals(listOf(VkBench.Path.ORBIT), far.paths)
        // A pack missing leaves its views out.
        assertEquals(listOf("home"), VkBench.mapViews(RegionFrame.living(), null, null).map { it.label })
        assertEquals(listOf("board", "pitch70", "world72"), VkBench.mapViews(null, region, board).map { it.label })
        assertTrue(VkBench.mapViews(null, region, null).isEmpty())
        // The constants the map stage stands its views by over a phone's own packs are the canal ring's.
        assertEquals(region, RegionFrame.region(VkBench.BOARD_ORIGIN))
        assertEquals(listOf(board.x, board.z, board.yaw, board.pitch, board.distance, board.cy), VkBench.BOARD_CAMERA.let { listOf(it.x, it.z, it.yaw, it.pitch, it.distance, it.cy) })
        // Over the canal ring the lines are CI's as before: no pack named.
        assertEquals(listOf("home", "board"), views.take(2).map { it.where })
        assertEquals("map home still", VkBench.mapLabel(home, VkBench.Path.STILL))
        assertEquals("map world72 orbit", VkBench.mapLabel(far, VkBench.Path.ORBIT))
        // The records are compared where the most walls are kept: the home's pinch and pitch70's turn.
        assertEquals(listOf(VkBench.Path.PINCH, null, VkBench.Path.ORBIT, null), views.map { it.compared })
        // The full and quick benches compare them at both; CI's at the first alone, the home's pinch where it is drawn.
        for (length in listOf(VkBench.Length.FULL, VkBench.Length.QUICK)) {
            assertEquals(listOf(true, false, true, false), views.map { VkBench.recordsCompared(it, views, length) })
        }
        assertEquals(listOf(true, false, false, false), views.map { VkBench.recordsCompared(it, views, VkBench.Length.CI) })
        val boards = VkBench.mapViews(null, region, board)
        assertEquals(listOf(false, true, false), boards.map { VkBench.recordsCompared(it, boards, VkBench.Length.CI) })
    }

    @Test
    fun theRecordsAreComparedInSlicesTwoByTwoEndingOnWithALineEachThatCiReadsNoneOf() {
        // an even number of slices of about half a second, at least two: CI's 1.5 s path two, the quick bench's 12
        assertEquals(listOf(2, 2, 12, 60), listOf(0.0, 1.5, 6.0, 30.0).map { VkBench.recordsSlices(it) })
        // on, off, off, on, … counted from the last, which is on; off then on in CI's two
        assertEquals(listOf(true, false, false, true, true, false, false, true), (0 until 8).map { VkBench.recordsOn(it, 8) })
        assertEquals(listOf(false, true, true, false, false, true), (0 until 6).map { VkBench.recordsOn(it, 6) })
        assertEquals(listOf(false, true), (0 until 2).map { VkBench.recordsOn(it, 2) })
        for (n in listOf(2, 6, 12, 60)) {
            assertEquals(n / 2, (0 until n).count { VkBench.recordsOn(it, n) })
            assertTrue(VkBench.recordsOn(n - 1, n))
        }
        // where the slices are a multiple of four, on and off meet the path's views alike: their mean slice the same
        for (n in listOf(12, 60)) assertEquals((0 until n).filter { VkBench.recordsOn(it, n) }.sum(), (0 until n).filter { !VkBench.recordsOn(it, n) }.sum())
        // a frame is its slice's once the records were set and until the next slice's were asked for; none between
        val slices = listOf(VkBench.RecordsSlice(true, 100), VkBench.RecordsSlice(false, 300))
        slices[0].untilNs = 250
        slices[1].untilNs = 500
        assertEquals(listOf(null, slices[0], null, null, slices[1], null), listOf(100L, 101L, 260L, 300L, 499L, 500L).map { VkBench.recordsSlice(slices, it) })
        // each side its own line, naming whether the records were on, over a phone's own pack too
        val home = VkBench.mapViews(RegionFrame.living(), null, null).single()
        assertEquals("map home pinch records on", VkBench.recordsLabel(home, VkBench.Path.PINCH, true))
        val over = VkBench.MapView("home", 52.0, 4.9, 52.0, 52.0, 430.0, home.paths, "window/14/8414/5384", VkBench.Path.PINCH)
        assertEquals("map home pinch records off over window/14/8414/5384", VkBench.recordsLabel(over, VkBench.Path.PINCH, false))
        assertEquals("vkbench map home pinch records: not compared (no map)", VkBench.recordsSkipped(home, VkBench.Path.PINCH, "no map"))
        // the terrain's relief is compared in the same slices, its lines named alike (ENGINE.md part 6, step 2)
        assertEquals("map home pinch terrain on", VkBench.terrainLabel(home, VkBench.Path.PINCH, true))
        assertEquals("map home pinch terrain off over window/14/8414/5384", VkBench.terrainLabel(over, VkBench.Path.PINCH, false))
        assertEquals("vkbench map home pinch terrain: not compared (no map)", VkBench.terrainSkipped(home, VkBench.Path.PINCH, "no map"))
        assertEquals(
            "gpu before the main pass 2.00 ms, in it 7.00, after it 1.00",
            VkBench.gpuParts(doubleArrayOf(3.0, 2.0, 1.0), doubleArrayOf(7.0, 9.0, 5.0), doubleArrayOf(1.0, 1.0, 2.0)),
        )
        assertEquals("gpu before the main pass — ms, in it —, after it —", VkBench.gpuParts(DoubleArray(0), DoubleArray(0), DoubleArray(0)))
        // CI's checks of the map's paths (smoke.sh: a line for each, the governor's bias 0 on each but world72's) read
        // none of the comparison's lines, and the paths' own lines are as they were
        val s = VkBench.Summary(10, 16.7, 18.0, 20.0, 0, "display-timing", 9.0, 11.0, 6.0, 8.0, 1.0, 2.0, Double.NaN, Double.NaN, 40, 900)
        val lines = listOf(home, over).flatMap { v ->
            listOf(true, false).flatMap { on ->
                listOf(VkBench.recordsLabel(v, VkBench.Path.PINCH, on), VkBench.terrainLabel(v, VkBench.Path.PINCH, on))
                    .map { VkBench.line(it, s, 100.0, 50.0, VkBench.gpuParts(DoubleArray(0), DoubleArray(0), DoubleArray(0))) }
            }
        } + VkBench.recordsSkipped(over, VkBench.Path.PINCH, "no map") + VkBench.terrainSkipped(over, VkBench.Path.PINCH, "no map")
        // each a line of vkbench.txt (smoke.sh keeps every `vkbench map` line), with its side named
        assertEquals(10, lines.count { it.startsWith("vkbench map home pinch ") })
        assertTrue(lines.any { it.startsWith("vkbench map home pinch terrain on: p50 ") })
        assertTrue(lines.any { it.startsWith("vkbench map home pinch terrain off over window/14/8414/5384: p50 ") })
        val path = Regex("^vkbench map [a-z0-9]+ [a-z]+: p50 ")
        for (p in listOf("home still", "home orbit", "home pan", "home pinch", "board orbit", "pitch70 orbit", "world72 orbit")) {
            assertTrue(lines.none { it.startsWith("vkbench map $p: p50 ") })
        }
        assertTrue(lines.none { path.containsMatchIn(it) || it.startsWith("vkbench board ") || it.startsWith("vkbench pitch70 ") })
        assertEquals("vkbench map home pinch: p50 16.70", VkBench.line(VkBench.mapLabel(home, VkBench.Path.PINCH), s, 100.0, 50.0).take(33))
    }

    /** Window [z]/[x]/[y] in [dir] as PackList reads one: D1's header and a format-3 manifest with its tile's box, no views. */
    private fun window(dir: File, z: Int, x: Int, y: Int) {
        val n = (1 shl z).toDouble()
        val north = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
        val south = Math.toDegrees(atan(sinh(PI * (1 - 2 * (y + 1) / n))))
        val manifest = """{"format":"atlas-pack","version":3,"layout":"render-tiles","payload":1,"id":"window","profile":"region",""" +
            """"bbox":{"west":${x / n * 360 - 180},"south":$south,"east":${(x + 1) / n * 360 - 180},"north":$north},""" +
            """"origin":{"lat":52.3667,"lon":4.8516},"tz":"Europe/Amsterdam","chunks":1,"config":{"views":{}}}"""
        val bytes = manifest.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ATLASPCK".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 3, 0, 0))
        out.write(ByteBuffer.allocate(8).putInt(bytes.size).putInt(0).array())
        out.write(bytes)
        out.write(ByteArray(48) { it.toByte() })
        File(dir, "$z-$x-$y.atlas").writeBytes(out.toByteArray())
    }

    /** The L14 window holding [lat], [lon]: its x and y. */
    private fun tileOf(lat: Double, lon: Double): Pair<Int, Int> {
        val n = 1 shl 14
        val r = Math.toRadians(lat)
        return floor((lon + 180) / 360 * n).toInt() to floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt()
    }

    @Test
    fun withoutTheCanalRingTheMapIsDrawnOverThePhonesOwnPacksAtItsTargets() {
        // An alpha on a phone: nothing in /data/local/tmp/atlas, the windows the phone took about the Westerkerk (a block of
        // nine, and their frame cell's L13 tile).
        val dir = Files.createTempDirectory("vkbench").toFile()
        try {
            val living = RegionFrame.living()
            val (x, y) = tileOf(living.lat(-10.0, -30.0), living.lon(-10.0, -30.0))
            for (dx in -1..1) for (dy in -1..1) window(dir, 14, x + dx, y + dy)
            window(dir, 13, x / 2, y / 2)
            val packs = PackList(windows = { dir }).also { it.load() }
            val views = VkBench.installedViews(packs)
            // the same views and paths at the same targets as over the canal ring
            val canal = VkBench.mapViews(living, RegionFrame.region(VkBench.BOARD_ORIGIN), VkBench.BOARD_CAMERA)
            val shape = { v: VkBench.MapView -> listOf(v.label, v.latitude, v.longitude, v.bearing, v.pitch, v.distance, v.paths, v.compared) }
            assertEquals(canal.map(shape), views.map(shape))
            // each over the window the app names at its target, its L14 one
            assertEquals(views.map { v -> tileOf(v.latitude, v.longitude).let { (tx, ty) -> "window/14/$tx/$ty" } }, views.map { it.over })
            assertEquals("window/14/$x/$y", views[0].over)
            // the lines name it, so none is taken for CI's canal ring's
            assertEquals("home over window/14/$x/$y", views[0].where)
            assertEquals("map home still over window/14/$x/$y", VkBench.mapLabel(views[0], VkBench.Path.STILL))
            assertEquals(
                "vkbench map: no canal ring (CI puts it in /data/local/tmp/atlas), so over the phone's own 10 packs, at the canal ring's targets",
                VkBench.installedLine(packs, views),
            )
            // the camera names the pack as the app does (MapController: PackList.named): past the block's east edge, where
            // no pack holds the target, the nearest, drawn with its frame cell's pack (the core's own cover), not none
            val east = packs.packs.first { it.id == "window/14/${x + 1}/$y" }.box
            val (lat, lon) = east.center.lat to east.east + 0.01
            val source = BenchSource(CameraState(), ChromeShapes())
            source.running = true
            source.widthPx = 1080
            source.heightPx = 2400
            source.density = 2.625
            source.packs = packs
            source.mapView = VkBench.MapView("home", lat, lon, 52.0, 52.0, 430.0, listOf(VkBench.Path.STILL))
            val out = CameraWriter.allocate()
            assertTrue(source.camera(16_666_667L, out))
            assertEquals(null, packs.choose(lat, lon))
            assertEquals(packs.named(lat, lon)!!.number, out.getLong(CameraWriter.PACK_ID_AT))
            assertTrue(out.getLong(CameraWriter.PACK_ID_AT) != 0L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun whereNoPackOfThePhonesHoldsTheHomeTheViewsStandAboutItsDensestPlace() {
        // A phone that took windows about Utrecht alone: a lone one, a row of three, and the row's frame cell's L13 tile.
        val dir = Files.createTempDirectory("vkbench").toFile()
        try {
            window(dir, 13, 4212, 2697)
            window(dir, 14, 8400, 5390)
            for (x in 8424..8426) window(dir, 14, x, 5394)
            val packs = PackList(windows = { dir }).also { it.load() }
            val views = VkBench.installedViews(packs)
            // all four about the middle of the row's middle window, the place with the most places within a tile of it
            val middle = packs.packs.first { it.id == "window/14/8425/5394" }.box.center
            assertEquals(listOf("home", "board", "pitch70", "world72"), views.map { it.label })
            for (v in views) assertEquals(listOf(middle.lat, middle.lon, "window/14/8425/5394"), listOf(v.latitude, v.longitude, v.over))
            // at the canal ring's bearings, pitches and distances, with its paths
            val canal = VkBench.mapViews(RegionFrame.living(), RegionFrame.region(VkBench.BOARD_ORIGIN), VkBench.BOARD_CAMERA)
            val camera = { v: VkBench.MapView -> listOf(v.bearing, v.pitch, v.distance, v.paths) }
            assertEquals(canal.map(camera), views.map(camera))
            assertEquals(
                "vkbench map: no canal ring (CI puts it in /data/local/tmp/atlas), so over the phone's own 5 packs, about window/14/8425/5394",
                VkBench.installedLine(packs, views),
            )
            // none where the phone lists no pack
            assertTrue(VkBench.installedViews(PackList(windows = { File(dir, "none") }).also { it.load() }).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aMapPathsLineCarriesTheGovernorsBias() {
        val stats = "vk session: on; vk tiles 19 resident 144.1 of 256 build 88.9 waiting 0 planned 144.1 coarser 0 holes 0 built 49 failed 0 " +
            "governor bias 0.30 most 1.50 triangles 13.61 of 16.00 world 6 drawn finest 11 wanted 0 bare 0"
        assertEquals("bias 0.30 most 1.50 triangles 13.61M of 16.00M", VkBench.governor(stats))
        // a core whose stats hold none (the map off): nothing
        assertEquals("", VkBench.governor("vk session: on"))
    }

    @Test
    fun theMapsPathsMoveAboutItsView() {
        val home = VkBench.mapViews(RegionFrame.living(), null, null).single()
        val c = VkBench.Camera()
        VkBench.mapCameraAt(VkBench.Path.ORBIT, home, 22.5, 30.0, c)
        assertEquals((52.0 + 270.0) % 360.0, c.bearing, 1e-9)
        assertEquals(listOf(home.latitude, home.longitude, 52.0, 430.0), listOf(c.latitude, c.longitude, c.pitch, c.distance))
        // the pinch: out to 8 km half way, back at the end, evenly in the logarithm
        VkBench.mapCameraAt(VkBench.Path.PINCH, home, 15.0, 30.0, c)
        assertEquals(VkBench.MAP_FAR, c.distance, 1e-6)
        VkBench.mapCameraAt(VkBench.Path.PINCH, home, 7.5, 30.0, c)
        assertEquals(kotlin.math.sqrt(430.0 * VkBench.MAP_FAR), c.distance, 1e-6)
        VkBench.mapCameraAt(VkBench.Path.PINCH, home, 30.0, 30.0, c)
        assertEquals(430.0, c.distance, 1e-9)
        // the pan: half the view's distance along the view at a quarter of the way
        VkBench.mapCameraAt(VkBench.Path.PAN, home, 7.5, 30.0, c)
        val north = (c.latitude - home.latitude) * 110574.0
        val east = (c.longitude - home.longitude) * 111320.0 * kotlin.math.cos(Math.toRadians(home.latitude))
        assertEquals(215.0, kotlin.math.hypot(north, east), 1e-6)
        // still: a sway of at most 2°, the bearing within [0, 360)
        VkBench.mapCameraAt(VkBench.Path.STILL, home, 7.5, 30.0, c)
        assertEquals(54.0, c.bearing, 1e-9)
        val back = VkBench.MapView("t", 52.0, 4.0, 1.0, 40.0, 100.0, emptyList())
        VkBench.mapCameraAt(VkBench.Path.STILL, back, 22.5, 30.0, c)
        assertEquals(359.0, c.bearing, 1e-9)
    }

    @Test
    fun theMapsLinesSayWhatItDrewAndHowSoonItWasComplete() {
        assertEquals(
            "vkbench map: the packs (grachten opened), the sunny moment, haze on, vignette off",
            VkBench.mapHead("grachten opened", "sunny", haze = true, vignette = false),
        )
        val frames = "45 frames, the longest 26174.44 ms"
        assertEquals(
            "vkbench map first picture: complete after 59971.92 ms (at the home; $frames)",
            VkBench.mapPictureLine("home", 59.97192, first = true, frames = frames),
        )
        val stalled = VkBench.mapPictureLine("home", 82.4, first = true, frames = frames, stalled = "43 frames in 60 s without the picture growing")
        assertEquals(
            "vkbench map first picture: not complete, the map stalled after 82400.00 ms: 43 frames in 60 s without the picture " +
                "growing (at the home; $frames)",
            stalled,
        )
        // smoke.sh passes the first picture by its 'complete after' line alone
        assertFalse(stalled.startsWith("vkbench map first picture: complete after "))
        assertEquals(
            "vkbench map board picture: complete after 412.50 ms (at the board; 0 frames, the longest 0.00 ms)",
            VkBench.mapPictureLine("board", 0.4125, first = false, frames = VkBench.PictureWait(0L, 0L, 0L).said()),
        )
        // the core's health line: the frame drawn now, or none
        assertEquals(27404L, VkBench.drawingMs("vk drawing 27404 ms failed 0 lost 0 undrawn 0\nvk failed: x"))
        assertEquals(null, VkBench.drawingMs("vk drawing no failed 0 lost 0 undrawn 2 (no window)"))
        assertEquals(null, VkBench.drawingMs("vk no core"))
    }

    /** A frame of the map's as the core draws it: from [startS] to [endS] seconds into the wait, with [draws]. */
    private data class Drawn(val startS: Double, val endS: Double, val draws: Int, val ours: Boolean = true)

    /** Frames drawn one after another from [fromS]: each (seconds, draws). */
    private fun backToBack(fromS: Double, frames: List<Pair<Double, Int>>): List<Drawn> {
        var t = fromS
        return frames.map { (s, d) -> Drawn(t, t + s, d).also { t += s } }
    }

    /**
     * [loop] through a [VkBench.PictureWait] as VK bench's map stage polls it, every 50 ms from the wait's start up to
     * [untilS]: the frames presented since it began and how long the one drawn now has run (the core's health), then the
     * records the core has handed on, each once [heldBack] more frames were drawn (8 where the device does not say when a
     * frame was presented, as on CI's emulator), the view's own (begun in the wait) to the wait. Over [away] (seconds in)
     * the app is away: VK bench's clock stands still and no stall is judged, and the wait is told when it comes back. The
     * wait, and its first stall (seconds in, why) or null.
     */
    private fun waited(
        loop: List<Drawn>,
        untilS: Double,
        heldBack: Int = 8,
        away: ClosedFloatingPointRange<Double>? = null,
    ): Pair<VkBench.PictureWait, Pair<Double, String>?> {
        val records = loop.sortedBy { it.endS }
        val presentedBefore = records.count { it.endS <= 0.0 }
        val wait = VkBench.PictureWait(0L, 0L, presentedBefore.toLong())
        var fed = 0
        var awayNs = 0L
        var leftNs = -1L
        var t = 0L
        while (t <= (untilS * 1e9).toLong()) {
            val gone = away != null && t / 1e9 in away
            if (gone && leftNs < 0) leftNs = t
            val cameBack = !gone && leftNs >= 0
            if (cameBack) {
                awayNs += t - leftNs
                leftNs = -1L
            }
            val now = t - awayNs - if (leftNs >= 0) t - leftNs else 0L
            val presented = records.count { (it.endS * 1e9).toLong() <= t }
            val drawing = loop.firstOrNull { (it.startS * 1e9).toLong() <= t && t < (it.endS * 1e9).toLong() }
                ?.let { (t - (it.startS * 1e9).toLong()) / 1_000_000 }
            wait.tick(now, presented.toLong(), drawing)
            while (fed < presented - heldBack) {
                val f = records[fed++]
                if (f.ours) wait.frame((f.endS * 1e9).toLong(), (f.endS - f.startS) * 1000.0, f.draws, f.draws * 3)
            }
            if (!gone) {
                if (cameBack) wait.cameBack(t)
                wait.stall(now, drawing)?.let { return wait to (t / 1e9 to it) }
            }
            t += 50_000_000L
        }
        return wait to null
    }

    private fun firstStall(
        loop: List<Drawn>,
        untilS: Double,
        heldBack: Int = 8,
        away: ClosedFloatingPointRange<Double>? = null,
    ): Pair<Double, String>? = waited(loop, untilS, heldBack, away).second

    @Test
    fun aSlowRunnersPictureIsWaitedForAndAStuckMapIsNot() {
        // The branch's run 41601551318 (53e376ac, 3 November) as its logcat has the frames: the map's first, which made its
        // 95 pipelines, 9.9 s; 41 more of about 130 ms as its tiles came in (the picture growing through the first 8 of
        // them, as the wait saw it); then its first frames over the tiles, waiting on lavapipe's first compiles, 26.2 s
        // and 17.0 s, and the complete picture's 1.5 s, 60.0 s in. The core handed each record on 8 frames late, so the
        // wait never saw those last 8 frames' and judged the picture by the 37 before them; its longest frame is the core's
        // health's.
        val home = backToBack(0.0, listOf(9.91 to 60) + (1..41).map { 0.1307 to minOf(it, 8) * 120 } +
            listOf(26.17 to 960, 17.04 to 960, 1.48 to 960))
        val (seen, stall) = waited(listOf(Drawn(-0.4, -0.2, 9000, ours = false)) + home, home.last().endS + 0.1)
        assertEquals(null, stall)
        assertEquals(45L, seen.frames)
        assertEquals(26170.0, seen.longestMs, 60.0)
        // Its compiles 1.66 times as long, as the slowest seen (43.4 s, onemap's 41346470652, attempt 1), and 28.2 s:
        // waited for, 88.4 s in, where a rule that judged by the frames not seen yet would have given up 71 s in.
        val slowest = backToBack(0.0, listOf(9.91 to 60) + (1..41).map { 0.1307 to minOf(it, 8) * 120 } +
            listOf(43.4 to 960, 28.2 to 960, 1.48 to 960))
        assertEquals(null, firstStall(listOf(Drawn(-0.4, -0.2, 9000, ours = false)) + slowest, slowest.last().endS + 0.1))
        // App's push run 41503008244 (5b547053, 2 November) on a slow runner (the emulator's boot 37.1 s): the stand-in's
        // last frame, begun before the wait; 11.5 s with no frame while the map's 94 pipelines were made; 31 frames of
        // about 135 ms as its tiles came in (here the picture grows through the first 24 of them alone); then its first
        // frames over the tiles: 27.4 s, 2.6 s, 16.0 s, and the complete picture's 1.4 s, 63.1 s in: past the fixed 60 s
        // the wait had, as on 7 of 26 runs from 30 October to 3 November.
        val tiles = (1..31).map { 0.135 to minOf(it, 24) * 40 }
        val slow = listOf(Drawn(-0.3, 0.1, 5000, ours = false)) +
            backToBack(11.5, tiles + listOf(27.4 to 960, 2.6 to 960, 16.0 to 960, 1.4 to 960))
        assertTrue(slow.last().endS > VkBench.MAP_WAIT_SECONDS)
        assertEquals(null, firstStall(slow, slow.last().endS))
        // twice as slow again, every frame: still waited for, its longest frame 54.8 s
        val slower = listOf(Drawn(-0.3, 0.1, 5000, ours = false)) +
            backToBack(23.0, tiles.map { (s, d) -> 2 * s to d } + listOf(54.8 to 960, 5.2 to 960, 32.0 to 960, 2.8 to 960))
        assertEquals(null, firstStall(slower, slower.last().endS))
        // a phone's: 60 frames a second, the tiles in within half a second, the fades drawn on for two seconds after; its
        // records come once their frames were presented, 2 frames on
        val phone = backToBack(0.2, (1..150).map { 1 / 60.0 to minOf(it, 30) * 30 })
        assertEquals(null, firstStall(phone, phone.last().endS, heldBack = 2))
        // The app away for 2 minutes once the picture stopped growing: VK bench's clock stands still, and the frames drawn
        // before it count no time without growth after it, so the map is waited for when it comes back.
        val beforeAway = backToBack(0.0, (1..10).map { 1.0 to it * 50 } + (1..20).map { 1.0 to 500 })
        val afterAway = backToBack(150.1, (1..20).map { 1.0 to 500 })
        assertEquals(null, firstStall(beforeAway + afterAway, afterAway.last().endS, away = 30.0..150.0))

        // The frame loop stops after 20 s of frames, none being drawn: given up 60 s after the last one.
        val stopped = backToBack(1.0, (1..19).map { 1.0 to it * 50 })
        val (at, why) = firstStall(stopped, 200.0)!!
        assertEquals(80.0, at, 0.051)
        assertEquals("no frame for 60 s, and none being drawn", why)
        // A frame that never ends (the driver or the render thread stuck): given up once it has run 180 s.
        val stuck = backToBack(1.0, (1..9).map { 1.0 to it * 50 }) + Drawn(10.0, 1e9, 500)
        val (stuckAt, stuckWhy) = firstStall(stuck, 400.0)!!
        assertEquals(190.0, stuckAt, 0.051)
        assertEquals("a frame has run for 180 s", stuckWhy)
        // Frames on without the picture growing (a tile that never comes): given up once 20 of the frames seen were drawn
        // after it last grew, over 60 s of their own times, however the frames are paced (each seen 8 frames on).
        val drawingOn = backToBack(1.0, (1..10).map { 1.0 to it * 50 } + (1..200).map { 1.4 to 500 })
        val (onAt, onWhy) = firstStall(drawingOn, 400.0)!!
        assertEquals(82.4, onAt, 0.051) // the 43rd frame since the picture last grew, 60.2 s after it, seen as the 51st ends
        assertEquals("43 frames in 60 s without the picture growing", onWhy)
        val heavyOn = backToBack(1.0, (1..10).map { 1.0 to it * 50 } + (1..30).map { 15.0 to 500 })
        val (heavyAt, heavyWhy) = firstStall(heavyOn, 500.0)!!
        assertEquals(431.0, heavyAt, 0.051) // the 20th, 300 s after it, seen as the 28th ends
        assertEquals("20 frames in 300 s without the picture growing", heavyWhy)
        // The frames of the view before (begun before the wait) set no bar for this one's growth: the board's own tiles,
        // coming in here over 92 s, each frame drawing more but fewer than a bigger view's last frame, keep it waited for,
        // as they would not were that frame taken for the board's.
        val board = backToBack(0.1, (1..400).map { 0.23 to it * 10 })
        assertEquals(null, firstStall(listOf(Drawn(-0.3, 0.1, 9000, ours = false)) + board, board.last().endS))
        val (barAt, barWhy) = firstStall(listOf(Drawn(-0.3, 0.1, 9000)) + board, board.last().endS)!!
        assertEquals(62.0, barAt, 0.051) // the 261st frame, 60.03 s after that one, seen as the 269th ends
        assertEquals("261 frames in 60 s without the picture growing", barWhy)
    }

    @Test
    fun theMatrixHoldsWhatTheDeviceHas() {
        // lavapipe: C and 1x, both routes, not a tiler: C has resolve and direct in the render pass, resolve dynamic.
        val lavapipe = VkBench.matrix(setOf(2, 4), dynamic = true, tiler = false).map { it.name }
        assertEquals(
            listOf(
                "C renderpass onepass resolve", "C renderpass composite resolve", "C renderpass composite direct",
                "C dynamic onepass resolve", "C dynamic composite resolve",
                "1x renderpass onepass resolve", "1x renderpass composite direct", "1x dynamic onepass resolve", "1x dynamic composite direct",
            ),
            lavapipe,
        )
        // A Mali with lazily allocated memory and 2x, a render pass only: no direct target on a tiler.
        val mali = VkBench.matrix(setOf(0, 1, 2, 4), dynamic = false, tiler = true).map { it.name }
        assertEquals(8, mali.size)
        assertFalse(mali.any { it.contains("direct") && !it.startsWith("1x") })
        assertTrue(mali.first().startsWith("A "))
    }

    @Test
    fun theStatisticsAreTheUsualOnes() {
        val v = doubleArrayOf(5.0, 1.0, 3.0, 2.0, 4.0)
        assertEquals(3.0, VkBench.percentile(v, 5, 50.0), 0.0)
        assertEquals(5.0, VkBench.percentile(v, 5, 95.0), 0.0)
        assertEquals(1.0, VkBench.percentile(v, 5, 0.0), 0.0)
        assertTrue(VkBench.percentile(v, 0, 50.0).isNaN())
        assertEquals(2.0, VkBench.slope(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(1.0, 3.0, 5.0), 3), 1e-12)
        assertTrue(VkBench.slope(doubleArrayOf(1.0), doubleArrayOf(1.0), 1).isNaN())
    }

    @Test
    fun aSummaryCountsDropsAndFragments() {
        val presented = doubleArrayOf(16.7, 16.6, 33.4, 16.7, 50.0)
        val s = VkBench.summarise(
            presented, presented.size, "display-timing",
            doubleArrayOf(8.0, 9.0, 10.0), 3, doubleArrayOf(1.0, 2.0), doubleArrayOf(0.5, 0.6), doubleArrayOf(0.2, 0.3), 2,
            doubleArrayOf(), 0, 5L, 2, 1000L, 263, 677, 1000.0 / 60.0,
        )
        assertEquals(3, s.dropped) // one at 33 ms (one vsync missed), two at 50 ms
        assertEquals(16.7, s.presentedP50, 0.0)
        assertEquals(50.0, s.presentedMax, 0.0)
        assertEquals(2.5, s.fse, 1e-12) // 5k fragments over two frames of 1,000 pixels: 2.5 full screens a frame
        assertTrue(s.hwuiGpuP50.isNaN())
        val line = VkBench.line("board C renderpass composite direct", s, 120.5, 30.25)
        assertTrue(line, line.startsWith("vkbench board C renderpass composite direct: p50 16.70 p95 50.00 max 50.00 dropped 3 gpu 9.00"))
        assertTrue(line.contains("graphics 120.5 native 30.3"))
        assertTrue(line.contains("presented-by display-timing"))
    }

    @Test
    fun theColourLineSetsTheTenBitFrameAgainstTheEightBitOne() {
        fun summary(p50: Double, p95: Double) = VkBench.summarise(
            doubleArrayOf(16.7), 1, "vsync", doubleArrayOf(p50, p50, p95), 3, doubleArrayOf(1.0), doubleArrayOf(0.5), doubleArrayOf(0.2), 1,
            doubleArrayOf(), 0, 0L, 0, 1000L, 263, 677, 1000.0 / 60.0,
        )
        val line = VkBench.colourLine("board A renderpass composite resolve", "rgb10a2", summary(4.31, 4.5), summary(4.12, 4.33))
        assertEquals("vkbench colour board A renderpass composite resolve: rgb10a2 4.31 ms, rgba8 4.12 ms GPU (p95 4.50 and 4.33; rgb10a2 +0.19 ms)", line)
        assertTrue(VkBench.colourLine("x", "bgr10a2", summary(Double.NaN, Double.NaN), summary(4.0, 4.0)).endsWith("bgr10a2 —)"))
        // The colour stage as VkFacts' colour.stage line gives it (CP/gpu/Facts.hpp's colour_stage).
        val deep = VkBench.colourStage("rgb10a2 (A2B10G10R10UnormPack32 in SrgbNonlinear: the surface offers it in sRGB and the frame's attachments follow it)")
        assertEquals("rgb10a2", deep.format)
        assertFalse(deep.off)
        assertEquals("A2B10G10R10UnormPack32 in SrgbNonlinear: the surface offers it in sRGB and the frame's attachments follow it", deep.why)
        assertEquals("bgr10a2", VkBench.colourStage("bgr10a2 (A2R10G10B10UnormPack32 in SrgbNonlinear: …)").format)
        val guard = "vk guard colour ImaginationProprietary 0x00661bd0 attempt 1 pipeline composite (module standin_composite, " +
            "configuration A renderpass composite resolve rgb10a2) build 0123456789abcdef"
        val off = VkBench.colourStage("off (10 bits are off for this driver and build: $guard)")
        assertEquals(null, off.format)
        assertTrue(off.off)
        assertEquals("10 bits are off for this driver and build: $guard", off.why)
        assertEquals("vkbench colour: skipped (10 bits are off for this driver and build: $guard)", VkBench.colourSkipped(off))
        assertEquals("a0 colour: rgba8 (10 bits are off for this driver and build: $guard)", VkBench.a0Colour(off, null, null))
        val none = VkBench.colourStage("none (the surface offers no 10-bit format)")
        assertEquals(null, none.format)
        assertFalse(none.off)
        assertEquals("vkbench colour: rgba8 only (the surface offers no 10-bit format)", VkBench.colourSkipped(none))
        assertEquals("a0 colour: rgba8 (the surface offers no 10-bit format)", VkBench.a0Colour(none, null, null))
        assertEquals(null, VkBench.colourStage("?").format) // a core without the line: no colour stage
        assertEquals(
            "a0 colour: rgb10a2 4.31 ms against rgba8 4.12 ms GPU in the picked configuration at the board's view, side by side in the colour stage",
            VkBench.a0Colour(deep, summary(4.31, 4.5), summary(4.12, 4.33)),
        )
        // The frame records say which depth each frame was drawn at (CP/jni/Bridge.hpp's frame_flag::ten_bit).
        val records = FrameRecords.allocate(2)
        records.putInt(FrameRecords.FLAGS_AT, FrameRecords.GPU or FrameRecords.TEN_BIT or (2 shl 8))
        records.putInt(FrameRecords.SIZE + FrameRecords.FLAGS_AT, FrameRecords.GPU)
        assertTrue(FrameRecords.read(records, 0).tenBit)
        assertFalse(FrameRecords.read(records, 1).tenBit)
        assertTrue(FrameRecords.read(records, 1).gpuValid)
    }

    @Test
    fun theColourStageMakesEachDepthsPipelinesOncePerConfiguration() {
        // The owner's PowerVR: A, B, C and 1x, both routes, a tiler. The colour stage measures each at 10 bits and at 8
        // beside it, the order turning with each configuration.
        val cells = VkBench.matrix(setOf(0, 1, 2, 4), dynamic = true, tiler = true)
        assertEquals(16, cells.size)
        val steps = VkBench.colourSteps(cells)
        assertEquals(2 * cells.size, steps.size)
        assertEquals(steps.size, steps.map { it.config to it.tenBit }.toSet().size) // each configuration once at each depth
        for (i in cells.indices) {
            assertEquals(setOf(true, false), setOf(steps[2 * i].tenBit, steps[2 * i + 1].tenBit))
            assertEquals(cells[i], steps[2 * i].config)
            assertEquals(cells[i], steps[2 * i + 1].config)
            if (i > 0) assertEquals(steps[2 * i - 1].tenBit, steps[2 * i].tenBit) // the depth goes on into the next one
        }
        assertTrue(steps.first().tenBit) // the stage opens at 10 bits
        // The core's work, as Native.bench makes it: a new depth remakes the swapchain and the pipelines, a new
        // configuration the pipelines. Starting at the frame's 8 bits in the picked configuration (the main runs'), no
        // configuration's pipelines are made twice at one depth, and the depth changes once a configuration.
        var depth = false
        var config: VkBench.Config? = null
        val made = mutableListOf<Pair<VkBench.Config, Boolean>>()
        var switches = 0
        for (step in steps) {
            if (step.tenBit != depth) switches++
            if (step.tenBit != depth || step.config != config) made += step.config to step.tenBit
            depth = step.tenBit
            config = step.config
        }
        assertEquals(made.size, made.toSet().size)
        assertEquals(steps.size, made.size)
        assertEquals(cells.size + 1, switches) // into 10 bits, then once a configuration
        assertTrue(VkBench.colourSteps(emptyList()).isEmpty())
    }

    @Test
    fun theColourStageRunsWhereThereAreNoTenBitsSayingSo() {
        // CI's emulator, or a phone whose surface offers no 10-bit sRGB format: the stage still runs, each configuration
        // at 8 bits alone, so its flow (the window going and coming back, the bounded waits) runs on every surface.
        fun summary(p50: Double, p95: Double) = VkBench.summarise(
            doubleArrayOf(16.7), 1, "vsync", doubleArrayOf(p50, p50, p95), 3, doubleArrayOf(1.0), doubleArrayOf(0.5), doubleArrayOf(0.2), 1,
            doubleArrayOf(), 0, 0L, 0, 1000L, 263, 677, 1000.0 / 60.0,
        )
        val none = VkBench.colourStage("none (the surface offers no 10-bit format)")
        assertEquals(
            "vkbench colour stage: rgba8 only (the surface offers no 10-bit format): no 10 bits here, so each configuration at the board's view at 8 bits alone",
            VkBench.colourStageLine(none),
        )
        val deep = VkBench.colourStage("rgb10a2 (A2B10G10R10UnormPack32 in SrgbNonlinear: the surface offers it in sRGB and the frame's attachments follow it)")
        assertTrue(VkBench.colourStageLine(deep).startsWith("vkbench colour stage: rgb10a2 (A2B10G10R10UnormPack32 in SrgbNonlinear: "))
        assertTrue(VkBench.colourStageLine(deep).endsWith("; each configuration at the board's view at 10 bits and at 8 beside it"))
        assertEquals(
            "vkbench colour board C renderpass composite resolve: rgba8 4.12 ms GPU (p95 4.33; no 10 bits here: the surface offers no 10-bit format)",
            VkBench.colourEightLine("board C renderpass composite resolve", summary(4.12, 4.33), none.why),
        )
        val cells = VkBench.matrix(setOf(2, 4), dynamic = true, tiler = false)
        val eightOnly = VkBench.colourSteps(cells, ten = false)
        assertEquals(cells, eightOnly.map { it.config })
        assertTrue(eightOnly.none { it.tenBit })
        assertEquals(VkBench.colourSteps(cells), VkBench.colourSteps(cells, ten = true))
        // Every wait for a window ends, and says why it ended without one.
        assertEquals("no surface window came within 60 s", VkBench.windowTimedOut("surface"))
        assertEquals(60, VkBench.WINDOW_WAIT_SECONDS)
    }

    @Test
    fun theColourStageSaysWhenTheAppGoesToTheBackgroundAndComesBack() {
        // The app sent to the background pauses the run (the core leaves the stage, nothing is drawn); in the colour
        // stage the run waits for it as for a window, and a step it cut short is measured again. smoke.sh reads these:
        // a stage that ends here fails, as one whose window never came back does.
        assertEquals(
            "vkbench colour: the app went to the background before board C renderpass composite resolve; the colour stage was left, and waits for the app (at most 60 s)",
            VkBench.colourAppAway("board C renderpass composite resolve"),
        )
        assertEquals("vkbench colour: the app came back", VkBench.colourAppBack())
        assertTrue(VkBench.colourAppGone().endsWith("; the colour stage ends here"))
        assertEquals(
            "vkbench colour board C renderpass composite resolve: rgb10a2 cut short (the app went to the background); measured again",
            VkBench.colourCutShort("board C renderpass composite resolve", "rgb10a2"),
        )
    }

    @Test
    fun theWholeRunWaitsForTheAppAndMeasuresAgainWhatItCutShort() {
        // Outside the colour stage the run waits for the app, however long it is away, and its lines are not the colour
        // stage's: smoke.sh tells the stage's own pause by "vkbench colour: …".
        assertEquals("vkbench: the app went to the background before the hosts; the run waits for it", VkBench.appAway("the hosts"))
        assertEquals("vkbench: the app came back", VkBench.appBack())
        assertFalse(VkBench.appAway("board C").startsWith("vkbench colour"))
        assertFalse(VkBench.appBack().startsWith("vkbench colour"))
        assertEquals(
            "vkbench board C renderpass composite resolve: cut short (the app went to the background); measured again",
            VkBench.cutShort("board C renderpass composite resolve"),
        )
        // The soak's line says how long the app was away of it, and nothing when it never went.
        assertEquals("", VkBench.soakAway(0.0))
        assertEquals("; the app was away 37 s of it (the slope takes the pause in)", VkBench.soakAway(37.2))
    }

    @Test
    fun aCellTheGuardTurnedOffIsSkippedSayingWhyAndTheRunGoesOn() {
        // A driver that crashed in the work of a cell the facts would not pick has that cell alone turned off for it and
        // this build (CP/gpu/Guard.hpp): the core refuses it with the guard's line, and the matrix says so and goes on.
        val guard = "vk guard cell ImaginationProprietary 0x00661bd0 attempt 1 B dynamic onepass resolve build 0123456789abcdef"
        val why = "${VkBench.CELL_OFF}$guard)"
        assertEquals(
            "vkbench board B dynamic onepass resolve: off (the driver crashed in it: $guard)",
            VkBench.skippedLine("board B dynamic onepass resolve", why),
        )
        // Anything else the core refuses is a cell the device cannot draw, as before.
        assertEquals(
            "vkbench pitch70 1x dynamic onepass resolve: unavailable (this device has no dynamic rendering with local reads)",
            VkBench.skippedLine("pitch70 1x dynamic onepass resolve", "this device has no dynamic rendering with local reads"),
        )
    }

    @Test
    fun theReportIsRecordedAsTheBenchRunsAndAnUnfinishedOneIsKept() {
        val lines = listOf("vkbench start: full bench, Imagination PowerVR", "vkbench board A renderpass composite resolve: p50 16.70")
        val running = VkBench.recorded(lines, "the colour stage")
        assertTrue(running.startsWith("atlas vkbench report, recorded at: the colour stage\n"))
        val (step, kept) = VkBench.unfinished(running) ?: error("an unfinished report was not found")
        assertEquals("the colour stage", step)
        assertEquals(lines, kept)
        // A finished run, or anything else, keeps nothing.
        assertEquals(null, VkBench.unfinished(VkBench.recorded(lines, VkBench.COMPLETE)))
        assertEquals(null, VkBench.unfinished(""))
        assertEquals(null, VkBench.unfinished("vkbench start: not a recorded report"))
        val section = VkBench.keptSection(step, kept)
        assertEquals("vkbench kept: the last run ended in the colour stage; the results it recorded before that follow", section.first())
        assertEquals(lines, section.subList(1, section.size - 1))
        assertEquals("vkbench kept: end", section.last())
    }

    @Test
    fun theDecisionsReadTheNumbers() {
        assertTrue(VkBench.onepassVerdict(6.5, 5.0).contains("ONEPASS is to be built"))
        assertTrue(VkBench.onepassVerdict(5.4, 5.0).contains("no ONEPASS"))
        assertTrue(VkBench.onepassVerdict(Double.NaN, 5.0).startsWith("unknown"))
        assertTrue(VkBench.onTime(1_000_000_000L, 1_004_000_000L, 16_666_667L))
        assertFalse(VkBench.onTime(1_000_000_000L, 1_016_000_000L, 16_666_667L))
        assertFalse(VkBench.onTime(1_000_000_000L, 0L, 16_666_667L))
        val soak = VkBench.soakLine(doubleArrayOf(0.0, 5.0, 7.5, 10.0), doubleArrayOf(0.3, 0.40, 0.41, 0.42), 4, 50.0, 300.0, false, 16.7)
        assertTrue(soak, soak.startsWith("vkbench soak: thermal slope 0.0040/min"))
        assertTrue(soak.contains("charge 50.0 mAh"))
        assertTrue(VkBench.soakLine(doubleArrayOf(0.0), doubleArrayOf(0.3), 1, 0.0, Double.NaN, true, 16.7).contains("plugged in"))
    }

    @Test
    fun theDeqpLevelReadsAsADate() {
        assertEquals("2024-03-01", VkFactsLines.dEqpDate((2024 shl 16) or (3 shl 8) or 1))
        assertEquals("2022-03-01", VkFactsLines.dEqpDate(0x07E60301))
    }

    @Test
    fun theGuardsLinesLeadTheFacts() {
        val report = listOf(
            "atlas.vkfacts: 1",
            "gpu: PowerVR",
            "guard: vk guard pipelines ImaginationProprietary 0x00661bd0 attempt 1 pipeline composite (module standin_composite, " +
                "configuration A renderpass composite resolve) build 0123456789abcdef",
            "guard.build: fedcba9876543210",
            "guard.driver: ImaginationProprietary 0x00661bd0",
            "guard.off: none",
            "guarded: not a guard line",
            "core.build: atlasmap 0.1.0",
        )
        val guard = VkFactsLines.guard(report)
        assertEquals(4, guard.size)
        assertTrue(guard[0].contains("pipeline composite (module standin_composite"))
        assertEquals("guard.off: none", guard.last())
        assertTrue(VkFactsLines.guard(listOf("gpu: x")).isEmpty())
    }

    @Test
    fun thePillsMoveWithinTheirStripAndRestEachCycle() {
        // A second's move of 80 dp at an even pace, then a second at rest; back the other way in the next cycle.
        assertEquals(-40f, pillShift(0.0), 1e-6f)
        assertEquals(0f, pillShift(0.5), 1e-4f)
        assertEquals(40f, pillShift(1.0), 1e-4f)
        assertEquals(40f, pillShift(1.7), 1e-4f)
        assertEquals(0f, pillShift(2.5), 1e-4f)
        assertEquals(-40f, pillShift(3.5), 1e-4f)
        assertEquals(-20f, pillShift(4.25), 1e-4f)
        assertTrue(pillMoving(0.5))
        assertFalse(pillMoving(1.5))
        assertTrue(pillMoving(2.2))
        assertFalse(pillMoving(3.9))
        // A core frame and the window's share their frame when the window drew that vsync, on time.
        val vsyncs = longArrayOf(0L, 16_666_667L, 33_333_333L)
        val late = booleanArrayOf(false, true, false)
        assertTrue(VkBench.drawnOnTime(vsyncs, late, 1_000_000L))
        assertFalse(VkBench.drawnOnTime(vsyncs, late, 16_666_667L))
        assertFalse(VkBench.drawnOnTime(vsyncs, late, 50_000_000L))
        val boxes = pillBoxes(412f, 915f, 10f)
        assertEquals(2, boxes.size)
        assertEquals(26f, boxes[0][0], 0f)
        assertEquals(915f - 48f, boxes[1][3], 0f)
    }
}
