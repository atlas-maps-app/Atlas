package app.atlas.render

import app.atlas.render.py.PyCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Python oracle: runs a driver from src/test/python with python3 and PYTHONHASHSEED=0, with the repo's
 * render/ and data/regions/ on the path. Outputs that are files go under the repo's cache/oracle.
 */
object Oracle {
    val repo: File = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile
    val drivers: File = File(repo, "app/android/render/src/test/python")
    val out: File = File(repo, "cache/oracle").also { it.mkdirs() }
    private val json = Json { isLenient = false }

    /** Runs `python3 <driver> args…` and returns its standard output; fails with its standard error. */
    fun run(driver: String, vararg args: String, timeoutSec: Long = 600): String {
        val cmd = listOf("python3", File(drivers, driver).path) + args
        val pb = ProcessBuilder(cmd).directory(repo)
        pb.environment()["PYTHONHASHSEED"] = "0"
        pb.environment()["PYTHONPATH"] = listOf(File(repo, "render"), File(repo, "data/regions"), drivers).joinToString(File.pathSeparator)
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        val errFile = File.createTempFile("oracle", ".err")
        pb.redirectError(errFile)
        val p = pb.start()
        val text = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly(); error("oracle $driver timed out")
        }
        val err = errFile.readText(); errFile.delete()
        check(p.exitValue() == 0) { "oracle $cmd failed (${p.exitValue()}):\n$err" }
        return text
    }

    /** Runs a driver and parses its JSON output. */
    fun json(driver: String, vararg args: String): JsonElement = json.parseToJsonElement(run(driver, *args))

    /** The oracle's CPython: minor version and string hash algorithm. */
    val info: JsonObject by lazy { json("py_support.py", "info").jsonObject }

    /** Makes the port reproduce the oracle's CPython where versions differ. Call before every comparison. */
    fun configure() {
        PyCompat.minor = info["minor"]!!.jsonPrimitive.int
        PyCompat.sipRounds = when (info["hash"]!!.jsonPrimitive.content) {
            "siphash24" -> 2
            "siphash13" -> 1
            else -> error("unsupported hash algorithm ${info["hash"]}")
        }
    }

    /** A JSON value as the Kotlin value standing for the Python one: ints stay ints, floats are Doubles. */
    fun py(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.content.any { it == '.' || it == 'e' || it == 'E' } || e.content == "NaN" || e.content.endsWith("Infinity") ->
                e.content.toDouble()
            else -> e.content.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
                ?: java.math.BigInteger(e.content)
        }
        is JsonArray -> e.map { py(it) }
        is JsonObject -> LinkedHashMap<String, Any?>().also { m -> for ((k, v) in e) m[k] = py(v) }
    }

    /** A float sent as its IEEE bits. */
    fun bitsOf(e: JsonElement): Double = java.lang.Double.longBitsToDouble(e.jsonPrimitive.content.toLong())

    /** The Python run's elapsed seconds, as reported by a driver. */
    fun seconds(e: JsonElement?): Double = e?.jsonPrimitive?.content?.toDouble() ?: Double.NaN
}
