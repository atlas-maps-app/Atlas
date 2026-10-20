package app.atlas.mapcorecheck.likeness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The engine's own references (ENGINE.md, from step 1): its frames at fixed views and moments, the boards' views first,
 * stored per engine version in `likeness/engine.json` as the SHA-256 of each frame's pixels on each platform (each
 * lavapipe draws its own exact pixels). Every finished part is held to them: on a platform that has references of the
 * current version, every frame must come out the same, pixel for pixel, so a simplification that changes what the
 * boards look like fails here. The frames are the app's: its camera (no engine mode), every effect, at the phone's size.
 *
 * A deliberate change to what the engine draws is a new version: raise `version`, say in its `note` what changed, and
 * record this machine's frames with `-Datlas.engine.record=true`. A platform without frames of the current version
 * (CI's lavapipe-x86_64 after a new version) prints its own and leaves them in the report's `engine/engine.json`, to
 * paste in. Every run leaves its frames there as PNGs, to look at.
 */
class EngineRefsTest {
    @Test
    fun `the engine draws the frames its version stored`() {
        val required = System.getProperty("atlas.likeness.require") == "true"
        if (HostCore.load() == null) {
            check(!required) { "the engine's references need the host core: ${HostCore.unavailable}" }
            assumeTrue("the host core: ${HostCore.unavailable}", false)
        }
        val file = File(Likeness.cases, "engine.json")
        val refs = Likeness.json(file)
        val version = refs.str("version")
        val platform = Likeness.platform
        val out = File(Likeness.report, "engine").apply { mkdirs() }
        val drawn = LinkedHashMap<String, String>()
        val missing = ArrayList<String>()
        for (f in refs["frames"]!!.jsonArray) {
            val o = f.jsonObject
            val name = o.str("name")
            val frame = try {
                HostFrames.draw(o.str("pack"), o.str("view"), o.str("moment"), refs.str("size"), refs.str("mode"))
            } catch (e: CaseUnavailable) {
                check(!required) { "$name: ${e.message}" }
                missing.add("$name (${e.message})")
                continue
            }
            HostFrames.png(frame, File(out, "${name.replace(' ', '-')}.png"))
            drawn[name] = sha256(frame.rgba)
            println("engine ref $name: ${drawn[name]} (${frame.ms} ms)")
        }
        assumeTrue("no frame could be drawn here: $missing", drawn.isNotEmpty())
        val stored = refs["versions"]?.jsonObject?.get(version)?.jsonObject?.get("platforms")?.jsonObject?.get(platform)?.jsonObject
        val proposal = withFrames(refs, version, platform, drawn)
        File(out, "engine.json").writeText(Ratchet.format(proposal) + "\n")
        if (System.getProperty("atlas.engine.record") == "true") {
            check(missing.isEmpty()) { "every frame is recorded at once; not drawn: $missing" }
            file.writeText(Ratchet.format(proposal) + "\n")
            println("engine refs: version $version's frames on $platform recorded in $file")
            return
        }
        if (stored == null) {
            println("engine refs: version $version has no frames of $platform yet; this run's are in ${File(out, "engine.json")}")
            return
        }
        val changed = drawn.filter { (name, hash) -> stored[name]?.jsonPrimitive?.content != hash }.keys
        println("engine refs: ${drawn.size - changed.size} of ${drawn.size} frames as version $version stored them on $platform" +
            if (missing.isEmpty()) "" else "; not drawn here: $missing")
        assertTrue(
            "the engine no longer draws version $version's frames on $platform: $changed (this run's frames: $out). A deliberate " +
                "change is a new version (likeness/engine.json, recorded with -Datlas.engine.record=true); any other is a bug",
            changed.isEmpty(),
        )
    }

    /** [refs] with [drawn] as [platform]'s frames of [version] (the version's entry made if it is new). */
    private fun withFrames(refs: JsonObject, version: String, platform: String, drawn: Map<String, String>): JsonObject {
        val versions = refs["versions"]?.jsonObject ?: JsonObject(emptyMap())
        val entry = versions[version]?.jsonObject ?: JsonObject(mapOf("note" to JsonPrimitive("")))
        val platforms = LinkedHashMap(entry["platforms"]?.jsonObject ?: JsonObject(emptyMap()))
        platforms[platform] = JsonObject(drawn.mapValues { JsonPrimitive(it.value) })
        val e = LinkedHashMap(entry).apply { this["platforms"] = JsonObject(platforms) }
        val v = LinkedHashMap(versions).apply { this[version] = JsonObject(e) }
        return JsonObject(LinkedHashMap(refs).apply { this["versions"] = JsonObject(v) })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
