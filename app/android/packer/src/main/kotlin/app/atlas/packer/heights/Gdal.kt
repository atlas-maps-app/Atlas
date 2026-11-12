package app.atlas.packer.heights

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * GDAL's command-line tools, which terrain.yml's job has (`gdal-bin`; MIT, a tool of the job, never in the app): the
 * national models lie in national grids (the Dutch RD, UTM zones), and `gdalwarp -r average` brings them onto the
 * lattice, each node the average of the source's cells under it, never the nearest one (which aliases dikes and
 * ditches). Overviews are never read in a warp (`-ovr NONE`): a host's overviews may be nearest picks (Kartverket's
 * are). Every node's place in the source is computed exactly (`-et 0`): gdalwarp's own shortcut may put it an eighth of
 * a sample off, which on a dune's slope is several steps of height, and would differ from one window to the next.
 */
class Gdal(private val bin: File? = null) {
    private fun tool(name: String): String = bin?.let { File(it, name).path } ?: name

    /** Runs [name] with [args] (and [stdin]); what it wrote to its output, or an exception with what it said (its warnings are no part of its output). */
    fun run(name: String, args: List<String>, stdin: String? = null, minutes: Long = 120): String {
        val errors = File.createTempFile("gdal", ".err")
        try {
            val p = try {
                ProcessBuilder(listOf(tool(name)) + args).redirectError(errors).also { b ->
                    // what GDAL reads from a host itself (the checks' windows) names the packer as the packer's own reads do
                    b.environment()["GDAL_HTTP_USERAGENT"] = Fetcher.USER_AGENT
                    b.environment()["GDAL_HTTP_MAX_RETRY"] = "5"
                    b.environment()["GDAL_HTTP_RETRY_DELAY"] = "5"
                    b.environment()["GDAL_DISABLE_READDIR_ON_OPEN"] = "EMPTY_DIR"
                    b.environment()["CPL_VSIL_CURL_ALLOWED_EXTENSIONS"] = ".tif"
                }.start()
            } catch (e: IOException) {
                throw IOException("$name does not start (is GDAL installed? terrain.yml installs gdal-bin): ${e.message}")
            }
            if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                throw IOException("$name ${args.joinToString(" ")} did not end in $minutes minutes")
            }
            if (p.exitValue() != 0) throw IOException("$name ${args.joinToString(" ")} failed (${p.exitValue()}): ${(out + errors.readText()).takeLast(2000)}")
            return out
        } finally {
            errors.delete()
        }
    }

    /**
     * The box [box] (west, south, east, north in its own CRS) of raster [input] (a file, or `/vsicurl/` and a URL: GDAL
     * then reads the host by ranges) into [out] as a GeoTIFF: of its overview [overview] (1 the first), or of the
     * raster itself (0).
     */
    fun cut(input: String, overview: Int, box: DoubleArray, out: File) {
        val args = ArrayList<String>(listOf("-q", "-of", "GTiff"))
        if (overview > 0) args += listOf("-ovr", "${overview - 1}") else args += listOf("-ovr", "NONE")
        args += listOf("-projwin", fmt(box[0]), fmt(box[3]), fmt(box[2]), fmt(box[1]), input, out.path)
        run("gdal_translate", args)
    }

    /** Whether the tools are there. */
    fun available(): Boolean = try {
        run("gdalwarp", listOf("--version")).isNotEmpty()
    } catch (e: IOException) {
        false
    }

    /** [points] (x, y: longitude and latitude for `OGC:CRS84`) of CRS [from] in CRS [to]. */
    fun transform(points: List<DoubleArray>, from: String, to: String): List<DoubleArray> {
        if (points.isEmpty()) return emptyList()
        val out = run("gdaltransform", listOf("-s_srs", from, "-t_srs", to), points.joinToString("\n", postfix = "\n") { "${it[0]} ${it[1]}" })
        val lines = out.lines().filter { it.isNotBlank() }
        if (lines.size != points.size) throw IOException("gdaltransform gave ${lines.size} points for ${points.size}: ${out.take(500)}")
        return lines.map { l -> l.trim().split(Regex("\\s+")).let { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) } }
    }

    /** The box (west, south, east, north) in CRS [to] of [box] in CRS [from], its edges followed at [steps] points each. */
    fun transformBox(box: DoubleArray, from: String, to: String, steps: Int = 16): DoubleArray {
        val pts = ArrayList<DoubleArray>()
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = box[0] + (box[2] - box[0]) * t
            val y = box[1] + (box[3] - box[1]) * t
            pts += doubleArrayOf(x, box[1]); pts += doubleArrayOf(x, box[3]); pts += doubleArrayOf(box[0], y); pts += doubleArrayOf(box[2], y)
        }
        val t = transform(pts, from, to).filter { it[0].isFinite() && it[1].isFinite() }
        if (t.isEmpty()) throw IOException("the box ${box.joinToString()} of $from has no place in $to")
        return doubleArrayOf(t.minOf { it[0] }, t.minOf { it[1] }, t.maxOf { it[0] }, t.maxOf { it[1] })
    }

    /** A virtual mosaic of [files] in [vrt], in CRS [crs] whatever the files say of theirs. */
    fun buildVrt(files: List<File>, vrt: File, crs: String) {
        val list = File(vrt.path + ".txt")
        list.writeText(files.joinToString("\n", postfix = "\n") { it.absolutePath })
        run("gdalbuildvrt", listOf("-q", "-overwrite", "-a_srs", crs, "-input_file_list", list.path, vrt.path))
    }

    /**
     * [input] warped onto [window]'s nodes: each node the average of the source's cells under its square of the
     * lattice (or, with [resampling], another of gdalwarp's methods); NaN where it has none; null where it has none in
     * the whole window. [scratch] is a file name of this call's own.
     */
    fun warp(input: File, window: Window, scratch: File, resampling: String = "average"): FloatArray? {
        val b = window.bounds()
        val hdr = File(scratch.path.removeSuffix(".bil") + ".hdr")
        try {
            run(
                "gdalwarp",
                listOf(
                    "-q", "-overwrite", "-ovr", "NONE", "-et", "0", "-t_srs", "EPSG:3857", "-te", fmt(b[0]), fmt(b[1]), fmt(b[2]), fmt(b[3]), "-ts", "${window.width}", "${window.height}",
                    "-r", resampling, "-ot", "Float32", "-of", "ENVI", "-dstnodata", "nan", "-wm", "256", input.path, scratch.path,
                ),
            )
            val header = hdr.takeIf { it.isFile }?.readText() ?: throw IOException("gdalwarp wrote no header for $scratch")
            if (!Regex("byte order\\s*=\\s*0").containsMatchIn(header)) throw IOException("gdalwarp's raster is not little-endian")
            val bytes = scratch.readBytes()
            if (bytes.size != window.size * 4) throw IOException("gdalwarp wrote ${bytes.size} bytes for ${window.width} by ${window.height} nodes")
            val out = FloatArray(window.size)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            var any = false
            for (i in out.indices) {
                // a height no place on Earth has is a nodata value the source did not declare
                if (out[i] < -1000f || out[i] > 10000f) out[i] = Float.NaN
                if (!out[i].isNaN()) any = true
            }
            return if (any) out else null
        } finally {
            scratch.delete()
            hdr.delete()
            File(scratch.path + ".aux.xml").delete()
        }
    }

    private fun fmt(v: Double): String = "%.6f".format(java.util.Locale.ROOT, v)
}
