package app.atlas.packer.heights

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A terrain source read for a build ([TerrainSources]): a national model's sheets or coverage boxes fetched into the
 * job's work disk and laid as a GDAL mosaic, which `gdalwarp` brings onto the lattice ([GdalSource]); or Copernicus's
 * degree cells, which lie on latitude and longitude and are sampled here ([CogTiles]).
 */
object Sources {
    /** A sheet of a sheet index: where it is read, and its box (west, south, east, north) in its source's CRS. */
    class Sheet(val url: String, val box: DoubleArray) {
        val name: String get() = url.substringAfterLast('/')
    }

    fun meets(a: DoubleArray, b: DoubleArray): Boolean = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    /** The sheets of [source]'s index, whose bytes are [index]. */
    fun sheets(source: TerrainSources.Source, index: ByteArray): List<Sheet> {
        val ix = source.index ?: throw IllegalArgumentException("source ${source.id} has no sheet index")
        return when (ix.kind) {
            "geojson" -> {
                val key = ix.file ?: "url"
                Json.parseToJsonElement(String(index, Charsets.UTF_8)).jsonObject.getValue("features").jsonArray.map { f ->
                    val o = f.jsonObject
                    val ring = o.getValue("geometry").jsonObject.getValue("coordinates").jsonArray[0].jsonArray.map { p -> p.jsonArray.let { doubleArrayOf(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }
                    Sheet(o.getValue("properties").jsonObject.getValue(key).jsonPrimitive.content, doubleArrayOf(ring.minOf { it[0] }, ring.minOf { it[1] }, ring.maxOf { it[0] }, ring.maxOf { it[1] }))
                }
            }
            "vrt" -> {
                val text = String(index, Charsets.UTF_8)
                val gt = Regex("<GeoTransform>([^<]+)</GeoTransform>").find(text)?.groupValues?.get(1)?.split(',')?.map { it.trim().toDouble() }
                    ?: throw IllegalArgumentException("${ix.url} has no GeoTransform")
                require(gt.size == 6 && gt[2] == 0.0 && gt[4] == 0.0 && gt[1] > 0 && gt[5] < 0) { "${ix.url}'s raster is turned" }
                val base = URI.create(ix.url)
                Regex("<SourceFilename[^>]*>([^<]+)</SourceFilename>.*?<DstRect xOff=\"([^\"]+)\" yOff=\"([^\"]+)\" xSize=\"([^\"]+)\" ySize=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
                    .findAll(text).map { m ->
                        val (name, x, y, w, h) = m.destructured
                        val west = gt[0] + x.toDouble() * gt[1]
                        val north = gt[3] + y.toDouble() * gt[5]
                        Sheet(base.resolve(name.trim()).toString(), doubleArrayOf(west, north + h.toDouble() * gt[5], west + w.toDouble() * gt[1], north))
                    }.toList()
            }
            else -> throw IllegalArgumentException("source ${source.id}: an index of kind '${ix.kind}'")
        }
    }

    /** What [fetch] brought: the files, and each one's box in the source's CRS. */
    class Fetched(val files: List<File>, val boxes: List<DoubleArray>, val seconds: Double)

    /**
     * [source]'s rasters over [boxes] (each west, south, east, north in the source's CRS) into [dir]: the sheets that
     * meet a box, each one's [TerrainSources.Source.overview] as a GeoTIFF of its own (the sheet itself for 0); or the
     * coverage service's answers for the boxes, cut into blocks on its own grid.
     */
    fun fetch(source: TerrainSources.Source, boxes: List<DoubleArray>, dir: File, fetcher: Fetcher, log: (String) -> Unit): Fetched {
        dir.mkdirs()
        val t0 = System.nanoTime()
        class Job(val file: File, val box: DoubleArray, val work: () -> Unit)
        val jobs = ArrayList<Job>()
        when (source.format) {
            TerrainSources.Format.COG_SHEETS -> {
                val all = sheets(source, fetcher.get(source.id, source.index!!.url))
                val wanted = all.filter { s -> boxes.any { meets(s.box, it) } }
                log("${source.id}: ${wanted.size} of ${all.size} sheets meet the build's ${boxes.size} boxes")
                for (s in wanted) {
                    val file = File(dir, s.name)
                    jobs += Job(file, s.box) {
                        if (source.overview == 0) {
                            fetcher.download(source.id, s.url, file)
                        } else if (!file.isFile) {
                            val images = Tiff.images { o, n -> fetcher.get(source.id, s.url, o until o + n, short = true) }
                            // a sheet too small to carry the overview is read at the coarsest it has
                            val image = images[minOf(source.overview, images.size - 1)]
                            val range = image.dataRange
                            if (!range.isEmpty()) Tiff.writeImage(images[0], image, fetcher.get(source.id, s.url, range), file)
                        }
                    }
                }
            }
            TerrainSources.Format.WCS -> {
                val w = source.wcs!!
                val cell = source.read
                val span = w.block * cell
                val seen = HashSet<Pair<Long, Long>>()
                for (b in boxes) {
                    var j = Math.floor(b[1] / span).toLong()
                    while (j * span < b[3]) {
                        var i = Math.floor(b[0] / span).toLong()
                        while (i * span < b[2]) {
                            if (seen.add(i to j)) {
                                val box = doubleArrayOf(i * span, j * span, (i + 1) * span, (j + 1) * span)
                                val file = File(dir, "${source.id}_${i}_$j.tif")
                                val url = source.hosts[0] + "?service=WCS&version=2.0.1&request=GetCoverage&coverageId=${w.coverage}&format=image/tiff" +
                                    (w.extra?.let { "&$it" } ?: "") + "&subset=${w.axes[0]}(${num(box[0])},${num(box[2])})&subset=${w.axes[1]}(${num(box[1])},${num(box[3])})"
                                jobs += Job(file, box) {
                                    if (!file.isFile) {
                                        val bytes = fetcher.get(source.id, url)
                                        if (bytes.size < 8 || bytes[0] != 'I'.code.toByte() && bytes[0] != 'M'.code.toByte()) throw IOException("${source.id}: $url did not answer with a TIFF")
                                        val part = File(file.path + ".part")
                                        part.writeBytes(bytes)
                                        if (!part.renameTo(file)) throw IOException("cannot move $part to $file")
                                    }
                                }
                            }
                            i++
                        }
                        j++
                    }
                }
                log("${source.id}: ${jobs.size} boxes of ${w.block} cells a side for the build's ${boxes.size} boxes")
            }
            TerrainSources.Format.COG_TILES -> throw IllegalArgumentException("source ${source.id} is read cell by cell (CogTiles), not fetched whole")
        }
        // the fetcher lets four requests at a time through to a host; as many workers ask
        val pool = Executors.newFixedThreadPool(Fetcher.PER_HOST)
        val done = AtomicInteger()
        try {
            pool.invokeAll(jobs.map { j ->
                Callable {
                    j.work()
                    val n = done.incrementAndGet()
                    if (n % 100 == 0 || n == jobs.size) log("${source.id}: $n of ${jobs.size} read")
                }
            }).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val there = jobs.filter { it.file.isFile }
        return Fetched(there.map { it.file }, there.map { it.box }, (System.nanoTime() - t0) / 1e9)
    }

    private fun num(v: Double): String = if (v == Math.rint(v)) "%d".format(v.toLong()) else "%s".format(java.util.Locale.ROOT, v)
}

/** A mosaic of a source's fetched rasters ([vrt]), warped onto a window's nodes by GDAL, each node the average of the cells under it. */
class GdalSource(private val gdal: Gdal, private val vrt: File, private val scratch: File, private val area: DoubleArray? = null) : NodeSource {
    private val serial = AtomicInteger()

    override fun heights(window: Window): FloatArray? = gdal.warp(vrt, window, File(scratch, "warp-${serial.incrementAndGet()}.bil"), area = area)
}

/**
 * Copernicus's degree cells (ENGINE.md part 6: GLO-30 fills the national models' gaps): each cell's GeoTIFF fetched
 * whole into [dir] when a window first needs it, read with the packer's own reader ([Tiff]) and sampled at the nodes
 * between its four nearest samples (the cells are coarser than the lattice they fill, so nothing is averaged away). A
 * cell the host does not have is open sea: no sample.
 */
class CogTiles(private val source: TerrainSources.Source, private val dir: File, private val fetcher: Fetcher, private val keep: Int = 6) : NodeSource {
    /** One degree cell's raster and where its samples lie: sample (i, j) at longitude x0 + i·dx, latitude y0 − j·dy. */
    private class Cell(val width: Int, val height: Int, val x0: Double, val y0: Double, val dx: Double, val dy: Double, val z: FloatArray)

    private val cells = object : LinkedHashMap<Long, Cell?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cell?>?): Boolean = size > keep
    }
    private val absent = HashSet<Long>()

    /** The file name of the cell whose south-west corner is ([lat], [lon]). */
    fun name(lat: Int, lon: Int): String = source.pattern!!
        .replace("{NS}", if (lat >= 0) "N" else "S").replace("{EW}", if (lon >= 0) "E" else "W")
        .replace("{lat:02}", "%02d".format(Math.abs(lat))).replace("{lon:03}", "%03d".format(Math.abs(lon)))

    private fun cell(lat: Int, lon: Int): Cell? {
        val key = (lat + 90L) * 1000 + (lon + 180L)
        synchronized(this) {
            if (key in absent) return null
            cells[key]?.let { return it }
        }
        val name = name(lat, lon)
        val file = File(dir, name.substringAfterLast('/'))
        val url = source.hosts[0] + name
        val loaded = synchronized(file.path.intern()) {
            if (!file.isFile && !fetcher.exists(source.id, url)) {
                null
            } else {
                fetcher.download(source.id, url, file)
                RandomAccessFile(file, "r").use { f ->
                    val image = Tiff.images { o, n -> Tiff.at(f, o, n) }[0]
                    val scale = image.scale ?: throw IOException("$name has no pixel scale")
                    val tie = image.tie ?: throw IOException("$name has no tie point")
                    val half = if (image.pixelIsPoint) 0.0 else 0.5
                    Cell(image.width, image.height, tie[3] + (half - tie[0]) * scale[0], tie[4] - (half - tie[1]) * scale[1], scale[0], scale[1], Tiff.raster(image) { o, n -> Tiff.at(f, o, n) })
                }
            }
        }
        synchronized(this) {
            if (loaded == null) absent += key else cells[key] = loaded
        }
        return loaded
    }

    /** The height at ([lat], [lon]), between the cell's four samples round it; NaN where the host has no cell. */
    fun at(lat: Double, lon: Double): Float {
        val cellLat = Math.floor(lat).toInt()
        val cellLon = Math.floor(lon).toInt()
        val c = cell(cellLat, cellLon) ?: return Float.NaN
        val fx = maxOf(0.0, (lon - c.x0) / c.dx)
        val fy = maxOf(0.0, (c.y0 - lat) / c.dy)
        val i = Math.floor(fx).toInt()
        val j = Math.floor(fy).toInt()
        val tx = (fx - i).toFloat()
        val ty = (fy - j).toFloat()
        // a sample past the cell's last column or row is its east or south neighbour's first: read there by its place
        // (the neighbour may count other columns to a degree), the cell's own edge where the neighbour is sea
        fun sample(ii: Int, jj: Int): Float {
            if (ii < c.width && jj < c.height) return c.z[jj * c.width + ii]
            val own = c.z[minOf(jj, c.height - 1) * c.width + minOf(ii, c.width - 1)]
            val n = cell(if (jj >= c.height) cellLat - 1 else cellLat, if (ii >= c.width) cellLon + 1 else cellLon) ?: return own
            val ni = Math.rint((c.x0 + ii * c.dx - n.x0) / n.dx).toInt().coerceIn(0, n.width - 1)
            val nj = Math.rint((n.y0 - (c.y0 - jj * c.dy)) / n.dy).toInt().coerceIn(0, n.height - 1)
            return n.z[nj * n.width + ni]
        }
        val a = sample(i, j)
        val b = sample(i + 1, j)
        val d = sample(i, j + 1)
        val e = sample(i + 1, j + 1)
        return (a * (1 - tx) + b * tx) * (1 - ty) + (d * (1 - tx) + e * tx) * ty
    }

    override fun heights(window: Window): FloatArray? {
        val out = FloatArray(window.size)
        var any = false
        val lons = DoubleArray(window.width) { window.lattice.lon((window.x0 + it).toDouble()) }
        for (r in 0 until window.height) {
            val lat = window.lattice.lat((window.y0 + r).toDouble())
            for (c in 0 until window.width) {
                val v = at(lat, lons[c])
                out[r * window.width + c] = v
                if (!v.isNaN()) any = true
            }
        }
        return if (any) out else null
    }
}
