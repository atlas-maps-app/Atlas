package app.atlas.mapcorecheck.load

import app.atlas.mapcore.Native
import app.atlas.tiles.WindowsIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Windows the core builds from a region's windows' data (`packer windows`), as the phone builds them from a pack's: the
 * data file -Datlas.windows names (`<id>.windows`, its index `<id>.windows.idx` beside it, read by the phone's own
 * WindowsIndex), each window's bytes held to the index's SHA-256, unpacked and built by the core through the façade
 * (`MapCore.Window.build`, JNI buildWindow) with no refusal: the Westerkerk's window when the region holds it, the
 * three biggest, and twenty more across the region. And each element of a window is the core's own form of it, byte
 * for byte, as osm/Diff.cpp writes an element of Overpass's diff (`MapCore.Window.merge`): so the phone's merges keep
 * a pack's data Overpass's answer. On the Mac, beside an extract's windows (Noord-Holland's).
 */
class WindowPackBuildTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getProperty("atlas.mapcore.host")
            assumeTrue("no host build (-Datlas.mapcore.host)", path != null)
            assertNull(Native.load(path))
        }
    }

    private val data = System.getProperty("atlas.windows")?.let(::File)

    /** Window [e]'s answer from the data file, its bytes held to the index's SHA-256. */
    private fun answer(file: RandomAccessFile, e: WindowsIndex.Entry): ByteArray {
        val member = ByteArray(e.length)
        file.seek(e.offset)
        file.readFully(member)
        assertTrue("14/${e.x}/${e.y}: its bytes are not the index's", MessageDigest.getInstance("SHA-256").digest(member).contentEquals(e.sha256))
        return GZIPInputStream(member.inputStream()).use { it.readBytes() }
    }

    private fun JsonObject.text(k: String) = this[k]!!.jsonPrimitive.content

    /** An attribute's value as Overpass's XML writes it. */
    private fun xml(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            '\n' -> append("&#10;")
            '\t' -> append("&#9;")
            '\r' -> append("&#13;")
            else -> append(c)
        }
    }

    /** Element [o] of an answer as Overpass's XML prints it under `out geom`, its numbers as the answer's text has them. */
    private fun element(o: JsonObject, b: StringBuilder) {
        val type = o.text("type")
        b.append("  <").append(type).append(" id=\"").append(o.text("id")).append('"')
        if (type == "node") b.append(" lat=\"").append(o.text("lat")).append("\" lon=\"").append(o.text("lon")).append('"')
        b.append(">\n")
        (o["bounds"] as? JsonObject)?.let { x ->
            b.append("    <bounds minlat=\"${x.text("minlat")}\" minlon=\"${x.text("minlon")}\" maxlat=\"${x.text("maxlat")}\" maxlon=\"${x.text("maxlon")}\"/>\n")
        }
        if (type == "way") {
            val points = o["geometry"]!!.jsonArray.map { it.jsonObject }
            o["nodes"]!!.jsonArray.forEachIndexed { i, n ->
                b.append("    <nd ref=\"${n.jsonPrimitive.content}\" lat=\"${points[i].text("lat")}\" lon=\"${points[i].text("lon")}\"/>\n")
            }
        }
        for (m in (o["members"]?.jsonArray ?: emptyList()).map { it.jsonObject }) {
            b.append("    <member type=\"${m.text("type")}\" ref=\"${m.text("ref")}\" role=\"${xml(m.text("role"))}\"")
            when (m.text("type")) {
                "node" -> b.append(" lat=\"${m.text("lat")}\" lon=\"${m.text("lon")}\"/>\n")
                "way" -> {
                    b.append(">\n")
                    for (p in m["geometry"]!!.jsonArray.map { it.jsonObject }) b.append("      <nd lat=\"${p.text("lat")}\" lon=\"${p.text("lon")}\"/>\n")
                    b.append("    </member>\n")
                }
                else -> b.append("/>\n")
            }
        }
        for ((k, v) in (o["tags"] as? JsonObject).orEmpty()) b.append("    <tag k=\"${xml(k)}\" v=\"${xml(v.jsonPrimitive.content)}\"/>\n")
        b.append("  </").append(type).append(">\n")
    }

    @Test
    fun aPacksElementsAreTheCoresOwnFormOfThemByteForByte() {
        assumeTrue("a region's windows' data (-Datlas.windows)", data != null && data.isFile)
        val index = WindowsIndex.read(File(data!!.path + ".idx").readBytes())
        val all = (0 until index.size).map { index[it] }
        // the windows spread across the region, and the Westerkerk's, up to 10 MB of JSON each
        val picked = (listOfNotNull(index.find(8414, 5384)) + (0 until 40).map { all[it * all.size / 40] }).distinctBy { it.x to it.y }
        val dir = Files.createTempDirectory("atlas-window-forms").toFile()
        val kinds = sortedMapOf<String, Int>()
        var windows = 0
        try {
            RandomAccessFile(data, "r").use { file ->
                for (e in picked) {
                    val ours = String(answer(file, e), Charsets.UTF_8)
                    if (ours.length > 10_000_000) continue
                    val elements = Json.parseToJsonElement(ours).jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
                    // a member way of one node prints no points, which Overpass's XML cannot say
                    if (elements.size < 2 || elements.any { o -> o["members"]?.jsonArray?.any { m -> m.jsonObject["geometry"]?.jsonArray?.isEmpty() == true } == true }) continue
                    // each element named again in its own form, the last one deleted: the merge keeps an element's bytes
                    // where its new form, as the core writes it, is them, and writes the core's form where it is not
                    val diff = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" generator=\"Overpass API 0.7.62.11 87bfad18\">\n")
                    diff.append("<meta osm_base=\"2026-10-27T21:00:00Z\"/>\n\n")
                    for ((i, o) in elements.withIndex()) {
                        kinds.merge(o.text("type"), 1, Int::plus)
                        for (m in (o["members"]?.jsonArray ?: emptyList())) kinds.merge("${m.jsonObject.text("type")} member", 1, Int::plus)
                        val one = StringBuilder().also { element(o, it) }
                        if (i < elements.size - 1) diff.append("<action type=\"modify\">\n<old>\n").append(one).append("</old>\n<new>\n").append(one).append("</new>\n</action>\n")
                        else diff.append("<action type=\"delete\">\n<old>\n").append(one).append("</old>\n</action>\n")
                    }
                    diff.append("</osm>\n")
                    val kept = File(dir, "kept.json").apply { writeText(ours) }
                    val merged = File(dir, "merged.json")
                    val bytes = diff.toString().toByteArray()
                    assertTrue(Native.mergeWindow(14, e.x, e.y, kept.path, ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() as ByteBuffer, merged.path))
                    val text = merged.readText()
                    val start = ours.indexOf("\"elements\": [")
                    val last = ours.lastIndexOf(",\n{\n  \"type\": ")
                    assertEquals("14/${e.x}/${e.y}", ours.substring(start, last) + "\n\n  ]\n}\n", text.substring(text.indexOf("\"elements\": [")))
                    windows++
                }
            }
        } finally {
            dir.deleteRecursively()
        }
        assertTrue("$windows windows", windows >= 10)
        println("window forms: $windows windows, every element the core's own form of it: $kinds")
    }

    @Test
    fun windowsBuildFromAPacksDataWithNoRefusal() {
        assumeTrue("a region's windows' data (-Datlas.windows)", data != null && data.isFile)
        val index = WindowsIndex.read(File(data!!.path + ".idx").readBytes())
        assertEquals(14, index.level)
        assertEquals(WindowsIndex.QUERY, index.query)
        assertEquals(data.length(), index.dataBytes)
        val all = (0 until index.size).map { index[it] }
        // the Westerkerk's (52.3745, 4.8840), the biggest, and the rest spread across the region
        val westerkerk = index.find(8414, 5384)
        val picked = (listOfNotNull(westerkerk) + all.sortedByDescending { it.length }.take(3) + (0 until 20).map { all[it * all.size / 20] })
            .distinctBy { it.x to it.y }
        val dir = Files.createTempDirectory("atlas-window-packs").toFile()
        try {
            RandomAccessFile(data, "r").use { file ->
                for (e in picked) {
                    val json = File(dir, "14-${e.x}-${e.y}.json").apply { writeBytes(answer(file, e)) }
                    val pack = File(dir, "14-${e.x}-${e.y}.atlas")
                    val t0 = System.nanoTime()
                    val said = Native.buildWindow(14, e.x, e.y, json.path, pack.path)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    assertEquals("14/${e.x}/${e.y}: the core refused its data", "", said)
                    assertTrue(pack.isFile)
                    println("window 14/${e.x}/${e.y}: ${e.length} bytes gzipped, ${json.length()} of JSON, a pack of ${pack.length()} bytes in $ms ms")
                    json.delete()
                    pack.delete()
                }
            }
        } finally {
            dir.deleteRecursively()
        }
        if (data.name.startsWith("noord-holland")) assertNotNull("Noord-Holland's windows hold the Westerkerk's", westerkerk)
        println("window packs: ${picked.size} of ${index.size} windows built from ${data.name}")
    }
}
