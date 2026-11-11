package app.atlas.mapcorecheck.likeness

import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapview.vk.CoreMoment
import app.atlas.packer.world.LandBase
import app.atlas.packer.world.StagePTags
import app.atlas.packer.world.WorldCut
import app.atlas.packer.world.WorldGeometry
import app.atlas.packer.world.WorldPack
import app.atlas.packer.world.WorldProfile
import app.atlas.packer.world.WorldTiles
import app.atlas.render.live.Moment
import app.atlas.rendertiles.codec.Format3Writer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The Globe board's Earth as a world for the core (ENGINE.md step 5, the globe check): the land and the 80 cities the
 * app's Globe board draws (`res/raw/local_globe.json`: Natural Earth's, public domain), cut into a globe pack of L0 to
 * L3 by the packer's own world code (its land base, its cut at each tile's cells, its pack), so the core draws the
 * board's own Earth and the check compares looks, not two sets of coastlines. A city's people stand for its size on
 * the board (its first size ten million, its second three, its third one).
 */
internal object BoardGlobe {
    class City(val lon: Double, val lat: Double, val size: Int)

    class Data(val land: List<DoubleArray>, val cities: List<City>)

    const val LAST = 3

    val data: Data by lazy {
        val file = File(Likeness.repo, "app/android/app/src/main/res/raw/local_globe.json")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        fun numbers(a: JsonArray) = DoubleArray(a.size) { a[it].jsonPrimitive.doubleOrNull ?: 0.0 }
        Data(
            root.getValue("land").jsonArray.map { numbers(it.jsonArray) },
            root.getValue("cities").jsonArray.map { val a = numbers(it.jsonArray); City(a[0], a[1], a[2].toInt()) },
        )
    }

    private val built = HashMap<String, File>()

    /** The pack, built once a run into [dir] as `world-globe.atlas` (a file left there by an earlier run is replaced). */
    fun pack(dir: File): File = synchronized(built) { built.getOrPut(dir.canonicalPath) { build(dir) } }

    private fun build(dir: File): File {
        val out = File(dir, "world-globe.atlas")
        out.delete()
        dir.mkdirs()
        val f = WorldGeometry.FLOAT
        // the board fills its rings by their winding, so a ring wound against the land's is a hole in it (the Caspian
        // Sea): the land's rings are those wound as the largest is
        fun area(ring: DoubleArray): Double {
            var a = 0.0
            val n = ring.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                a += ring[2 * i] * ring[2 * j + 1] - ring[2 * j] * ring[2 * i + 1]
            }
            return a / 2
        }
        val outer = area(data.land.maxBy { abs(area(it)) }) > 0
        val pieces = ArrayList<Geometry>()
        val holes = ArrayList<Geometry>()
        for (ring in data.land) {
            val n = ring.size / 2
            if (n < 3) continue
            // a ring across the antimeridian (Fiji, Chukotka) runs on past 180°: its longitudes unwrapped, and the ring
            // once more a turn to the other side, so each half lies in the world's square
            val lons = DoubleArray(n)
            lons[0] = ring[0]
            for (i in 1 until n) {
                var step = ring[2 * i] - ring[2 * (i - 1)]
                if (step > 180.0) step -= 360.0 else if (step < -180.0) step += 360.0
                lons[i] = lons[i - 1] + step
            }
            val shifts = when {
                lons.max() > 180.0 -> doubleArrayOf(0.0, -360.0)
                lons.min() < -180.0 -> doubleArrayOf(0.0, 360.0)
                else -> doubleArrayOf(0.0)
            }
            for (shift in shifts) {
                val c = ArrayList<Coordinate>(n + 1)
                for (i in 0 until n) c += Coordinate(lons[i] + shift, ring[2 * i + 1])
                if (c.first() != c.last()) c += Coordinate(c.first())
                (if ((area(ring) > 0) == outer) pieces else holes) += WorldGeometry.worldOfDegrees(f.createPolygon(c.toTypedArray()))
            }
        }
        if (holes.isNotEmpty()) {
            val cut = OverlayNGRobust.union(f.buildGeometry(holes.map { WorldGeometry.valid(it) }))
            val whole = pieces.map { WorldGeometry.valid(it) }
            pieces.clear()
            for (g in whole) {
                val left = if (g.envelopeInternal.intersects(cut.envelopeInternal)) OverlayNGRobust.overlay(g, cut, OverlayNG.DIFFERENCE) else g
                if (!left.isEmpty) pieces += left
            }
        }
        val land = LandBase(pieces)
        val places: List<Pair<Map<String, Any?>, Geometry>> = data.cities.mapIndexed { i, c ->
            val people = when (c.size) { 1 -> 10_000_000L; 2 -> 3_000_000L; else -> 1_000_000L }
            val attrs = linkedMapOf<String, Any?>("kind" to "city", "name" to "city ${i + 1}", "population" to people.toString(), "scalerank" to c.size - 1)
            attrs to WorldGeometry.worldOfDegrees(f.createPoint(Coordinate(c.lon, c.lat)))
        }
        val cut = WorldCut(StagePTags.STAGE_P)
        val encoded = ArrayList<Format3Writer.Encoded>()
        for (z in 0..LAST) {
            land.walk(0, 0, 0, z) { tz, tx, ty, mine ->
                if (tz == z) {
                    val box = WorldGeometry.tileWorld(tz, tx, ty)
                    val items = places.filter { box.contains(it.second.coordinate) }
                        .map { WorldCut.Item(WorldProfile.PLACE, it.first, WorldGeometry.worldToPixels(it.second, tz, tx, ty)) }
                    WorldTiles.fitted(cut, tz, tx, ty, items) { tolerance -> land.land(tz, tx, ty, mine, tolerance) }.encoded?.let { encoded += it }
                }
                true
            }
        }
        val source = WorldPack.sha256(listOf("local_globe.json", MessageDigest.getInstance("SHA-256").digest(File(Likeness.repo, "app/android/app/src/main/res/raw/local_globe.json").readBytes()).joinToString("") { "%02x".format(it) }))
        WorldPack.write(encoded, WorldPack.Spec("world-globe", 0, 0, 0, 0..LAST, null, listOf("the Globe board's Earth (Natural Earth, public domain)"), source), out)
        return out
    }
}

/** A frame of the world as the app's map draws it on the host (jni/TestLook.cpp), R G B bytes a pixel in [rgb]. */
internal class WorldShot(val w: Int, val h: Int, val density: Double, val rgb: ByteArray, val notes: String) {
    /** The colour at pixel ([x], [y]), bilinear between the four pixels about it, as doubles in [out] (r, g, b). */
    fun sample(x: Double, y: Double, out: DoubleArray) {
        val fx = (x - 0.5).coerceIn(0.0, w - 1.0)
        val fy = (y - 0.5).coerceIn(0.0, h - 1.0)
        val x0 = floor(fx).toInt().coerceAtMost(w - 2)
        val y0 = floor(fy).toInt().coerceAtMost(h - 2)
        val tx = fx - x0
        val ty = fy - y0
        for (c in 0 until 3) {
            fun at(px: Int, py: Int) = (rgb[(py * w + px) * 3 + c].toInt() and 0xFF).toDouble()
            out[c] = (at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx) * (1 - ty) + (at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx) * ty
        }
    }

    fun image(): BufferedImage {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until w * h) {
            image.setRGB(i % w, i / w, ((rgb[i * 3].toInt() and 0xFF) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[i * 3 + 2].toInt() and 0xFF))
        }
        return image
    }
}

/**
 * The app's map on the host over the board's globe alone, as the phone's Bridge drives it ([LookHost]): frames of
 * [w] × [h] px at [density], the screen's haze, vignette and grain as [screen] says (the app's Look settings have them
 * on), its files under [work].
 */
internal class WorldHost(private val w: Int, private val h: Int, private val density: Double, screen: Boolean, work: File) : AutoCloseable {
    private val handle: Long

    init {
        val globe = BoardGlobe.pack(File(work, "globe"))
        handle = LookHost.open(w, h, File(work, "cache").apply { mkdirs() }.path)
        check(handle != 0L) { "the host map: ${LookHost.lastError()}" }
        LookHost.screen(handle, haze = screen, vignette = screen, grain = screen)
        LookHost.packs(handle, longArrayOf(1L), intArrayOf(WORLD_BASE), arrayOf("world-globe"), arrayOf(globe.path))
    }

    /**
     * The world from [distance] metres over ([lat], [lon]) at the map's [bearing] and [pitch], at [moment], the target
     * at ([w] / 2, [cyDp]) of the frame.
     */
    fun draw(lat: Double, lon: Double, distance: Double, bearing: Double, pitch: Double, moment: Moment, cyDp: Double): WorldShot {
        val theme = ThemeBlock.allocate()
        CoreMoment.write(theme, moment, ZoneId.of("Europe/Amsterdam"), daylight = false)
        check(LookHost.theme(handle, theme)) { LookHost.lastError() }
        val widthDp = (w / density).roundToInt()
        val heightDp = (h / density).roundToInt()
        val camera = CameraWriter.allocate()
        CameraWriter.header(camera, ++sequence, CameraWriter.REST)
        CameraWriter.parts(camera, 0L)
        CameraWriter.view(camera, lat, lon, bearing, pitch, distance, widthDp / 2.0, cyDp)
        CameraWriter.frame(camera, w, h, widthDp, heightDp, density)
        CameraWriter.pack(camera, 0L, CameraWriter.PROFILE_NONE)
        CameraWriter.keepOuts(camera, 0)
        CameraWriter.noLocation(camera)
        val drew = LookHost.draw(handle, camera, 120.0)
        check(drew.isNotEmpty()) { "the host map drew nothing: ${LookHost.lastError()}" }
        val pixels = ByteBuffer.allocateDirect(w * h * 4)
        check(LookHost.read(handle, pixels)) { LookHost.lastError() }
        val rgb = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            rgb[i * 3] = pixels.get(i * 4); rgb[i * 3 + 1] = pixels.get(i * 4 + 1); rgb[i * 3 + 2] = pixels.get(i * 4 + 2)
        }
        return WorldShot(w, h, density, rgb, drew)
    }

    override fun close() = LookHost.close(handle)

    private companion object {
        var sequence = 0L

        /** MapCore.PackRef.WORLD_BASE: the globe's kind (tiles::PackKind::world_base). */
        const val WORLD_BASE = 4
    }
}

/**
 * The globe check (ENGINE.md step 5, "The globe": held by the engine's frame at the board's camera and instant
 * against `drawEarth` rasterised by Robolectric).
 *
 * The board's picture is `likeness/globe/board.png`, which `:app`'s GlobeBoardEarthTest draws with the app's own
 * `drawEarth` and holds to (`board.txt` says of what instant and view). The core draws the same Earth ([BoardGlobe])
 * at that instant ([CoreMoment]'s live moment: the sun the phone's map is lit by) from the board's camera: straight
 * down over the latitude of the board's tilt and its longitude, north up, from where the planet's disc is the board's
 * 150 dp about (206, 420), at two pixels a dp, with the screen's haze, vignette and grain off.
 *
 * The board's Earth is an orthographic disc and the core's camera a perspective one (the map's, 36° tall): from its
 * 53,800 km a place half way out on the board lies 8 dp further out in the core's frame. Both key every colour to the
 * place and to the surface's normal in the view, so the check compares the two place by place: inside the disc (to
 * 0.975 of its radius, where the same place lies clear of the core's rim) a board pixel is compared with the core's
 * frame where the same place of the planet lies; on the limb and outside it (the rim, the atmosphere's ring, space),
 * where the two discs are the same circle, pixel for pixel: the last 4 dp inside the limb show the board's places
 * edge-on and the core's a little before its horizon, so there the two differ by what the perspective hides.
 * Each board pixel takes the mean of the core's two by two pixels there.
 */
class GlobeCheckTest {
    private class Board(val image: BufferedImage, val instant: Instant, val lon: Double, val tilt: Double, val cx: Double, val cy: Double, val radius: Double)

    private fun board(): Board {
        val dir = File(Likeness.cases, "globe")
        val about = File(dir, "board.txt").readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return Board(
            ImageIO.read(File(dir, "board.png")), Instant.parse(about.getValue("instant")), about.getValue("lon").toDouble(), about.getValue("tilt").toDouble(),
            about.getValue("cx").toDouble(), about.getValue("cy").toDouble(), about.getValue("radius").toDouble(),
        )
    }

    @Test
    fun `the engine draws the Globe board's Earth`() {
        val required = System.getProperty("atlas.likeness.require") == "true"
        if (HostCore.load() == null) {
            check(!required) { "the globe check needs the host core: ${HostCore.unavailable}" }
            assumeTrue("the host core: ${HostCore.unavailable}", false)
        }
        val b = board()
        val out = File(Likeness.report, "globe").apply { mkdirs() }
        val wDp = b.image.width
        val hDp = b.image.height
        val density = 2.0
        // the camera: the planet's limb at the board's radius about the frame's target
        val focal = (hDp / 2.0) / tan(Math.toRadians(FIELD_OF_VIEW) / 2.0)
        val t = b.radius / focal
        val distance = EARTH_RADIUS / (t / sqrt(1.0 + t * t)) - EARTH_RADIUS
        val shot = WorldHost((wDp * density).toInt(), (hDp * density).toInt(), density, screen = false, work = File(out, "work")).use {
            it.draw(b.tilt, b.lon, distance, 0.0, 0.0, Moment.Live(b.instant, null), b.cy)
        }
        ImageIO.write(shot.image(), "png", File(out, "core.png"))
        println("globe check: the core from %.0f km: ${shot.notes.lines().first()}".format(distance / 1000.0))

        // the core's frame as the board's picture: place by place inside the disc, pixel for pixel about it
        val warped = BufferedImage(wDp, hDp, BufferedImage.TYPE_INT_RGB)
        val diff = BufferedImage(wDp, hDp, BufferedImage.TYPE_INT_RGB)
        val sums = DoubleArray(3)
        val counts = IntArray(3)
        val s = DoubleArray(3)
        val mean = DoubleArray(3)
        for (y in 0 until hDp) {
            for (x in 0 until wDp) {
                val rho = hypot(x + 0.5 - b.cx, y + 0.5 - b.cy) / b.radius
                mean.fill(0.0)
                for (q in 0 until 4) {
                    val bx = x + 0.25 + 0.5 * (q and 1)
                    val by = y + 0.25 + 0.5 * (q shr 1)
                    val at = if (rho <= INSIDE) onCore(bx, by, b, focal, distance) else doubleArrayOf(bx, by)
                    shot.sample(at[0] * density, at[1] * density, s)
                    for (c in 0 until 3) mean[c] += s[c] / 4.0
                }
                val want = b.image.getRGB(x, y)
                var worst = 0.0
                val region = if (rho <= INSIDE) 0 else if (rho <= ABOUT) 1 else 2
                for (c in 0 until 3) {
                    val d = abs(mean[c] - ((want shr (16 - 8 * c)) and 0xFF))
                    sums[region] += d / 3.0
                    worst = max(worst, d)
                }
                counts[region]++
                warped.setRGB(x, y, (mean[0].roundToInt() shl 16) or (mean[1].roundToInt() shl 8) or mean[2].roundToInt())
                val level = min(255, (worst * 4).roundToInt())
                diff.setRGB(x, y, (level shl 16) or (level shl 8) or level)
            }
        }
        ImageIO.write(warped, "png", File(out, "core-as-board.png"))
        ImageIO.write(diff, "png", File(out, "difference-x4.png"))
        ImageIO.write(b.image, "png", File(out, "board.png"))
        val disc = sums[0] / counts[0]
        val about = sums[1] / counts[1]
        val space = sums[2] / counts[2]
        val all = sums.sum() / counts.sum()
        println("globe check: mean difference %.2f levels on the disc (%d px), %.2f on the limb and about it to %.2f radii (%d px), %.2f beyond (%d px); %.2f over the frame"
            .format(disc, counts[0], about, ABOUT, counts[1], space, counts[2], all))

        // the lights, by place: a city lit on the board is lit in the core's frame within a dp of its place, and none other
        val lights = StringBuilder()
        var matched = 0
        var boardOnly = 0
        var coreOnly = 0
        var farthest = 0.0
        var brightest = 0.0
        val boardShot = shotOf(b.image)
        for ((i, c) in BoardGlobe.data.cities.withIndex()) {
            val n = normal(c.lat, c.lon, b)
            if (n[2] < LIGHTS_FACING) continue
            val onBoard = doubleArrayOf(b.cx + b.radius * n[0], b.cy - b.radius * n[1])
            val inCore = onCore(onBoard[0], onBoard[1], b, focal, distance)
            val lit = light(boardShot, onBoard[0], onBoard[1], 1.0)
            val drawn = light(shot, inCore[0] * density, inCore[1] * density, density)
            if (lit != null && drawn != null) {
                matched++
                // the two lights' places, each from where its city lies in its own picture
                val apart = hypot(lit[0] - drawn[0], lit[1] - drawn[1])
                farthest = max(farthest, apart)
                brightest = max(brightest, abs(lit[2] - drawn[2]))
                println("globe check: city ${i + 1} (${c.lat}, ${c.lon}), size ${c.size}: its lights %.2f dp apart, the board's %.0f levels warm and the core's %.0f".format(apart, lit[2], drawn[2]))
            } else if (lit != null) {
                boardOnly++
                lights.append(" city ${i + 1} (${c.lat}, ${c.lon}) is lit on the board alone;")
            } else if (drawn != null) {
                coreOnly++
                lights.append(" city ${i + 1} (${c.lat}, ${c.lon}) is lit in the core's frame alone;")
            }
        }
        println("globe check: $matched cities lit in both, the farthest two %.2f dp apart, the most unlike by %.0f levels of warmth; $boardOnly on the board alone, $coreOnly in the core's alone".format(farthest, brightest))
        File(out, "numbers.txt").writeText(
            "disc %.3f\nabout %.3f\nspace %.3f\nframe %.3f\nlights %d\nlights_board_only %d\nlights_core_only %d\nlights_farthest_dp %.3f\ndistance_km %.1f\n"
                .format(disc, about, space, all, matched, boardOnly, coreOnly, farthest, distance / 1000.0),
        )
        assertTrue("the core's Earth is not the board's: $all levels over the frame, $disc on the disc, $about about the limb, $space beyond (limits $MOST_FRAME, $MOST_DISC, $MOST_ABOUT, $MOST_SPACE; pictures in $out)",
            all <= MOST_FRAME && disc <= MOST_DISC && about <= MOST_ABOUT && space <= MOST_SPACE)
        assertTrue("the cities' lights are not the board's:$lights (pictures in $out)", boardOnly == 0 && coreOnly == 0 && matched > 0)
        assertTrue("a light lies $farthest dp from its place", farthest <= 1.0)
    }

    /** OrthoView.project: the view's coordinates (x right, y up, z towards us) of a place. */
    private fun normal(lat: Double, lon: Double, b: Board): DoubleArray {
        val l = Math.toRadians(lon - b.lon)
        val p = Math.toRadians(lat)
        val st = sin(Math.toRadians(b.tilt))
        val ct = cos(Math.toRadians(b.tilt))
        return doubleArrayOf(cos(p) * sin(l), ct * sin(p) - st * cos(p) * cos(l), st * sin(p) + ct * cos(p) * cos(l))
    }

    /**
     * Where the core's camera sees the place the board shows at its pixel ([bx], [by]), dp: the place whose normal in
     * the view is the pixel's on the unit disc, on the planet from the camera's [distance] over the disc's middle.
     */
    private fun onCore(bx: Double, by: Double, b: Board, focal: Double, distance: Double): DoubleArray {
        val nx = (bx - b.cx) / b.radius
        val ny = -(by - b.cy) / b.radius
        val nz = sqrt(max(0.0, 1.0 - nx * nx - ny * ny))
        val depth = distance + EARTH_RADIUS * (1.0 - nz)
        return doubleArrayOf(b.cx + focal * EARTH_RADIUS * nx / depth, b.cy - focal * EARTH_RADIUS * ny / depth)
    }

    private fun shotOf(image: BufferedImage): WorldShot {
        val rgb = ByteArray(image.width * image.height * 3)
        for (i in 0 until image.width * image.height) {
            val p = image.getRGB(i % image.width, i / image.width)
            rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte()
        }
        return WorldShot(image.width, image.height, 1.0, rgb, "")
    }

    /**
     * Whether a city's light shows at pixel ([x], [y]) of [shot]: its warmth (red over blue) over the ground's about
     * it, and then where the warm pixels' middle lies from the place (dp, x and y) and the most warmth; null where
     * none shows.
     */
    private fun light(shot: WorldShot, x: Double, y: Double, density: Double): DoubleArray? {
        val s = DoubleArray(3)
        fun warmth(px: Double, py: Double): Double { shot.sample(px, py, s); return s[0] - s[2] }
        // the ring 6 to 8 dp out: the ground's warmth is all but its warmest fifth's (a coast's land beside the sea is as
        // warm as the land under the place; a neighbour's light takes less of the ring)
        val ring = ArrayList<Double>()
        for (k in 0 until 24) {
            val a = 2 * Math.PI * k / 24
            for (r in doubleArrayOf(6.0, 7.0, 8.0)) ring += warmth(x + r * density * cos(a), y + r * density * sin(a))
        }
        ring.sort()
        val ground = ring[ring.size * 4 / 5]
        var sum = 0.0
        var mx = 0.0
        var my = 0.0
        var most = 0.0
        val reach = (3.0 * density).toInt()
        for (dy in -reach..reach) {
            for (dx in -reach..reach) {
                if (dx * dx + dy * dy > reach * reach) continue
                val over = warmth(x + dx, y + dy) - ground
                if (over <= 0.0) continue
                sum += over; mx += over * dx; my += over * dy
                most = max(most, over)
            }
        }
        if (most < LIGHT_WARMTH) return null
        return doubleArrayOf(mx / sum / density, my / sum / density, most)
    }

    private companion object {
        const val EARTH_RADIUS = 6_371_000.0
        const val FIELD_OF_VIEW = 36.0
        /** The disc's inside, compared place by place, and how far about it the limb's surroundings reach (radii). */
        const val INSIDE = 0.975
        const val ABOUT = 1.25
        /**
         * The most the core's frame may differ from the board's picture, mean levels (0 to 255) of a region's pixels:
         * the design's 1.5 over the frame and on the disc (0.50 and 0.88 measured on lavapipe, 11 November), and what
         * the limb's last 4 dp, the rim and the ring leave (1.52 measured) and space (0.23).
         */
        const val MOST_FRAME = 1.5
        const val MOST_DISC = 1.5
        const val MOST_ABOUT = 2.5
        const val MOST_SPACE = 0.5
        /**
         * A city is compared where it faces the view by this much: nearer the limb the core's perspective shows the
         * places a few dp apart, and its horizon hides the last of them.
         */
        const val LIGHTS_FACING = 0.3
        /** The warmth over the ground's (levels of red over blue) from which a light shows. */
        const val LIGHT_WARMTH = 10.0
    }
}

/**
 * The world's references at the style ladder's knots (ENGINE.md step 5, "The style ladder": held by references at
 * each knot): the app's map over the board's Earth ([BoardGlobe]) at 18, 75, 600, 2,000 and 6,000 km and from the
 * board's camera, by day and at night, straight down and pitched, as the phone draws it (every effect, at the phone's
 * size), stored per version in `likeness/world.json` as the SHA-256 of each frame's pixels on each platform, as the
 * engine's own references are (EngineRefs.kt): on a platform that has references of the current version every frame
 * must come out the same, pixel for pixel. A deliberate change to the world's look is a new version, recorded with
 * `-Datlas.engine.record=true`; a platform without frames of the current version prints its own and leaves them in
 * the report's `world/world.json`, to paste in. Every run leaves its frames there as PNGs, to look at.
 */
class WorldRefsTest {
    @Test
    fun `the engine draws the world's frames its version stored`() {
        val required = System.getProperty("atlas.likeness.require") == "true"
        if (HostCore.load() == null) {
            check(!required) { "the world's references need the host core: ${HostCore.unavailable}" }
            assumeTrue("the host core: ${HostCore.unavailable}", false)
        }
        val file = File(Likeness.cases, "world.json")
        val refs = Likeness.json(file)
        val version = refs.str("version")
        val platform = Likeness.platform
        val out = File(Likeness.report, "world").apply { mkdirs() }
        val drawn = LinkedHashMap<String, String>()
        val (w, h, density) = Triple(1080, 2400, 1080.0 / 412.0)
        WorldHost(w, h, density, screen = true, work = File(out, "work")).use { host ->
            for (f in refs["frames"]!!.jsonArray) {
                val o = f.jsonObject
                val name = o.str("name")
                val t0 = System.nanoTime()
                val shot = host.draw(o.dbl("lat"), o.dbl("lon"), o.dbl("distance"), 0.0, o.dbl("pitch"), Moment.Named(o.str("moment")), (h / density) * 0.48)
                ImageIO.write(shot.image(), "png", File(out, "${name.replace(' ', '-')}.png"))
                drawn[name] = MessageDigest.getInstance("SHA-256").digest(shot.rgb).joinToString("") { "%02x".format(it) }
                println("world ref $name: ${drawn[name]} (${(System.nanoTime() - t0) / 1_000_000} ms; ${shot.notes.lines().first()})")
            }
        }
        val versions = refs["versions"]?.jsonObject ?: JsonObject(emptyMap())
        val stored = versions[version]?.jsonObject?.get("platforms")?.jsonObject?.get(platform)?.jsonObject
        val entry = versions[version]?.jsonObject ?: JsonObject(mapOf("note" to JsonPrimitive("")))
        val platforms = LinkedHashMap(entry["platforms"]?.jsonObject ?: JsonObject(emptyMap())).apply { this[platform] = JsonObject(drawn.mapValues { JsonPrimitive(it.value) }) }
        val proposal = JsonObject(LinkedHashMap(refs).apply {
            this["versions"] = JsonObject(LinkedHashMap(versions).apply { this[version] = JsonObject(LinkedHashMap(entry).apply { this["platforms"] = JsonObject(platforms) }) })
        })
        File(out, "world.json").writeText(Ratchet.format(proposal) + "\n")
        if (System.getProperty("atlas.engine.record") == "true") {
            file.writeText(Ratchet.format(proposal) + "\n")
            println("world refs: version $version's frames on $platform recorded in $file")
            return
        }
        if (stored == null) {
            println("world refs: version $version has no frames of $platform yet; this run's are in ${File(out, "world.json")}")
            return
        }
        val changed = drawn.filter { (name, hash) -> stored[name]?.jsonPrimitive?.content != hash }.keys
        println("world refs: ${drawn.size - changed.size} of ${drawn.size} frames as version $version stored them on $platform")
        assertTrue(
            "the engine no longer draws version $version's frames of the world on $platform: $changed (this run's frames: $out). A deliberate " +
                "change is a new version (likeness/world.json, recorded with -Datlas.engine.record=true); any other is a bug",
            changed.isEmpty(),
        )
    }
}
