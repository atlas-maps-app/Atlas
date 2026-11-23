package app.atlas.ui.mine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The unit tests' guard holds names (root build.gradle.kts, ci/test-hosts): a host written as numbers is not a name, so
 * the guard does not hold it. This scan does: no test source of any module may name an address outside the loopback.
 *
 * It knows spellings, not meanings: an IPv4 address where a host stands (after `//`, `@` or a quote, before `:`, `/`
 * or a quote: a URL, `InetSocketAddress("…", 443)`); the four bytes of `InetAddress.getByAddress(…, byteArrayOf(…))`;
 * an IPv6 address in a URL's brackets or a string of its own. A version written like an address ("Overpass API
 * 0.7.62.11") stands where no host does, and passes. An address put together at run time it cannot see.
 */
class TestAddressesTest {
    private val android = File(System.getProperty("atlas.repo") ?: "../..", "app/android")

    /** Every module's test sources: `src/test*` and `src/androidTest*`, Kotlin and Java. */
    private fun testSources(): List<File> = android.listFiles().orEmpty().filter { File(it, "src").isDirectory }.flatMap { module ->
        File(module, "src").listFiles().orEmpty().filter { it.isDirectory && (it.name.startsWith("test") || it.name.startsWith("androidTest")) }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
    }.sortedBy { it.path }

    /** The addresses outside the loopback written in [line], as written. */
    internal fun outside(line: String): List<String> {
        val found = ArrayList<String>()
        for (m in IPV4.findAll(line)) {
            val octets = m.groupValues[1].split('.').map { it.toInt() }
            if (octets.all { it in 0..255 } && octets[0] != 127) found += m.groupValues[1]
        }
        if ("getByAddress" in line) for (m in BYTES.findAll(line)) {
            val bytes = (1..4).map { m.groupValues[it].toInt() and 0xFF }
            if (bytes[0] != 127) found += bytes.joinToString(".")
        }
        for (m in IPV6.findAll(line)) {
            val text = m.groupValues[1]
            val groups = ipv6(text) ?: continue
            if (groups != LOOPBACK6) found += text
        }
        return found
    }

    /** [text]'s eight groups where it is an IPv6 address written in full or with one `::`; null where it is none. */
    private fun ipv6(text: String): List<Int>? {
        if (text.count { it == ':' } < 2 || text.none { it.isLetterOrDigit() }) return null
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(s: String) = if (s.isEmpty()) emptyList() else s.split(':')
        val head = groups(halves[0])
        val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
        if ((head + tail).any { it.isEmpty() || it.length > 4 }) return null
        val missing = 8 - head.size - tail.size
        if (halves.size == 1 && missing != 0 || halves.size == 2 && missing < 1) return null
        return (head + List(missing) { "0" } + tail).map { it.toInt(16) }
    }

    @Test
    fun noTestSourceNamesAnAddressOutsideTheLoopback() {
        val sources = testSources()
        assertTrue("the scan reads every module's tests: ${sources.size} files", sources.size > 100)
        assertTrue("it reads this module's and the providers'", sources.any { "/app/src/test/" in it.path } && sources.any { "/providers/src/test/" in it.path })
        val hits = sources.flatMap { f ->
            f.readLines().withIndex().flatMap { (i, l) -> outside(l).map { "${f.relativeTo(android)}:${i + 1} $it" } }
        }
        assertEquals("addresses outside the loopback in test sources (the guard holds names only)", emptyList<String>(), hits)
    }

    /** The scan's own spellings, put together here so that this file names no address itself. */
    @Test
    fun theScanKnowsTheSpellingsItSaysItKnows() {
        val real = listOf("140", "82", "121", "4").joinToString(".")
        val q = '"'
        val by = "getBy" + "Address"
        assertEquals(listOf(real), outside("val url = ${q}http://$real/x$q"))
        assertEquals(listOf(real), outside("InetSocketAddress($q$real$q, 443)"))
        assertEquals(listOf(real), outside("${q}user@$real:22$q"))
        assertEquals(listOf(real), outside("InetAddress.$by(${q}github.com$q, byteArrayOf(140, 82, 121, 4))"))
        assertEquals(listOf(real), outside("InetAddress.$by(byteArrayOf(-116, 82, 121, 4))"))
        val v6 = listOf("2606", "4700", "", "1111").joinToString(":")
        assertEquals(listOf(v6), outside("${q}http://[$v6]:80/$q"))
        assertEquals(listOf(v6), outside("InetAddress.getByName($q$v6$q)"))
        // the loopback, a version, a time and a coordinate pass
        assertEquals(emptyList<String>(), outside("${q}http://127.0.0.1:8080/$q byteArrayOf(127, 0, 0, 1) $by"))
        assertEquals(emptyList<String>(), outside("${q}http://[::1]:80/$q ${q}0:0:0:0:0:0:0:1$q split($q::$q)"))
        assertEquals(emptyList<String>(), outside("generator=\\${q}Overpass API 0.7.62.11 87bfad18\\$q ${q}19:30:00$q LatLon(52.37, 4.88)"))
    }

    private companion object {
        val IPV4 = Regex("""(?<=//|@|")((?:\d{1,3}\.){3}\d{1,3})(?=[:/"])""")
        val BYTES = Regex("""byteArrayOf\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""")
        val IPV6 = Regex("""(?<=//\[|")([0-9A-Fa-f:]*:[0-9A-Fa-f:]*)(?=[\]"])""")
        val LOOPBACK6 = List(7) { 0 } + 1
    }
}
