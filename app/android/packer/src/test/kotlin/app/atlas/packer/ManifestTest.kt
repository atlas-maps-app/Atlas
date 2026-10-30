package app.atlas.packer

import app.atlas.tiles.PackCatalog
import app.atlas.tiles.PackFile
import app.atlas.tiles.TilesIndex
import app.atlas.tiles.WindowsIndex
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
import java.io.File
import java.time.Instant

/** A small but real region pack: a pharmacy and a street around Luxembourg's centre, made by the packer's own `pbf` command. */
object FakeAtlas {
    fun write(file: File, id: String = file.name.removeSuffix(".atlas"), box: String = "6.0,49.5,6.3,49.7"): File {
        val pbf = TestPbf(
            listOf(TNode(1, 49.61, 6.13, mapOf("amenity" to "pharmacy", "name" to "Pharmacie du Globe")), TNode(2, 49.62, 6.14), TNode(3, 49.62, 6.15)),
            listOf(TWay(10, listOf(2, 3), mapOf("highway" to "residential", "name" to "Rue du Fort"))),
        ).write(File(file.parentFile, "$id.osm.pbf"))
        check(run(listOf("pbf", pbf.path, box, "--name", id, "--id", id, "--out", file.path)) {} == 0)
        pbf.delete()
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
        val file = File(System.getProperty("atlas.repo"), "app/android/packs/regions.json")
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
        // Norway's polygon holds some 4.4 million L14 windows: an index too big for the phone to download whole
        assertEquals(listOf("norway"), r.filter { !it.windows }.map { it.id })
    }

    @Test
    fun aRegionsFilesBecomeAnEntryWithUrlsSizesAndChecksums() {
        val dir = tmp.newFolder("out")
        val atlasFile = FakeAtlas.write(File(dir, "luxembourg.atlas"))
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
        // the places sidecar's time wins over the region pack's
        assertEquals("2026-10-13T20:21:31Z", e["osm_timestamp"]!!.jsonPrimitive.content)
        assertEquals("packs-20261101", e["release"]!!.jsonPrimitive.content)
        val files = e["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("places", "atlas"), files.map { it["kind"]!!.jsonPrimitive.content })
        val atlas = files.first { it["kind"]!!.jsonPrimitive.content == "atlas" }
        assertEquals("$base/luxembourg.atlas", atlas["url"]!!.jsonPrimitive.content)
        assertEquals(atlasFile.length(), atlas["bytes"]!!.jsonPrimitive.long)
        assertEquals(PackFiles.sha256(atlasFile), atlas["sha256"]!!.jsonPrimitive.content)
        // the pharmacy and the street
        assertEquals(2L, atlas["elements"]!!.jsonPrimitive.long)
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
        assertEquals("$base/luxembourg.atlas", pack.files.single { it.kind == PackFile.ATLAS }.url)
        assertEquals(5000L, pack.places!!.rawBytes)
        assertEquals(atlasFile.length() + 1000, pack.downloadBytes)
        assertEquals(Instant.parse("2026-10-13T20:21:31Z"), pack.osmTime)
    }

    @Test
    fun theRegionPacksTimeAndBoxAreUsedWithoutPlaces() {
        val dir = tmp.newFolder("atlas-only")
        FakeAtlas.write(File(dir, "monaco.atlas"))
        val e = packs(Manifest.build(dir, base, Manifest.readRegions(regions(tmp.root)))).single()
        // the extract's replication time, as the region pack keeps it
        assertEquals(Instant.ofEpochSecond(1_760_000_000L).toString(), e["osm_timestamp"]!!.jsonPrimitive.content)
        // no bbox in regions.json: the region pack's
        assertEquals(6.0, e["bbox"]!!.jsonArray[0].jsonPrimitive.double, 1e-7)
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
        val file = FakeAtlas.write(File(dir, "luxembourg.atlas"))
        val whole = PackFiles.sha256(file)
        val size = file.length()
        // a part of a third and a bit: three parts; the places (smaller) stay whole
        val split = size / 3 + 1
        placesFiles(dir, "luxembourg", bytes = (split - 1).toInt())
        val e = packs(Manifest.build(dir, base, null, splitBytes = split)).single()
        val atlas = e["files"]!!.jsonArray.map { it.jsonObject }.first { it["kind"]!!.jsonPrimitive.content == "atlas" }
        assertNull(atlas["url"])
        assertEquals(whole, atlas["sha256"]!!.jsonPrimitive.content)
        assertEquals(size, atlas["bytes"]!!.jsonPrimitive.long)
        val parts = (atlas["parts"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("luxembourg.atlas.000", "luxembourg.atlas.001", "luxembourg.atlas.002"), parts.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf(split, split, size - 2 * split), parts.map { it["bytes"]!!.jsonPrimitive.long })
        assertEquals("$base/luxembourg.atlas.001", parts[1]["url"]!!.jsonPrimitive.content)
        assertFalse(file.exists())
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
        val atlasFile = PackCatalog.parse(Manifest.pretty(Manifest.build(dir, base, null))).packs.single().files.single { it.kind == PackFile.ATLAS }
        assertTrue(atlasFile.isSplit)
        assertEquals(parts.map { it["url"]!!.jsonPrimitive.content }, atlasFile.parts.map { it.url })
        assertEquals(size, atlasFile.bytes)
    }

    @Test
    fun theWindowsDataGoesUpInPartsBesideItsIndexWhole() {
        val dir = tmp.newFolder("windows")
        placesFiles(dir, "luxembourg")
        // three windows' gzip members' worth of bytes, and their index as `packer windows` writes it
        val data = File(dir, "luxembourg.windows").apply { writeBytes(ByteArray(3000) { (it * 7).toByte() }) }
        val sha = ByteArray(32)
        val entries = listOf(
            WindowsIndex.Entry(8500, 5600, 0, 1000, sha), WindowsIndex.Entry(8500, 5601, 1000, 1200, sha), WindowsIndex.Entry(8501, 5600, 2200, 800, sha),
        )
        File(dir, "luxembourg.windows.idx").outputStream().use {
            WindowsIndex.of(14, WindowsIndex.QUERY, Instant.parse("2026-10-26T21:24:46Z"), 3000, entries).write(it)
        }
        val whole = PackFiles.sha256(data)
        val e = packs(Manifest.build(dir, base, null, splitBytes = 1024)).single()
        val files = e["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("places", "windows", "windows-index"), files.map { it["kind"]!!.jsonPrimitive.content })
        val (_, windows, index) = files
        assertEquals(whole, windows["sha256"]!!.jsonPrimitive.content)
        assertEquals(3000L, windows["bytes"]!!.jsonPrimitive.long)
        assertNull(windows["url"])
        assertEquals(listOf(1024L, 1024L, 952L), windows["parts"]!!.jsonArray.map { it.jsonObject["bytes"]!!.jsonPrimitive.long })
        // the index whole, whatever its size, with what it says of itself
        assertNull(index["parts"])
        assertEquals("$base/luxembourg.windows.idx", index["url"]!!.jsonPrimitive.content)
        assertEquals(WindowsIndex.HEAD_BYTES + 3L * WindowsIndex.ENTRY_BYTES, index["bytes"]!!.jsonPrimitive.long)
        assertEquals(listOf(WindowsIndex.VERSION, 14, WindowsIndex.QUERY, 3), listOf("format", "level", "query", "windows").map { index[it]!!.jsonPrimitive.int })
        // run again on the parts: the entry already written stands
        assertEquals(e, packs(Manifest.build(dir, base, null)).single())
        // and the app sees both
        val pack = PackCatalog.parse(Manifest.pretty(Manifest.build(dir, base, null))).packs.single()
        assertTrue(pack.windows!!.isSplit)
        assertEquals(3000L, pack.windows!!.bytes)
        assertEquals("$base/luxembourg.windows.idx", pack.windowsIndex!!.url)
    }

    @Test
    fun aCountrysTilesGoUpInPartsBesideTheirIndexWholeWithTheCoresVersion() {
        val dir = tmp.newFolder("tiles")
        placesFiles(dir, "netherlands")
        // two windows' packs and a window with nothing to draw, and their index as `packer country` writes it
        val tiles = File(dir, "netherlands.tiles").apply { writeBytes(ByteArray(2500) { (it * 11).toByte() }) }
        val version = "core-p7 s1.1-49f47f3df1d91a4f"
        val entries = listOf(
            TilesIndex.Entry(8414, 5384, 0, 1500, ByteArray(32) { 1 }), TilesIndex.Entry(8414, 5385, 1500, 1000, ByteArray(32) { 2 }),
            TilesIndex.Entry(8372, 5377, 0, 0, ByteArray(32)),
        )
        val style = "the country's style".toByteArray()
        File(dir, "netherlands.tiles.idx").outputStream().use {
            TilesIndex.of(14, Instant.parse("2026-10-26T21:24:46Z"), 2500, version, style, entries).write(it)
        }
        val whole = PackFiles.sha256(tiles)
        val e = packs(Manifest.build(dir, base, null, splitBytes = 1024)).single()
        val files = e["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("places", "tiles", "tiles-index"), files.map { it["kind"]!!.jsonPrimitive.content })
        val (_, data, index) = files
        assertEquals(whole, data["sha256"]!!.jsonPrimitive.content)
        assertEquals(2500L, data["bytes"]!!.jsonPrimitive.long)
        assertNull(data["url"])
        assertEquals(listOf(1024L, 1024L, 452L), data["parts"]!!.jsonArray.map { it.jsonObject["bytes"]!!.jsonPrimitive.long })
        // the index whole, whatever its size, with what it says of itself and the core's version it was built by
        assertNull(index["parts"])
        assertEquals("$base/netherlands.tiles.idx", index["url"]!!.jsonPrimitive.content)
        assertEquals(
            TilesIndex.HEAD_BYTES + version.length + TilesIndex.STYLE_BYTES + style.size + 3L * TilesIndex.ENTRY_BYTES,
            index["bytes"]!!.jsonPrimitive.long,
        )
        assertEquals(listOf(TilesIndex.VERSION, 14, 3), listOf("format", "level", "windows").map { index[it]!!.jsonPrimitive.int })
        assertEquals(version, index["core_version"]!!.jsonPrimitive.content)
        // and the tiles name it too, which pairs them with their index
        assertEquals(version, data["core_version"]!!.jsonPrimitive.content)
        // run again on the parts: the entry already written stands
        assertEquals(e, packs(Manifest.build(dir, base, null)).single())
        // a run with neither keeps the earlier entries
        val later = tmp.newFolder("later")
        placesFiles(later, "netherlands")
        val kept = packs(Manifest.build(later, base, null, previous = Manifest.build(dir, base, null))).single()
        assertEquals(files.drop(1), kept["files"]!!.jsonArray.map { it.jsonObject }.drop(1))
        // and the app sees both, with the version its core must have
        val set = PackCatalog.parse(Manifest.pretty(Manifest.build(dir, base, null))).packs.single().tilesOf(version)!!
        assertTrue(set.tiles.isSplit)
        assertEquals(2500L, set.tiles.bytes)
        assertEquals("$base/netherlands.tiles.idx", set.index.url)
        assertEquals(version, set.index.coreVersion)
    }

    /** A country's tiles of core [version] in [dir], as `packer country` writes them: [bytes] of tiles, two windows. */
    private fun countryTiles(dir: File, version: String, bytes: Int = 300) {
        File(dir, "netherlands.tiles").writeBytes(ByteArray(bytes) { (it * 7 + version.length).toByte() })
        val entries = listOf(TilesIndex.Entry(8414, 5384, 0, 200, ByteArray(32) { 1 }), TilesIndex.Entry(8414, 5385, 200, bytes - 200, ByteArray(32) { 2 }))
        File(dir, "netherlands.tiles.idx").outputStream().use {
            TilesIndex.of(14, Instant.parse("2026-10-30T20:21:24Z"), bytes.toLong(), version, "the country's style".toByteArray(), entries).write(it)
        }
    }

    private var runs = 0

    /**
     * A build job's run for the Netherlands at core [version] into release [tag], then the publishing job's on
     * [previous] (packs-latest's list): the list it publishes, and the build's entry (`netherlands.pack.json`).
     */
    private fun republish(tag: String, version: String, previous: JsonObject, bytes: Int = 300): Pair<JsonObject, File> {
        val run = ++runs
        val built = tmp.newFolder("build-$run")
        placesFiles(built, "netherlands")
        countryTiles(built, version, bytes)
        val release = "https://github.com/atlas-maps-app/Atlas/releases/download/$tag"
        Manifest.build(built, release, null)
        val entry = File(built, "netherlands.pack.json")
        val entries = tmp.newFolder("publish-$run").also { entry.copyTo(File(it, entry.name)) }
        return Manifest.build(entries, release, null, previous) to entry
    }

    @Test
    fun aRepublishKeepsTheTilesOfTheCoreVersionBeforeForThePhonesNotUpdatedYet() {
        // packs-latest's list as packs-20261029 published it: alpha 22's core-p9 tiles, their index of format 1
        val p9 = "core-p9 s1.1-49f47f3df1d91a4f"
        val p11 = "core-p11 s1.1-49f47f3df1d91a4f"
        val old = "https://github.com/atlas-maps-app/Atlas/releases/download/packs-20261029"
        val published = Json.parseToJsonElement(
            """{"format": 1, "generated": "2026-10-29T22:40:19Z", "packs": [{"id": "netherlands", "name": "Netherlands", "country": "NL",
                "bbox": [3.3, 50.7, 7.2, 53.6], "osm_timestamp": "2026-10-28T21:21:02Z", "release": "packs-20261029", "files": [
                {"kind": "places", "name": "netherlands.places.db.gz", "bytes": 387060870, "sha256": "aa", "url": "$old/netherlands.places.db.gz"},
                {"kind": "tiles", "name": "netherlands.tiles", "bytes": 824584237, "sha256": "bb", "url": "$old/netherlands.tiles"},
                {"kind": "tiles-index", "name": "netherlands.tiles.idx", "bytes": 1209995, "sha256": "cc", "url": "$old/netherlands.tiles.idx",
                 "format": 1, "level": 14, "windows": 23268, "core_version": "$p9"}]}]}""",
        ).jsonObject
        val oldSet = packs(published).single()["files"]!!.jsonArray.drop(1)
        fun kinds(o: JsonObject) = packs(o).single()["files"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
        fun versions(o: JsonObject) = packs(o).single()["files"]!!.jsonArray.map { it.jsonObject }
            .filter { it["kind"]!!.jsonPrimitive.content == "tiles-index" }.map { it["core_version"]!!.jsonPrimitive.content }

        // the republish at core-p11: its own tiles, and alpha 22's kept ahead of them, as they were published
        val (first, built) = republish("packs-20261102", p11, published)
        assertEquals(listOf("places", "tiles", "tiles-index", "tiles", "tiles-index"), kinds(first))
        assertEquals(oldSet, packs(first).single()["files"]!!.jsonArray.drop(1).take(2))
        assertEquals(listOf(p9, p11), versions(first))
        val pack = PackCatalog.parse(Manifest.pretty(first)).packs.single()
        assertEquals("packs-20261102", pack.release)
        // each phone finds its own core's: alpha 22's (which reads the first tiles and index alone) and the new one's
        assertEquals("$old/netherlands.tiles", pack.tilesOf(p9)!!.tiles.url)
        assertEquals(p9, pack.files.first { it.kind == PackFile.TILES_INDEX }.coreVersion)
        assertEquals("$old/netherlands.tiles", pack.files.first { it.kind == PackFile.TILES }.url)
        val new = pack.tilesOf(p11)!!
        assertEquals("https://github.com/atlas-maps-app/Atlas/releases/download/packs-20261102/netherlands.tiles", new.tiles.url)
        assertEquals(p11, new.tiles.coreVersion)
        assertEquals(listOf(p9, p11), pack.tileSets.map { it.index.coreVersion })
        // the pack's size is the new core's: the tiles kept for the phones before it are not counted
        assertEquals(pack.places!!.bytes + new.tiles.bytes + new.index.bytes, pack.downloadBytes)
        // the publishing job run again: the same list
        val again = tmp.newFolder("again").also { built.copyTo(File(it, built.name)) }
        assertEquals(packs(first), packs(Manifest.build(again, "https://github.com/atlas-maps-app/Atlas/releases/download/packs-20261102", null, first)))

        // a month on at the same core: its new tiles replace the old ones of their version, alpha 22's stay
        val month = republish("packs-20261202", p11, first, bytes = 320).first
        assertEquals(listOf(p9, p11), versions(month))
        assertEquals(oldSet, packs(month).single()["files"]!!.jsonArray.drop(1).take(2))
        assertEquals(320L, PackCatalog.parse(Manifest.pretty(month)).packs.single().tilesOf(p11)!!.tiles.bytes)
        // the next core: the one before it stays, alpha 22's goes; one set kept, never more
        val p12 = "core-p12 s1.1-49f47f3df1d91a4f"
        val next = republish("packs-20261203", p12, month).first
        assertEquals(listOf(p11, p12), versions(next))
        assertEquals(listOf("places", "tiles", "tiles-index", "tiles", "tiles-index"), kinds(next))
        assertTrue(packs(next).single()["files"].toString().contains("packs-20261202/netherlands.tiles"))
        // a second run the same day at another core replaces the day's core-p12 tiles in their release: those are not
        // kept (their files are the new ones now), and the newest still published, core-p11's, stays
        val p13 = "core-p13 s1.1-49f47f3df1d91a4f"
        val sameDay = republish("packs-20261203", p13, next).first
        assertEquals(listOf(p11, p13), versions(sameDay))
        // and with none other published, none
        val alone = JsonObject(next + ("packs" to JsonArray(listOf(packs(next).single().let { e ->
            JsonObject(e + ("files" to JsonArray(e["files"]!!.jsonArray.filterIndexed { i, _ -> i == 0 || i >= 3 })))
        }))))
        assertEquals(listOf(p12), versions(alone))
        assertEquals(listOf(p13), versions(republish("packs-20261203", p13, alone).first))
    }

    @Test
    fun theTilesIndexAloneDatesThePack() {
        // a region with its tiles only: the index's date is its data's
        val dir = tmp.newFolder("index-only")
        File(dir, "netherlands.tiles").writeBytes(ByteArray(10))
        File(dir, "netherlands.tiles.idx").outputStream().use {
            TilesIndex.of(
                14, Instant.parse("2026-10-26T21:24:46Z"), 10, "core-p7 s1.1-49f47f3df1d91a4f", ByteArray(1), listOf(TilesIndex.Entry(1, 1, 0, 10, ByteArray(32))),
            ).write(it)
        }
        assertEquals("2026-10-26T21:24:46Z", packs(Manifest.build(dir, base, null)).single()["osm_timestamp"]!!.jsonPrimitive.content)
    }

    @Test
    fun partNamesUseAsciiDigitsWhateverTheLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            // Arabic digits by default: String.format("%03d") would write ٠٠٠
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val f = File(tmp.newFolder("ar"), "x.atlas").apply { writeBytes(ByteArray(10)) }
            assertEquals(listOf("x.atlas.000", "x.atlas.001", "x.atlas.002"), PackFiles.split(f, 4).map { it.name })
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
        FakeAtlas.write(File(out, "luxembourg.atlas"))
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
