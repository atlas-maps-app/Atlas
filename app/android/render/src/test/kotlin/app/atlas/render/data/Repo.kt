package app.atlas.render.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/** Where the tests find the repo, its data and the Python oracle. */
object Repo {
    val root = File(System.getProperty("atlas.repo") ?: error("atlas.repo is not set"))
    val regions = File(root, "data/regions")
    val ams = File(root, "data/ams/raw.json")
    val python = File(root, "app/android/render/src/test/python")
    val oracle = File(root, "cache/oracle").also { it.mkdirs() }

    /** Runs a Python driver (PYTHONHASHSEED=0) and returns its standard output. */
    fun py(script: String, vararg args: String): String {
        val pb = ProcessBuilder(listOf("python3", File(python, script).path) + args)
        pb.environment()["PYTHONHASHSEED"] = "0"
        pb.redirectErrorStream(false)
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        if (p.waitFor() != 0) error("python3 $script failed: $err")
        return out.trim()
    }

    /** The regions that have a local `raw.json`, by name. */
    fun localRegions(): List<String> = regions.listFiles { f -> File(f, "raw.json").isFile }.orEmpty().map { it.name }.sorted()

    fun places(): JsonObject = Json.parseToJsonElement(py("places_json.py", root.path)) as JsonObject

    /** The same canonical digest digest_elements.py makes, from elements already read. */
    fun digest(elements: Sequence<OsmElement>): Pair<Int, String> {
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0
        val sb = StringBuilder()
        for (e in elements) {
            sb.setLength(0)
            JsonText.element(sb, e, fixed = 7)
            sb.append('\n')
            md.update(sb.toString().toByteArray(Charsets.UTF_8))
            n++
        }
        return n to md.digest().joinToString("") { "%02x".format(it) }
    }
}
