package app.atlas.mapcorecheck.osm

import app.atlas.anchor.AnchorCases
import app.atlas.render.py.PyJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The Overpass reader's test entry point in the core's host build (`CP/jni/TestOsm.cpp`), loaded from
 * `-Datlas.mapcore.host` (the library :mapcorecheck's coreHost task builds).
 */
internal object OsmHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 1

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's osm entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /** The document at [path] as the core reads it: `sha256=… elements=… nodes=… ways=… relations=… ms=…`, or `error=…`. */
    @JvmStatic external fun hash(path: String): String
}

/**
 * Stage P's input held to the port (ENGINE.md step 3): every value the core reads of a region's raw.json
 * (`CP/osm/Overpass.hpp`) is what the port's loaders read through `json.load` (PyJson): each element's type, id, position
 * (each coordinate's bits, an integer's made a float as Python makes it), tags in their order, nodes, geometry and members.
 * Both sides hash the same canonical bytes (TestOsm.cpp's layout), over the raw.json the port unpacks from the bundled
 * Westerkerk's pack and the canal ring's.
 */
class OverpassParityTest {
    /** TestOsm.cpp's canonical bytes of the elements PyJson read, little-endian. */
    private class Canonical {
        val out = ByteArrayOutputStream(1 shl 20)
        fun u8(v: Int) = out.write(v)
        fun u32(v: Int) = le(v.toLong(), 4)
        fun i64(v: Long) = le(v, 8)
        fun real(v: Double) = le(java.lang.Double.doubleToRawLongBits(v), 8)
        fun text(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u32(b.size)
            out.write(b)
        }
        private fun le(v: Long, n: Int) {
            for (k in 0 until n) out.write((v ushr (8 * k)).toInt() and 0xFF)
        }
    }

    /** Python's float() of what json.load made of a coordinate: an int (of any size) or a float. */
    private fun float(v: Any?): Double = when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is BigInteger -> v.toDouble()
        else -> error("a coordinate of ${v?.let { it::class.simpleName }}")
    }

    private fun long(v: Any?): Long = when (v) {
        is Int -> v.toLong()
        is Long -> v
        else -> error("an id of ${v?.let { it::class.simpleName }}")
    }

    private fun code(type: String?): Int = when (type) {
        "node", null -> 0
        "way" -> 1
        "relation" -> 2
        else -> 3
    }

    @Suppress("UNCHECKED_CAST")
    private fun points(c: Canonical, list: Any?) {
        val l = list as List<Map<String, Any?>>
        c.u32(l.size)
        for (p in l) {
            c.real(float(p["lat"]))
            c.real(float(p["lon"]))
        }
    }

    /** The canonical bytes' SHA-256 and the elements' counts, as the core's entry point prints them (without the time). */
    @Suppress("UNCHECKED_CAST")
    private fun portHash(raw: File): String {
        val doc = PyJson.load(raw) as Map<String, Any?>
        val elements = doc["elements"] as List<Map<String, Any?>>
        val c = Canonical()
        val kinds = IntArray(4)
        for (e in elements) {
            val type = e["type"] as String
            kinds[code(type)]++
            c.text(type)
            val hasAt = "lat" in e && "lon" in e
            val tags = e["tags"] as Map<String, String>?
            c.u8(
                (if ("id" in e) 1 else 0) or (if (hasAt) 2 else 0) or (if (tags != null) 4 else 0) or
                    (if ("nodes" in e) 8 else 0) or (if ("geometry" in e) 16 else 0) or (if ("members" in e) 32 else 0),
            )
            if ("id" in e) c.i64(long(e["id"]))
            if (hasAt) {
                c.real(float(e["lat"]))
                c.real(float(e["lon"]))
            }
            if (tags != null) {
                c.u32(tags.size)
                for ((k, v) in tags) {
                    c.text(k)
                    c.text(v)
                }
            }
            if ("nodes" in e) {
                val nodes = e["nodes"] as List<Any?>
                c.u32(nodes.size)
                for (n in nodes) c.i64(long(n))
            }
            if ("geometry" in e) points(c, e["geometry"])
            if ("members" in e) {
                val members = e["members"] as List<Map<String, Any?>>
                c.u32(members.size)
                for (m in members) {
                    c.u8(code(m["type"] as String?))
                    c.i64(long(m["ref"]))
                    c.u8((if ("role" in m) 1 else 0) or (if ("geometry" in m) 2 else 0))
                    c.text((m["role"] as String?) ?: "")
                    if ("geometry" in m) points(c, m["geometry"]) else c.u32(0)
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(c.out.toByteArray())
        return "sha256=${digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }} elements=${elements.size} " +
            "nodes=${kinds[0]} ways=${kinds[1]} relations=${kinds[2]}"
    }

    @Test
    fun theCoreReadsEveryValueOfTheRegionsRawJsonAsThePortDoes() {
        val regions = listOfNotNull(AnchorCases.westerkerk(), AnchorCases.grachten())
        for (region in regions) {
            val raw = File(region.dir, "raw.json")
            assertTrue("${region.id}: no raw.json in ${region.dir}", raw.isFile)
            val t0 = System.nanoTime()
            val port = portHash(raw)
            val portMs = (System.nanoTime() - t0) / 1_000_000
            val core = OsmHost.hash(raw.path)
            println("overpass ${region.id}: ${raw.length()} bytes; core $core; the port's json.load $portMs ms")
            assertEquals("${region.id}: the core's elements are not the port's", port, core.substringBefore(" ms="))
        }
        assertTrue("the Westerkerk's raw.json is read", regions.isNotEmpty())
    }
}
