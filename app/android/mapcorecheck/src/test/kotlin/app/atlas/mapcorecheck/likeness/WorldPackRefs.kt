package app.atlas.mapcorecheck.likeness

import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapview.vk.CoreMoment
import app.atlas.render.live.Moment
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * platform, with the world they are of, `ATLAS_WORLD_REFS_RELEASE`) and the frame to a PNG beside it; on a platform the
 * current version holds frames of, every frame must come out as stored, as WorldRefsTest holds the board's, where the
 * world drawn is the one they are of (its `world`): another world's data draws other pixels, so its frames are only
 * proposed (a month's world drawn by world.yml's schedule).
 *
 * `ATLAS_WORLD_REFS_VIEWS` names other views of `likeness` to draw instead, held the same way, their report in a folder
 * of their name: `world-keep.json`, a kept country's zoom-out from 18 km to the globe over the tiles `packer world-keep`
 * keeps for it alone (ENGINE.md step 11), whole in every frame, without the world's names (its `names`). A view whose
 * `pixels` are false is not drawn: it is held by its tiles alone (`packer world-refs --kept`).
 */
class WorldPackRefsTest {
    @Test
    fun `the engine draws the world's references from the world's own packs`() {
        val dir = System.getenv("ATLAS_WORLD_REFS")?.takeIf { it.isNotBlank() }?.let(::File)
        val views = System.getenv("ATLAS_WORLD_REFS_VIEWS")?.takeIf { it.isNotBlank() } ?: "world-refs.json"
        val name = views.removeSuffix(".json")
        assumeTrue("the references around the world draw over world-refs only (ATLAS_WORLD_REFS=<folder>)", dir != null)
        check(dir!!.isDirectory) { "no world-refs folder at $dir" }
        if (HostCore.load() == null) {
            check(System.getProperty("atlas.likeness.require") != "true") { "the world's references need the host core: ${HostCore.unavailable}" }
            assumeTrue("the host core: ${HostCore.unavailable}", false)
        }
        val packs = dir.listFiles { f -> WorldFolder.PACK.matches(f.name) }.orEmpty().sortedBy { it.name }
        val globe = File(dir, GLOBE)
        check(globe.isFile) { "world-refs holds no $GLOBE" }
        val out = File(Likeness.report, name).apply { mkdirs() }

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
        println("$name: ${packs.size} packs and the globe, $tiles tiles decoded by atlas_stagep check")

        val refs = Likeness.json(File(Likeness.cases, views))
        val version = refs.str("version")
        val frame = refs["frame"]!!.jsonObject
        val w = frame.dbl("width").toInt()
        val h = frame.dbl("height").toInt()
        val density = frame.dbl("density")
        // the world's names, handed their sprites, unless the views say "names": false: a frame keeps the names it
        // picked while its tiles came that still rank among the first 96 beside the first 64, and the host draws on
        // without waiting for tiles while a fade runs, so where more than 64 stand in reach a frame's names follow when
        // its tiles came (world-keep.json's two draws, held to each other, leave them out: the keep holds its views'
        // names by their tiles, packer world-refs --kept)
        val names = (refs["names"] as? JsonPrimitive)?.booleanOrNull ?: true
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
                // a view held by its tiles alone, its pixels false (world-keep.json's pitched views from 60 to 1,000 km:
                // whole, but a pixel or two otherwise over the kept tiles than over the walk's in one a run, ENGINE.md
                // step 11)
                if ((o["pixels"] as? JsonPrimitive)?.booleanOrNull == false) continue
                val frameName = o.str("name")
                val t0 = System.nanoTime()
                val notes = draw(handle, o, w, h, widthDp, heightDp, density, frame.dbl("cy"), names)
                if (!whole(notes)) failed += "$frameName ($notes)"
                val pixels = ByteBuffer.allocateDirect(w * h * 4)
                check(LookHost.read(handle, pixels)) { LookHost.lastError() }
                val rgb = ByteArray(w * h * 3)
                for (i in 0 until w * h) {
                    rgb[i * 3] = pixels.get(i * 4); rgb[i * 3 + 1] = pixels.get(i * 4 + 1); rgb[i * 3 + 2] = pixels.get(i * 4 + 2)
                }
                ImageIO.write(WorldShot(w, h, density, rgb, notes).image(), "png", File(out, "${frameName.replace(' ', '-')}.png"))
                drawn[frameName] = MessageDigest.getInstance("SHA-256").digest(rgb).joinToString("") { "%02x".format(it) }
                println("$name $frameName: ${drawn[frameName]} (${(System.nanoTime() - t0) / 1_000_000} ms; ${notes.lines().first()})")
            }
        } finally {
            LookHost.close(handle)
        }
        assertTrue("$name's frames not drawn whole: ${failed.joinToString("; ")}", failed.isEmpty())

        val platform = Likeness.platform
        // the world drawn: the release world.yml's refs job cut the folder from (unset for a folder of no named release)
        val world = System.getenv("ATLAS_WORLD_REFS_RELEASE")?.takeIf { it.isNotBlank() }
        val versions = refs["versions"]?.jsonObject ?: JsonObject(emptyMap())
        val entry = versions[version]?.jsonObject ?: JsonObject(mapOf("note" to JsonPrimitive("")))
        val stored = entry["platforms"]?.jsonObject?.get(platform)?.jsonObject
        // the world the version's frames are of: they hold the engine over that world alone, as another month's data
        // draws other pixels with the same engine
        val storedWorld = entry["world"]?.jsonPrimitive?.content
        val platforms = LinkedHashMap(entry["platforms"]?.jsonObject ?: JsonObject(emptyMap())).apply { this[platform] = JsonObject(drawn.mapValues { JsonPrimitive(it.value) }) }
        val proposed = LinkedHashMap<String, JsonElement>().apply {
            this["note"] = entry["note"] ?: JsonPrimitive("")
            (world?.let { JsonPrimitive(it) } ?: entry["world"])?.let { this["world"] = it }
            for ((k, v) in entry) if (k !in setOf("note", "world", "platforms")) this[k] = v
            this["platforms"] = JsonObject(platforms)
        }
        val proposal = JsonObject(LinkedHashMap(refs).apply {
            this["versions"] = JsonObject(LinkedHashMap(versions).apply { this[version] = JsonObject(proposed) })
        })
        File(out, views).writeText(Ratchet.format(proposal) + "\n")
        if (stored == null) {
            println("$name: version $version has no frames of $platform yet; this run's are in ${File(out, views)}")
            return
        }
        val changed = drawn.filter { (frameName, hash) -> stored[frameName]?.jsonPrimitive?.content != hash }.keys
        if (world != null && storedWorld != null && world != storedWorld) {
            println("$name: version $version's frames are of $storedWorld, this is $world: ${drawn.size - changed.size} of ${drawn.size} frames alike; this run's are in ${File(out, views)}")
            return
        }
        println("$name: ${drawn.size - changed.size} of ${drawn.size} frames as version $version stored them on $platform")
        assertTrue(
            "the engine no longer draws version $version's frames around the world on $platform: $changed (this run's frames: $out). A " +
                "deliberate change is a new version (likeness/$views); any other is a bug",
            changed.isEmpty(),
        )
    }

    /**
     * Frame [o] drawn: its moment, its camera, the world's names handed until it wants none (none with [names] false),
     * and drawn on until [whole]; the last draw's notes.
     */
    private fun draw(handle: Long, o: JsonObject, w: Int, h: Int, widthDp: Int, heightDp: Int, density: Double, cy: Double, names: Boolean): String {
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
        // every platform) and the map drawn again, until it wants none. And a frame not yet whole is drawn again: the
        // host's draw ends where its residency has nothing under way, which the world's tiles alone never put there (they
        // are WorldTiles'), so a call can end before the frame is whole. Each frame drawn once, without names, left some
        // of the Netherlands' views not whole (World run 43256654382)
        var named = 0
        for (round in 0 until ROUNDS) {
            var handed = false
            if (names) {
                val wants = TestNames.wants(LookHost.namesWanted(handle))
                if (wants.isNotEmpty()) {
                    val why = LookHost.names(handle, TestNames.block(wants, density), TestNames.FLOOR_DP)
                    check(why.isEmpty()) { why }
                    named += wants.size
                    handed = true
                }
            }
            if (!handed && whole(drew)) break
            drew = LookHost.draw(handle, camera, DRAW_SECONDS)
            check(drew.isNotEmpty()) { "the host map drew nothing: ${LookHost.lastError()}" }
        }
        return if (named > 0) "$drew; $named names' sprites handed" else drew
    }

    /**
     * Whether the draw that said [notes] drew its frame whole: complete, no world tile wanted or held back for room (a
     * frame final over a coarser ancestor in its place) and no cell bare.
     */
    private fun whole(notes: String): Boolean {
        val world = WORLD.find(notes) ?: return false
        val held = HELD.find(notes)?.groupValues?.get(1) ?: return false
        return notes.contains("complete yes") && world.groupValues[3] == "0" && world.groupValues[4] == "0" &&
            held == "0"
    }

    private companion object {
        var sequence = 0L

        /** MapCore.PackRef's world kinds (tiles::PackKind): a world pack's, and the globe's. */
        const val WORLD_MID = 3
        const val WORLD_BASE = 4

        /** WorldFetch.GLOBE: the globe's file in the world's folder. */
        const val GLOBE = "world-globe.atlas"

        /**
         * The longest a draw lasts, seconds, and the most draws a frame gets after its first (each hands the names it
         * wants, or carries it on to whole).
         */
        const val DRAW_SECONDS = 300.0
        const val ROUNDS = 12

        /** MapFrame::set_packs' line for a pack it took ("<id>: <why not>" for one it did not). */
        val OPENED = Regex(""".* (opened|listed|kept)""")

        /** The world's part of the tiles line (scene::World::tiles_line). */
        val WORLD = Regex("""world (\d+) drawn finest (\d+) wanted (\d+) bare (\d+)""")

        /** The world heap's tiles held back for room, its notes' last word before the world's part (scene::World). */
        val HELD = Regex(""" held (\d+) world \d+ drawn finest""")
    }
}
