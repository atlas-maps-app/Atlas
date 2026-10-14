package app.atlas.packer

import app.atlas.tiles.PackCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/** A tiny but valid PMTiles v3 archive: header, an empty root directory, metadata, no tiles. */
object FakePmTiles {
    fun write(file: File, minZoom: Int = 0, maxZoom: Int = 14, bounds: DoubleArray = doubleArrayOf(5.73, 49.44, 6.53, 50.18), metadata: String = "{}", padding: Int = 0): File {
        val meta = metadata.toByteArray()
        val root = byteArrayOf(0)
        val h = ByteArray(127)
        "PMTiles".toByteArray().copyInto(h)
        h[7] = 3
        fun u64(p: Int, v: Long) { for (i in 0 until 8) h[p + i] = (v ushr (8 * i)).toByte() }
        fun i32(p: Int, v: Int) { for (i in 0 until 4) h[p + i] = (v ushr (8 * i)).toByte() }
        u64(8, 127)
        u64(16, root.size.toLong())
        u64(24, 128)
        u64(32, meta.size.toLong())
        u64(40, 128L + meta.size)
        u64(48, 0)
        u64(56, 128L + meta.size)
        u64(64, padding.toLong())
        h[96] = 1
        h[97] = 1
        h[98] = 2
        h[99] = 1
        h[100] = minZoom.toByte()
        h[101] = maxZoom.toByte()
        i32(102, Math.round(bounds[0] * 1e7).toInt())
        i32(106, Math.round(bounds[1] * 1e7).toInt())
        i32(110, Math.round(bounds[2] * 1e7).toInt())
        i32(114, Math.round(bounds[3] * 1e7).toInt())
        val out = ByteArrayOutputStream()
        out.write(h)
        out.write(root)
        out.write(meta)
        out.write(ByteArray(padding) { (it * 31).toByte() })
        file.writeBytes(out.toByteArray())
        return file
    }
}

class ManifestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://github.com/atlas-maps-app/Atlas/releases/download/packs-20261101"

    private fun regions(dir: File): File = File(dir, "regions.json").apply {
        writeText(
            """{"regions": [
              {"id": "luxembourg", "name": "Luxembourg", "country": "LU", "url": "https://download.geofabrik.de/europe/luxembourg-latest.osm.pbf", "bbox": [5.733, 49.4455, 6.5322, 50.185]},
              {"id": "tuscany", "name": "Tuscany", "country": "IT", "bbox": [9.68, 42.23, 12.38, 44.48], "clip": true},
              {"id": "monaco", "name": "Monaco", "country": "MC"}
            ]}""",
        )
    }

    private fun placesFiles(dir: File, id: String, bytes: Int = 1000) {
        File(dir, "$id.places.db.gz").writeBytes(ByteArray(bytes) { it.toByte() })
        File(dir, "$id.places.json").writeText(
            """{"file": "$id.places.db.gz", "osm_timestamp": "2026-10-13T20:21:31Z", "bbox": [5.7, 49.4, 6.5, 50.2], "raw_bytes": 5000, "schema": 1, "places": 299711, "trees": 78236}""",
        )
    }

    private fun packs(o: JsonObject): List<JsonObject> = o["packs"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun regionsAreRead() {
        val r = Manifest.readRegions(regions(tmp.root))
        assertEquals(listOf("luxembourg", "tuscany", "monaco"), r.map { it.id })
        assertTrue(r[1].clip)
        assertFalse(r[0].clip)
        assertEquals(9.68, r[1].bbox!!.west, 0.0)
        assertNull(r[2].bbox)
        assertEquals("LU", r[0].country)
    }

    @Test
    fun theRepositoryRegionsAreValid() {
        val file = File(System.getProperty("atlas.testdata"), "../packs/regions.json")
        val r = Manifest.readRegions(file)
        assertEquals(
            listOf("netherlands", "belgium", "luxembourg", "new-york", "paris", "tuscany", "norway", "greater-london"),
            r.map { it.id },
        )
        for (region in r) {
            assertTrue(region.id, region.url!!.startsWith("https://download.geofabrik.de/") && region.url!!.endsWith("-latest.osm.pbf"))
            val b = region.bbox!!
            assertTrue(region.id, b.west < b.east && b.south < b.north)
            assertTrue(region.id, region.country!!.length == 2)
        }
        assertEquals(listOf("tuscany"), r.filter { it.clip }.map { it.id })
    }

    @Test
    fun aRegionsFilesBecomeAnEntryWithUrlsSizesAndChecksums() {
        val dir = tmp.newFolder("out")
        FakePmTiles.write(File(dir, "luxembourg.pmtiles"), maxZoom = 14, metadata = """{"version":"3.16.0","planetiler:osm:osmosisreplicationtime":"2026-10-12T20:21:35Z"}""")
        placesFiles(dir, "luxembourg")
        val list = Manifest.build(dir, base, Manifest.readRegions(regions(tmp.root)), now = Instant.parse("2026-11-01T04:00:00Z"))
        assertEquals(1, list["format"]!!.jsonPrimitive.int)
        assertEquals("2026-11-01T04:00:00Z", list["generated"]!!.jsonPrimitive.content)
        assertTrue(list["attribution"]!!.jsonPrimitive.content.contains("OpenStreetMap contributors"))
        val e = packs(list).single()
        assertEquals("luxembourg", e["id"]!!.jsonPrimitive.content)
        assertEquals("Luxembourg", e["name"]!!.jsonPrimitive.content)
        assertEquals("LU", e["country"]!!.jsonPrimitive.content)
        assertEquals(listOf(5.733, 49.4455, 6.5322, 50.185), e["bbox"]!!.jsonArray.map { it.jsonPrimitive.double })
        // the places sidecar's time wins over the tiles' metadata
        assertEquals("2026-10-13T20:21:31Z", e["osm_timestamp"]!!.jsonPrimitive.content)
        assertEquals(0, e["minzoom"]!!.jsonPrimitive.int)
        assertEquals(14, e["maxzoom"]!!.jsonPrimitive.int)
        assertEquals("packs-20261101", e["release"]!!.jsonPrimitive.content)
        val files = e["files"]!!.jsonArray.map { it.jsonObject }
        val tiles = files.first { it["kind"]!!.jsonPrimitive.content == "tiles" }
        assertEquals("$base/luxembourg.pmtiles", tiles["url"]!!.jsonPrimitive.content)
        assertEquals(File(dir, "luxembourg.pmtiles").length(), tiles["bytes"]!!.jsonPrimitive.long)
        assertEquals(PackFiles.sha256(File(dir, "luxembourg.pmtiles")), tiles["sha256"]!!.jsonPrimitive.content)
        assertEquals("openmaptiles 3.16.0", tiles["schema"]!!.jsonPrimitive.content)
        val places = files.first { it["kind"]!!.jsonPrimitive.content == "places" }
        assertEquals("$base/luxembourg.places.db.gz", places["url"]!!.jsonPrimitive.content)
        assertEquals(1000L, places["bytes"]!!.jsonPrimitive.long)
        assertEquals("gzip", places["compression"]!!.jsonPrimitive.content)
        assertEquals(5000L, places["raw_bytes"]!!.jsonPrimitive.long)
        assertEquals(1, places["schema"]!!.jsonPrimitive.int)
        assertEquals(299711L, places["places"]!!.jsonPrimitive.long)
        // and the region's own entry is beside its files, for the publishing job
        assertEquals(e, Json.parseToJsonElement(File(dir, "luxembourg.pack.json").readText()))
        // the app reads what the packer writes
        val pack = PackCatalog.parse(Manifest.pretty(list)).packs.single()
        assertEquals("luxembourg", pack.id)
        assertEquals(14, pack.maxZoom)
        assertEquals("$base/luxembourg.pmtiles", pack.tiles!!.url)
        assertEquals(5000L, pack.places!!.rawBytes)
        assertEquals(File(dir, "luxembourg.pmtiles").length() + 1000, pack.downloadBytes)
        assertEquals(Instant.parse("2026-10-13T20:21:31Z"), pack.osmTime)
    }

    @Test
    fun theTilesTimeIsUsedWithoutPlaces() {
        val dir = tmp.newFolder("tiles-only")
        FakePmTiles.write(File(dir, "monaco.pmtiles"), metadata = """{"planetiler:osm:osmosisreplicationtime":"2026-10-12T20:21:35Z"}""")
        val e = packs(Manifest.build(dir, base, Manifest.readRegions(regions(tmp.root)))).single()
        assertEquals("2026-10-12T20:21:35Z", e["osm_timestamp"]!!.jsonPrimitive.content)
        // no bbox in regions.json: the tiles' bounds
        assertEquals(5.73, e["bbox"]!!.jsonArray[0].jsonPrimitive.double, 1e-7)
        assertEquals(1, e["files"]!!.jsonArray.size)
    }

    @Test
    fun theListMergesThePreviousOneAndOtherJobsEntries() {
        // the publishing job: entries other jobs built, and last month's list
        val dir = tmp.newFolder("publish")
        File(dir, "tuscany.pack.json").writeText("""{"id": "tuscany", "name": "Tuscany", "files": [], "release": "packs-20261101"}""")
        val previous = Json.parseToJsonElement(
            """{"format": 1, "packs": [
                {"id": "luxembourg", "name": "Luxembourg", "files": [], "release": "packs-20261001"},
                {"id": "tuscany", "name": "Tuscany", "files": [], "release": "packs-20261001"},
                {"id": "atlantis", "name": "Atlantis", "files": [], "release": "packs-20261001"}
            ]}""",
        ).jsonObject
        val merged = Manifest.build(dir, base, Manifest.readRegions(regions(tmp.root)), previous)
        val ps = packs(merged)
        // in regions.json order; a region no longer listed is dropped
        assertEquals(listOf("luxembourg", "tuscany"), ps.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("packs-20261001", ps[0]["release"]!!.jsonPrimitive.content)
        assertEquals("packs-20261101", ps[1]["release"]!!.jsonPrimitive.content)
        // without regions.json everything is kept, by id
        val all = packs(Manifest.build(dir, base, null, previous))
        assertEquals(listOf("atlantis", "luxembourg", "tuscany"), all.map { it["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun bigFilesGoUpInParts() {
        val dir = tmp.newFolder("big")
        FakePmTiles.write(File(dir, "luxembourg.pmtiles"), padding = 10_000)
        placesFiles(dir, "luxembourg", bytes = 2500)
        val whole = PackFiles.sha256(File(dir, "luxembourg.pmtiles"))
        val size = File(dir, "luxembourg.pmtiles").length()
        val e = packs(Manifest.build(dir, base, null, splitBytes = 4096)).single()
        val tiles = e["files"]!!.jsonArray.map { it.jsonObject }.first { it["kind"]!!.jsonPrimitive.content == "tiles" }
        assertNull(tiles["url"])
        assertEquals(whole, tiles["sha256"]!!.jsonPrimitive.content)
        assertEquals(size, tiles["bytes"]!!.jsonPrimitive.long)
        val parts = (tiles["parts"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("luxembourg.pmtiles.000", "luxembourg.pmtiles.001", "luxembourg.pmtiles.002"), parts.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf(4096L, 4096L, size - 8192), parts.map { it["bytes"]!!.jsonPrimitive.long })
        assertEquals("$base/luxembourg.pmtiles.001", parts[1]["url"]!!.jsonPrimitive.content)
        assertFalse(File(dir, "luxembourg.pmtiles").exists())
        // the parts put back together are the file
        val joined = parts.map { File(dir, it["name"]!!.jsonPrimitive.content).readBytes() }.reduce { a, b -> a + b }
        assertEquals(whole, java.security.MessageDigest.getInstance("SHA-256").digest(joined).joinToString("") { "%02x".format(it) })
        for (p in parts) assertEquals(PackFiles.sha256(File(dir, p["name"]!!.jsonPrimitive.content)), p["sha256"]!!.jsonPrimitive.content)
        // the small file stays whole
        val places = e["files"]!!.jsonArray.map { it.jsonObject }.first { it["kind"]!!.jsonPrimitive.content == "places" }
        assertEquals("$base/luxembourg.places.db.gz", places["url"]!!.jsonPrimitive.content)
        // run again on the parts: the entry already written stands
        assertEquals(e, packs(Manifest.build(dir, base, null)).single())
        // and the app sees the parts
        val tilesFile = PackCatalog.parse(Manifest.pretty(Manifest.build(dir, base, null))).packs.single().tiles!!
        assertTrue(tilesFile.isSplit)
        assertEquals(parts.map { it["url"]!!.jsonPrimitive.content }, tilesFile.parts.map { it.url })
        assertEquals(size, tilesFile.bytes)
    }

    @Test
    fun partNamesUseAsciiDigitsWhateverTheLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            // Arabic digits by default: String.format("%03d") would write ٠٠٠
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val f = File(tmp.newFolder("ar"), "x.pmtiles").apply { writeBytes(ByteArray(10)) }
            assertEquals(listOf("x.pmtiles.000", "x.pmtiles.001", "x.pmtiles.002"), PackFiles.split(f, 4).map { it.name })
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun releaseTagsAndBadBases() {
        assertEquals("packs-20261101", Manifest.releaseOf(base))
        assertNull(Manifest.releaseOf("https://example.org/packs"))
        try {
            Manifest.build(tmp.root, "file:///tmp", null)
            org.junit.Assert.fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("http"))
        }
    }
}

class MainTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun theCommandsRunEndToEnd() {
        val pbf = TestPbf(
            listOf(TNode(1, 49.61, 6.13, mapOf("amenity" to "pharmacy", "name" to "Pharmacie du Globe")), TNode(2, 49.62, 6.14), TNode(3, 49.62, 6.15)),
            listOf(TWay(10, listOf(2, 3), mapOf("highway" to "residential", "name" to "Rue du Fort"))),
        ).write(tmp.newFile("lux.osm.pbf"))
        val out = tmp.newFolder("out")
        val lines = ArrayList<String>()
        assertEquals(0, run(listOf("places", pbf.path, File(out, "luxembourg.places.db").path, "--name", "Luxembourg", "--threads", "2", "--langs", "fr,de"), lines::add))
        assertTrue(lines.last { it.startsWith("luxembourg") }.contains("2 places"))
        FakePmTiles.write(File(out, "luxembourg.pmtiles"))
        lines.clear()
        assertEquals(0, run(listOf("manifest", out.path, "https://example.org/releases/download/packs-1"), lines::add))
        val list = Json.parseToJsonElement(File(out, "packs.json").readText()).jsonObject
        val entry = list["packs"]!!.jsonArray.single().jsonObject
        assertEquals("luxembourg", entry["id"]!!.jsonPrimitive.content)
        assertEquals(2, entry["files"]!!.jsonArray.size)
        lines.clear()
        assertEquals(0, run(listOf("info", pbf.path, "--count"), lines::add))
        val info = Json.parseToJsonElement(lines.joinToString("\n")).jsonObject
        assertEquals(3L, info["nodes"]!!.jsonPrimitive.long)
        assertEquals(1L, info["ways"]!!.jsonPrimitive.long)
    }

    @Test
    fun badCommandLinesSayWhy() {
        fun usage(vararg args: String): String = try {
            run(args.toList()) {}
            "no error"
        } catch (e: UsageException) {
            e.message!!
        }
        assertTrue(usage("places", "only-one").contains("2 file arguments"))
        assertTrue(usage("places", "a", "b", "--frobnicate").contains("--frobnicate"))
        assertTrue(usage("places", "a", "b", "--bbox", "1,2,0,4").contains("bad box"))
        assertTrue(usage("nonsense").contains("unknown command"))
        assertTrue(usage("manifest", "a", "b", "--split-bytes", "-3").contains("split"))
        // a typo in --threads must not quietly fall back to the default
        assertTrue(usage("places", "a", "b", "--threads", "four").contains("--threads"))
        assertTrue(usage("places", "a", "b", "--threads", "0").contains("--threads"))
        assertEquals(2, run(emptyList()) {})
        assertEquals(0, run(listOf("help")) {})
    }
}
