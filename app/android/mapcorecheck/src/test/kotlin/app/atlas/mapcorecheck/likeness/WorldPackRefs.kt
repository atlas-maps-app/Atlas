package app.atlas.mapcorecheck.likeness

import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapview.vk.CoreMoment
import app.atlas.render.live.Moment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * The references around the world (ENGINE.md step 5, "References around the world"): the frames of
 * `likeness/world-refs.json` drawn by the app's map on the host over the world's own packs alone, `world-refs` (world.yml's
 * refs job: `packer world-refs` cut from a release's parts, beside its globe), the folder that `ATLAS_WORLD_REFS` names
 * (each pack `world-<z>-<x>-<y>.atlas` as the world's folder keeps it, and `world-globe.atlas`), listed as PackList lists
 * the world, the camera saying its place has no country's levels (WORLD_UNDER: the world from every distance).
 *
 * A pack the core does not open, a tile stage P's check (`atlas_stagep check`, the core's own decoding) does not
 * decode, or a frame that is not complete with every tile its cover wants drawn fails it: the refs job's gate. Each
 * frame's pixels' SHA-256 goes to the report's `world-refs/world-refs.json` (to paste in as a version's frames on its
 * platform) and the frame to a PNG beside it; on a platform the current version holds frames of, every frame must come
 * out as stored, as WorldRefsTest holds the board's.
 */
class WorldPackRefsTest {
    @Test
    fun `the engine draws the world's references from the world's own packs`() {
        val dir = System.getenv("ATLAS_WORLD_REFS")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("the references around the world draw over world-refs only (ATLAS_WORLD_REFS=<folder>)", dir != null)
        check(dir!!.isDirectory) { "no world-refs folder at $dir" }
        if (HostCore.load() == null) {
            check(System.getProperty("atlas.likeness.require") != "true") { "the world's references need the host core: ${HostCore.unavailable}" }
            assumeTrue("the host core: ${HostCore.unavailable}", false)
        }
        val packs = dir.listFiles { f -> WorldFolder.PACK.matches(f.name) }.orEmpty().sortedBy { it.name }
        val globe = File(dir, GLOBE)
        check(globe.isFile) { "world-refs holds no $GLOBE" }
        val out = File(Likeness.report, "world-refs").apply { mkdirs() }

        // every tile decoded as the core's store decodes it
        val tool = System.getProperty("atlas.stagep")?.let(::File)?.takeIf { it.canExecute() }
        check(tool != null) { "no stage P tool (-Datlas.stagep) to check world-refs' tiles with" }
        var tiles = 0
        for (f in packs + globe) {
            val p = ProcessBuilder(tool.path, "check", f.path).redirectErrorStream(false).start()
            val said = p.inputStream.bufferedReader().readText()
            val why = p.errorStream.bufferedReader().readText()
            check(p.waitFor(10, TimeUnit.MINUTES)) { "atlas_stagep check ${f.name} did not finish" }
            check(p.exitValue() == 0) { "atlas_stagep check ${f.name}: exit ${p.exitValue()}: $why" }
            tiles += said.lines().count { it.startsWith("tile ") }
        }
        println("world-refs: ${packs.size} packs and the globe, $tiles tiles decoded by atlas_stagep check")

        val refs = Likeness.json(File(Likeness.cases, "world-refs.json"))
        val version = refs.str("version")
        val frame = refs["frame"]!!.jsonObject
        val w = frame.dbl("width").toInt()
        val h = frame.dbl("height").toInt()
        val density = frame.dbl("density")
        val widthDp = (w / density).roundToInt()
        val heightDp = (h / density).roundToInt()
        val drawn = LinkedHashMap<String, String>()
        val failed = ArrayList<String>()
        val handle = LookHost.open(w, h, File(out, "cache").apply { mkdirs() }.path)
        check(handle != 0L) { "the host map: ${LookHost.lastError()}" }
        try {
            // MapData.wire with the Look settings unset: the haze, vignette and grain on
            LookHost.screen(handle, haze = true, vignette = true, grain = true)
            // PackList.refs: the world's packs, then the globe
            val listed = packs.map { f ->
                val (z, x, y) = WorldFolder.PACK.matchEntire(f.name)!!.destructured
                Triple("world/$z/$x/$y", WORLD_MID, f)
            } + Triple("world-globe", WORLD_BASE, globe)
            val said = LookHost.packs(handle, LongArray(listed.size) { it + 1L }, listed.map { it.second }.toIntArray(),
                listed.map { it.first }.toTypedArray(), listed.map { it.third.path }.toTypedArray())
            val refused = said.lines().filter { it.isNotBlank() && !OPENED.matches(it) }
            check(refused.isEmpty()) { "the core did not open world-refs' packs: ${refused.joinToString("; ")}" }
            for (e in refs["frames"]!!.jsonArray) {
                val o = e.jsonObject
                val name = o.str("name")
                val t0 = System.nanoTime()
                val notes = draw(handle, o, w, h, widthDp, heightDp, density, frame.dbl("cy"))
                val world = WORLD.find(notes)
                val complete = notes.contains("complete yes")
                val wanted = world?.groupValues?.get(3)?.toInt() ?: -1
                val bare = world?.groupValues?.get(4)?.toInt() ?: -1
                if (!complete || wanted != 0 || bare != 0) failed += "$name ($notes)"
                val pixels = ByteBuffer.allocateDirect(w * h * 4)
                check(LookHost.read(handle, pixels)) { LookHost.lastError() }
                val rgb = ByteArray(w * h * 3)
                for (i in 0 until w * h) {
                    rgb[i * 3] = pixels.get(i * 4); rgb[i * 3 + 1] = pixels.get(i * 4 + 1); rgb[i * 3 + 2] = pixels.get(i * 4 + 2)
                }
                ImageIO.write(WorldShot(w, h, density, rgb, notes).image(), "png", File(out, "${name.replace(' ', '-')}.png"))
                drawn[name] = MessageDigest.getInstance("SHA-256").digest(rgb).joinToString("") { "%02x".format(it) }
                println("world-refs $name: ${drawn[name]} (${(System.nanoTime() - t0) / 1_000_000} ms; ${notes.lines().first()})")
            }
        } finally {
            LookHost.close(handle)
        }
        assertTrue("world-refs' frames not drawn whole: ${failed.joinToString("; ")}", failed.isEmpty())

        val platform = Likeness.platform
        val versions = refs["versions"]?.jsonObject ?: JsonObject(emptyMap())
        val entry = versions[version]?.jsonObject ?: JsonObject(mapOf("note" to JsonPrimitive("")))
        val stored = entry["platforms"]?.jsonObject?.get(platform)?.jsonObject
        val platforms = LinkedHashMap(entry["platforms"]?.jsonObject ?: JsonObject(emptyMap())).apply { this[platform] = JsonObject(drawn.mapValues { JsonPrimitive(it.value) }) }
        val proposal = JsonObject(LinkedHashMap(refs).apply {
            this["versions"] = JsonObject(LinkedHashMap(versions).apply { this[version] = JsonObject(LinkedHashMap(entry).apply { this["platforms"] = JsonObject(platforms) }) })
        })
        File(out, "world-refs.json").writeText(Ratchet.format(proposal) + "\n")
        if (stored == null) {
            println("world-refs: version $version has no frames of $platform yet; this run's are in ${File(out, "world-refs.json")}")
            return
        }
        val changed = drawn.filter { (name, hash) -> stored[name]?.jsonPrimitive?.content != hash }.keys
        println("world-refs: ${drawn.size - changed.size} of ${drawn.size} frames as version $version stored them on $platform")
        assertTrue(
            "the engine no longer draws version $version's frames around the world on $platform: $changed (this run's frames: $out). A " +
                "deliberate change is a new version (likeness/world-refs.json); any other is a bug",
            changed.isEmpty(),
        )
    }

    /** Frame [o] drawn: its moment, its camera, the world's names handed until it wants none; the last draw's notes. */
    private fun draw(handle: Long, o: JsonObject, w: Int, h: Int, widthDp: Int, heightDp: Int, density: Double, cy: Double): String {
        val zone = ZoneId.of(o.strOrNull("zone") ?: "Europe/Amsterdam")
        val moment = o.strOrNull("time")?.let { Moment.Live(Instant.parse(it), null) } ?: Moment.Named(o.str("moment"))
        val theme = ThemeBlock.allocate()
        CoreMoment.write(theme, moment, zone, daylight = false)
        check(LookHost.theme(handle, theme)) { LookHost.lastError() }
        val camera = CameraWriter.allocate()
        // MapController.publishCamera: at rest, and where no country's levels are, the world under the cover
        CameraWriter.header(camera, ++sequence, CameraWriter.REST or CameraWriter.WORLD_UNDER)
        CameraWriter.parts(camera, 0L)
        val bearing = (o["bearing"] as? JsonPrimitive)?.content?.toDouble() ?: 0.0
        CameraWriter.view(camera, o.dbl("lat"), o.dbl("lon"), bearing, o.dbl("pitch"), o.dbl("distance"), widthDp / 2.0, heightDp * cy)
        CameraWriter.frame(camera, w, h, widthDp, heightDp, density)
        CameraWriter.pack(camera, 0L, CameraWriter.PROFILE_NONE)
        CameraWriter.keepOuts(camera, 0)
        CameraWriter.noLocation(camera)
        var drew = LookHost.draw(handle, camera, DRAW_SECONDS)
        check(drew.isNotEmpty()) { "the host map drew nothing: ${LookHost.lastError()}" }
        // the world's names: those the frame shows without a sprite are handed (TestNames' sprites, the same pixels on
        // every platform) and the map drawn again, until it wants none
        var named = 0
        for (round in 0 until NAME_ROUNDS) {
            val wants = TestNames.wants(LookHost.namesWanted(handle))
            if (wants.isEmpty()) break
            val why = LookHost.names(handle, TestNames.block(wants, density), TestNames.FLOOR_DP)
            check(why.isEmpty()) { why }
            named += wants.size
            drew = LookHost.draw(handle, camera, DRAW_SECONDS)
            check(drew.isNotEmpty()) { "the host map drew nothing: ${LookHost.lastError()}" }
        }
        return if (named > 0) "$drew; $named names' sprites handed" else drew
    }

    private companion object {
        var sequence = 0L

        /** MapCore.PackRef's world kinds (tiles::PackKind): a world pack's, and the globe's. */
        const val WORLD_MID = 3
        const val WORLD_BASE = 4

        /** WorldFetch.GLOBE: the globe's file in the world's folder. */
        const val GLOBE = "world-globe.atlas"

        /** The longest a frame is drawn for, seconds, and the rounds of names handed for one at most. */
        const val DRAW_SECONDS = 300.0
        const val NAME_ROUNDS = 6

        /** MapFrame::set_packs' line for a pack it took ("<id>: <why not>" for one it did not). */
        val OPENED = Regex(""".* (opened|listed|kept)""")

        /** The world's part of the tiles line (scene::World::tiles_line). */
        val WORLD = Regex("""world (\d+) drawn finest (\d+) wanted (\d+) bare (\d+)""")
    }
}
