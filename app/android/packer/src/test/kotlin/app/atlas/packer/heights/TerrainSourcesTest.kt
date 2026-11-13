package app.atlas.packer.heights

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * The terrain's registry and the packer's readers of its sources (ENGINE.md part 6), with no host: the registry the
 * repository carries says what step 1 builds and credits; a sheet index of either kind names its sheets and their
 * boxes; the GeoTIFF reader reads a cloud-optimised file's images (DEFLATE with the floating-point predictor) and writes
 * one of them out as a GeoTIFF of its own, placed as GDAL places an overview; Copernicus's cells are sampled between
 * their samples, across a cell's edge too; and the merge fills, feathers and does not depend on its window.
 */
class TerrainSourcesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val registry get() = TerrainSources.read(File(System.getProperty("atlas.repo"), "app/android/packs/terrain-sources.json"))

    @Test
    fun theRegistrySaysWhatIsBuiltAndCredited() {
        val r = registry
        assertEquals(listOf("netherlands", "tuscany", "norway"), r.regions.map { it.id })
        for (region in r.regions) {
            val sources = region.sources.map(r::source)
            assertTrue("${region.id} has a national model first", sources.first().area == listOf(region.id))
            assertEquals("${region.id} is filled by GLO-30", "glo30", sources.last().id)
            assertTrue(region.checkViews.isNotEmpty())
            for (v in region.checkViews) assertTrue("data/regions/$v", File(System.getProperty("atlas.repo"), "data/regions/$v/region.json").isFile)
        }
        // every source says how it is read, under which licence, and how it is credited; Copernicus's carry its notice and liability sentence
        for (s in r.sources) {
            assertTrue(s.id, s.credit.isNotBlank() && s.licence.isNotBlank() && s.hosts.all { it.startsWith("https://") } && Regex("\\d{4}-\\d{2}-\\d{2}").matches(s.date))
        }
        for ((id, product) in listOf("glo30" to "WorldDEM-30", "glo90" to "WorldDEM-90")) {
            val s = r.source(id)
            assertEquals("produced using Copernicus $product © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved", s.credit)
            assertEquals("The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus $product", s.notice)
            assertEquals("dsm", s.model)
        }
        assertTrue(r.source("tinitaly").credit.contains("https://doi.org/10.13127/tinitaly/1.1"))
        assertTrue(r.source("kartverket-dtm1").credit.contains("© Kartverket"))
        // the map's credit line names each source by its short name (terrain.json's, packer heights-list)
        assertEquals(listOf("Copernicus", "Copernicus", "AHN", "TINITALY", "Kartverket"), r.sources.map { it.short })
        // the Netherlands whole, Tuscany's L12 and L13 whole, Norway round Bergen
        assertTrue(r.region("netherlands").levels.single().let { it.whole && it.levels == listOf(12, 13, 14) })
        assertTrue(r.region("tuscany").levels.single().let { it.whole && it.levels == listOf(12, 13) })
        assertTrue(r.region("norway").levels.single().let { !it.whole && it.views == listOf("bergen") && it.growKm == 20.0 })
        assertThrows(IllegalArgumentException::class.java) { r.region("atlantis") }
        // About & licences shows the packer's licence table, which carries every source's credit and notice word for word
        val table = File(System.getProperty("atlas.repo"), "app/android/packer/LICENSES.md").readText()
        for (s in r.sources) {
            assertTrue("packer/LICENSES.md lacks ${s.id}'s credit", table.contains(s.credit))
            s.notice?.let { assertTrue("packer/LICENSES.md lacks ${s.id}'s notice", table.contains(it)) }
        }
        // Copernicus's cells by name
        val cells = CogTiles(r.source("glo30"), tmp.root, Fetcher("test"))
        assertEquals("Copernicus_DSM_COG_10_N52_00_E004_00_DEM/Copernicus_DSM_COG_10_N52_00_E004_00_DEM.tif", cells.name(52, 4))
        assertEquals("Copernicus_DSM_COG_10_S04_00_W061_00_DEM/Copernicus_DSM_COG_10_S04_00_W061_00_DEM.tif", cells.name(-4, -61))
    }

    @Test
    fun aSheetIndexNamesItsSheetsAndTheirBoxes() {
        val r = registry
        val geojson = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"kaartbladNr":"M_24FZ2","url":"https://example.invalid/M_24FZ2.tif"},
            "geometry":{"type":"Polygon","coordinates":[[[95000,487500],[95000,493750],[100000,493750],[100000,487500],[95000,487500]]]}}]}"""
        val a = Sources.sheets(r.source("ahn4"), geojson.toByteArray()).single()
        assertEquals("M_24FZ2.tif", a.name)
        assertArrayEquals(doubleArrayOf(95000.0, 487500.0, 100000.0, 493750.0), a.box, 0.0)
        val vrt = """<VRTDataset rasterXSize="1200010" rasterYSize="1515010">
  <GeoTransform> -8.4575000000000000e+04,  1.0000000000000000e+00,  0.0000000000000000e+00,  7.9560050000000000e+06,  0.0000000000000000e+00, -1.0000000000000000e+00</GeoTransform>
  <VRTRasterBand dataType="Float32" band="1">
    <ComplexSource>
      <SourceFilename relativeToVRT="1">DTM1/33-101-112.tif</SourceFilename>
      <SrcRect xOff="0" yOff="0" xSize="15010" ySize="15010" />
      <DstRect xOff="0" yOff="1335000" xSize="15010" ySize="15010" />
    </ComplexSource>
    <ComplexSource>
      <SourceFilename relativeToVRT="1">DTM1/33-102-110.tif</SourceFilename>
      <DstRect xOff="15000" yOff="1365000" xSize="15010" ySize="15010" />
    </ComplexSource>
  </VRTRasterBand>
</VRTDataset>"""
        val k = Sources.sheets(r.source("kartverket-dtm1"), vrt.toByteArray())
        assertEquals(listOf("https://nedlasting.geonorge.no/hoydedata/DTM1/33-101-112.tif", "https://nedlasting.geonorge.no/hoydedata/DTM1/33-102-110.tif"), k.map { it.url })
        assertArrayEquals(doubleArrayOf(-84575.0, 6605995.0, -69565.0, 6621005.0), k[0].box, 0.0)
        assertTrue(Sources.meets(k[0].box, doubleArrayOf(-70000.0, 6600000.0, -60000.0, 6606000.0)))
        assertTrue(!Sources.meets(k[0].box, doubleArrayOf(-69565.0, 6600000.0, -60000.0, 6606000.0)))
    }

    /** A little-endian tiled GeoTIFF of [images] (each width, height and samples), DEFLATE with the floating-point predictor, the raster's place on its first image. */
    private fun tiff(file: File, tile: Int, images: List<Triple<Int, Int, FloatArray>>, scale: DoubleArray, tie: DoubleArray, point: Boolean) {
        val body = ByteArrayOutputStream()
        class Dir(val entries: List<Triple<Int, Int, ByteArray>>, val counts: Int)
        fun shorts(vararg v: Int) = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()
        fun ints(v: List<Int>) = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it) } }.array()
        fun doubles(v: DoubleArray) = ByteBuffer.allocate(v.size * 8).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putDouble(it) } }.array()
        // the tiles' bytes first, from byte 8 on; then the directories and their values
        val dirs = ArrayList<List<Triple<Int, Int, ByteArray>>>()
        for ((n, image) in images.withIndex()) {
            val (w, h, z) = image
            val across = (w + tile - 1) / tile
            val down = (h + tile - 1) / tile
            val offsets = ArrayList<Int>()
            val counts = ArrayList<Int>()
            for (ty in 0 until down) for (tx in 0 until across) {
                val raw = ByteArray(tile * tile * 4)
                for (r in 0 until tile) {
                    val row = ByteArray(tile * 4)
                    for (c in 0 until tile) {
                        val x = tx * tile + c
                        val y = ty * tile + r
                        val bits = (if (x < w && y < h) z[y * w + x] else 0f).toRawBits()
                        row[c] = (bits ushr 24).toByte(); row[tile + c] = (bits ushr 16).toByte(); row[2 * tile + c] = (bits ushr 8).toByte(); row[3 * tile + c] = bits.toByte()
                    }
                    for (i in row.size - 1 downTo 1) row[i] = (row[i] - row[i - 1]).toByte()
                    System.arraycopy(row, 0, raw, r * tile * 4, tile * 4)
                }
                val d = Deflater()
                d.setInput(raw)
                d.finish()
                val buf = ByteArray(raw.size + 64)
                val len = d.deflate(buf)
                d.end()
                offsets += 8 + body.size()
                counts += len
                body.write(buf, 0, len)
            }
            val e = arrayListOf(
                Triple(256, 4, ints(listOf(w))), Triple(257, 4, ints(listOf(h))), Triple(258, 3, shorts(32)), Triple(259, 3, shorts(8)), Triple(277, 3, shorts(1)),
                Triple(317, 3, shorts(3)), Triple(322, 3, shorts(tile)), Triple(323, 3, shorts(tile)), Triple(324, 4, ints(offsets)), Triple(325, 4, ints(counts)), Triple(339, 3, shorts(3)),
            )
            if (n == 0) {
                e += Triple(33550, 12, doubles(scale))
                e += Triple(33922, 12, doubles(tie))
                e += Triple(34735, 3, shorts(1, 1, 0, 2, 1024, 0, 1, 2, 1025, 0, 1, if (point) 2 else 1))
                e += Triple(42113, 2, "-9999\u0000".toByteArray())
            }
            dirs += e.sortedBy { it.first }
        }
        val sizes = mapOf(2 to 1, 3 to 2, 4 to 4, 12 to 8)
        var at = 8 + body.size()
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(at)
        out.write(head.array()); out.write(body.toByteArray())
        for ((n, e) in dirs.withIndex()) {
            val dirBytes = 2 + e.size * 12 + 4
            var extra = at + dirBytes
            val d = ByteBuffer.allocate(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
            val tail = ByteArrayOutputStream()
            d.putShort(e.size.toShort())
            for ((tag, type, bytes) in e) {
                d.putShort(tag.toShort()).putShort(type.toShort()).putInt(bytes.size / sizes.getValue(type))
                if (bytes.size <= 4) {
                    d.put(bytes); repeat(4 - bytes.size) { d.put(0) }
                } else {
                    d.putInt(extra + tail.size()); tail.write(bytes)
                }
            }
            val next = extra + tail.size()
            d.putInt(if (n < dirs.size - 1) next else 0)
            out.write(d.array()); out.write(tail.toByteArray())
            at = next
        }
        file.writeBytes(out.toByteArray())
    }

    @Test
    fun theGeoTiffReaderReadsAFilesImagesAndWritesOneOut() {
        val full = FloatArray(40 * 24) { (it % 40) * 0.5f + (it / 40) * 3f - 7.25f }
        val half = FloatArray(20 * 12) { (it % 20) * 1f + (it / 20) * 6f - 6.5f }
        val file = tmp.newFile("sheet.tif")
        tiff(file, 16, listOf(Triple(40, 24, full), Triple(20, 12, half)), doubleArrayOf(0.5, 0.5, 0.0), doubleArrayOf(0.0, 0.0, 0.0, 95000.0, 493750.0, 0.0), false)
        val images = Tiff.images(file)
        assertEquals(2, images.size)
        assertEquals(listOf(40, 24, 16, 8, 3), images[0].let { listOf(it.width, it.height, it.tileWidth, it.compression, it.predictor) })
        assertEquals("-9999", images[0].nodata)
        assertTrue(!images[0].pixelIsPoint)
        val bytes = file.readBytes()
        val read = { o: Long, n: Int -> bytes.copyOfRange(o.toInt(), minOf(bytes.size, o.toInt() + n)) }
        assertArrayEquals(full, Tiff.raster(images[0], read), 0f)
        assertArrayEquals(half, Tiff.raster(images[1], read), 0f)
        // the overview as a GeoTIFF of its own: its tiles' bytes as they are, its pixels twice the raster's
        val range = images[1].dataRange
        val own = File(tmp.root, "overview.tif")
        Tiff.writeImage(images[0], images[1], bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1), own)
        val one = Tiff.images(own).single()
        assertEquals(listOf(20, 12), listOf(one.width, one.height))
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 0.0), one.scale, 0.0)
        assertArrayEquals(images[0].tie, one.tie, 0.0)
        assertEquals("-9999", one.nodata)
        assertArrayEquals(images[0].geoKeys, one.geoKeys)
        val ownBytes = own.readBytes()
        assertArrayEquals(half, Tiff.raster(one) { o, n -> ownBytes.copyOfRange(o.toInt(), minOf(ownBytes.size, o.toInt() + n)) }, 0f)
        assertThrows(IllegalArgumentException::class.java) { Tiff.images { _, n -> ByteArray(n) } }
    }

    @Test
    fun copernicusCellsAreSampledBetweenTheirSamplesAcrossACellsEdge() {
        val r = registry
        // two cells side by side, four samples a degree: sample (i, j) of a cell at its west + i/4, its north − j/4
        val dir = tmp.newFolder("glo30")
        val height = { lat: Double, lon: Double -> (100 * (lat - 52) + 10 * (lon - 4)).toFloat() }
        val none = object : Fetcher("test") {
            override fun exists(source: String, url: String): Boolean = false
            override fun download(source: String, url: String, file: File): File = file
        }
        val cells = CogTiles(r.source("glo30"), dir, none)
        for (lon in 4..5) {
            val z = FloatArray(16) { height(53.0 - (it / 4) / 4.0, lon + (it % 4) / 4.0) }
            tiff(File(dir, cells.name(52, lon).substringAfterLast('/')), 16, listOf(Triple(4, 4, z)), doubleArrayOf(0.25, 0.25, 0.0), doubleArrayOf(0.0, 0.0, 0.0, lon - 0.125, 53.125, 0.0), false)
        }
        // a plane is read back exactly between samples, in a cell and across the edge between the two
        for ((lat, lon) in listOf(52.5 to 4.25, 52.61 to 4.37, 52.9 to 4.875, 52.3 to 4.99, 52.75 to 5.3)) {
            assertEquals("$lat, $lon", height(lat, lon), cells.at(lat, lon), 1e-3f)
        }
        // south of the cells' last row the cell below is sea: the row's own samples
        assertEquals(height(52.25, 4.5), cells.at(52.1, 4.5), 1e-3f)
        // no cell, no sample
        assertTrue(cells.at(40.5, 4.5).isNaN())
        val lattice = Lattice(12, 257)
        val w = Window(lattice, Math.floor(lattice.x(4.4)).toLong(), Math.floor(lattice.y(52.6)).toLong(), 3, 2)
        val h = assertNotNull(cells.heights(w)).let { cells.heights(w)!! }
        assertEquals(height(lattice.lat(w.y0 + 1.0), lattice.lon(w.x0 + 2.0)), h[1 * 3 + 2], 1e-3f)
        assertNull(cells.heights(Window(lattice, Math.floor(lattice.x(4.4)).toLong(), Math.floor(lattice.y(40.5)).toLong(), 3, 2)))
    }

    @Test
    fun theMergeFillsFeathersAndDoesNotDependOnItsWindow() {
        // a national model with a small hole and a wide one, over a world model 30 m above it
        val n = 160
        val land = { x: Int, y: Int -> 10f + 0.05f * x + 0.02f * y }
        fun hole(x: Int, y: Int) = (x - 40) * (x - 40) + (y - 40) * (y - 40) <= 9 || x >= 100
        fun window(x0: Int, y0: Int, w: Int, h: Int): Composite.Merged {
            val first = FloatArray(w * h) { i -> val x = x0 + i % w; val y = y0 + i / w; if (hole(x, y)) Float.NaN else land(x, y) }
            val next = FloatArray(w * h) { i -> val x = x0 + i % w; land(x, y0 + i / w) + 30f + (if (x >= 150) Float.NaN else 0f) }
            val world = Composite.merge(next, null, null, w, h, 0, 0, 1)
            return Composite.merge(first, world.heights, world.source, w, h, 5, 20, 0)
        }
        val m = window(0, 0, n, n)
        fun at(x: Int, y: Int) = m.heights[y * n + x]
        fun src(x: Int, y: Int) = m.source[y * n + x]
        // its own samples; the small hole from its surroundings, marked filled
        assertEquals(land(10, 10), at(10, 10), 0f)
        assertEquals(0, src(10, 10).toInt())
        assertEquals(land(40, 40), at(40, 40), 0.05f)
        assertEquals(Composite.FILLED, src(40, 40).toInt())
        assertEquals(0, Composite.index(src(40, 40)))
        // the wide hole: filled for five nodes, then the world model with the offset fading over twenty, then the world model's own
        assertEquals(Composite.FILLED, src(104, 80).toInt())
        assertEquals(1, src(105, 80).toInt())
        assertEquals(land(105, 80) + 1.5f, at(105, 80), 0.5f)
        assertEquals(land(114, 80) + 15f, at(114, 80), 1.5f)
        assertEquals(land(130, 80) + 30f, at(130, 80), 0f)
        for (x in 96..130) assertTrue("a step at $x", Math.abs(at(x + 1, 80) - at(x, 80)) < 2.5f)
        // where neither has a sample: none, 0 m
        assertEquals(Composite.NONE, src(155, 80))
        assertEquals(0f, at(155, 80), 0f)
        assertEquals(-1, Composite.index(src(155, 80)))
        // another window over the same nodes gives them the same values, as far inside it as the fill and the feather reach
        val other = window(30, 20, 110, 120)
        for (y in 20 + 26 until 140 - 26) for (x in 30 + 26 until 140 - 26) {
            assertEquals("node $x, $y", at(x, y), other.heights[(y - 20) * 110 + (x - 30)], 0f)
            assertEquals(src(x, y), other.source[(y - 20) * 110 + (x - 30)])
        }
        // a source with nothing in the window leaves it to the next
        val none = Composite.merge(null, m.heights, m.source, n, n, 5, 20, 0)
        assertArrayEquals(m.heights, none.heights, 0f)
    }
}
