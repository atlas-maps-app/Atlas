package app.atlas.packer.world

import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.LinearComponentExtracter
import org.locationtech.jts.geom.util.PolygonExtracter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shapefiles as osmdata and Natural Earth ship them, written for the tests (the ESRI and dBASE layouts, written here
 * independently of [Shapefile]): one layer's `.shp`, `.dbf` and `.cpg` in a zip. Every record of a layer is a point,
 * a polyline or a polygon (shells written clockwise, holes counter-clockwise), with its attributes: text, integers and
 * decimals.
 */
object ShapefileWriter {
    /** A record: its geometry in the layer's coordinates and its attributes. */
    class Record(val geometry: Geometry, val attrs: Map<String, Any?> = emptyMap())

    /** Writes layer [name] of [records] into the zip [file]. */
    fun zip(file: File, name: String, records: List<Record>) {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((ext, bytes) in listOf("shp" to shp(records), "dbf" to dbf(records), "cpg" to "UTF-8".toByteArray())) {
                z.putNextEntry(ZipEntry("$name/$name.$ext"))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }

    private fun type(g: Geometry): Int = when (g) {
        is Point -> 1
        is LineString -> 3
        else -> if (g.geometryType.contains("LineString")) 3 else 5
    }

    /** The .shp of [records]: the 100-byte header, then each record's number, length and content. */
    fun shp(records: List<Record>): ByteArray {
        val type = records.firstOrNull()?.let { type(it.geometry) } ?: 5
        val contents = records.map { content(it.geometry) }
        val length = 100 + contents.sumOf { 8 + it.size }
        val b = ByteBuffer.allocate(length)
        b.order(ByteOrder.BIG_ENDIAN).putInt(0, 9994).putInt(24, length / 2)
        b.order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1000).putInt(32, type)
        val env = records.fold(org.locationtech.jts.geom.Envelope()) { e, r -> e.apply { expandToInclude(r.geometry.envelopeInternal) } }
        b.putDouble(36, env.minX).putDouble(44, env.minY).putDouble(52, env.maxX).putDouble(60, env.maxY)
        b.position(100)
        for ((i, c) in contents.withIndex()) {
            b.order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(c.size / 2)
            b.put(c)
        }
        return b.array()
    }

    private fun content(g: Geometry): ByteArray {
        if (g is Point) {
            return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putDouble(g.x).putDouble(g.y).array()
        }
        val polygonal = type(g) == 5
        val parts: List<Array<Coordinate>> = if (polygonal) {
            PolygonExtracter.getPolygons(g).flatMap { p ->
                p as Polygon
                val shell = p.exteriorRing.coordinates.let { if (Orientation.isCCW(it)) it.reversedArray() else it }
                val holes = (0 until p.numInteriorRing).map { k -> p.getInteriorRingN(k).coordinates.let { if (Orientation.isCCW(it)) it else it.reversedArray() } }
                listOf(shell) + holes
            }
        } else {
            LinearComponentExtracter.getLines(g).map { (it as LineString).coordinates }
        }
        val points = parts.sumOf { it.size }
        val b = ByteBuffer.allocate(44 + 4 * parts.size + 16 * points).order(ByteOrder.LITTLE_ENDIAN)
        val env = g.envelopeInternal
        b.putInt(if (polygonal) 5 else 3).putDouble(env.minX).putDouble(env.minY).putDouble(env.maxX).putDouble(env.maxY)
        b.putInt(parts.size).putInt(points)
        var start = 0
        for (p in parts) {
            b.putInt(start)
            start += p.size
        }
        for (p in parts) for (c in p) b.putDouble(c.x).putDouble(c.y)
        return b.array()
    }

    /** The .dbf of [records]' attributes: a character field for text, a numeric one for numbers, as the first has them. */
    fun dbf(records: List<Record>): ByteArray {
        val names = records.flatMap { it.attrs.keys }.distinct()
        class Field(val name: String, val type: Char, val length: Int)
        val fields = names.map { n ->
            val values = records.mapNotNull { it.attrs[n] }
            if (values.all { it is Number }) Field(n, 'N', 24) else Field(n, 'C', maxOf(1, values.maxOfOrNull { it.toString().toByteArray().size } ?: 1))
        }
        val headerBytes = 32 + 32 * fields.size + 1
        val recordBytes = 1 + fields.sumOf { it.length }
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        head.put(0, 3).put(1, 126).put(2, 10).put(3, 31).putInt(4, records.size).putShort(8, headerBytes.toShort()).putShort(10, recordBytes.toShort())
        out.write(head.array())
        for (f in fields) {
            val d = ByteArray(32)
            f.name.toByteArray(Charsets.US_ASCII).copyInto(d, 0, 0, minOf(10, f.name.length))
            d[11] = f.type.code.toByte()
            d[16] = f.length.toByte()
            d[17] = if (f.type == 'N') 6 else 0
            out.write(d)
        }
        out.write(0x0D)
        for (r in records) {
            out.write(' '.code)
            for (f in fields) {
                val v = r.attrs[f.name]
                val text = when {
                    v == null -> ""
                    f.type == 'N' && (v is Double || v is Float) -> "%.6f".format(java.util.Locale.ROOT, (v as Number).toDouble())
                    else -> v.toString()
                }
                val bytes = text.toByteArray(Charsets.UTF_8)
                val cell = ByteArray(f.length) { ' '.code.toByte() }
                if (f.type == 'N') bytes.copyInto(cell, f.length - bytes.size) else bytes.copyInto(cell, 0)
                out.write(cell)
            }
        }
        out.write(0x1A)
        return out.toByteArray()
    }
}
