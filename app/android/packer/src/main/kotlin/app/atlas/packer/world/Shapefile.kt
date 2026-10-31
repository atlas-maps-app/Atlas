package app.atlas.packer.world

import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Location
import org.locationtech.jts.geom.Polygon
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * ESRI shapefiles read straight from their zips (osmdata's land polygons, Natural Earth's layers), so the packer needs no
 * GIS library: points, polylines and polygons (shape types 1, 3 and 5, and their Z and M forms with those ignored), and
 * the dBASE attributes beside them. Polygon rings are the shapefile's: clockwise shells, each followed by its
 * counter-clockwise holes.
 */
object Shapefile {
    /** One shape: its record's place in the file, its geometry (null for a null shape) and its attributes. */
    class Shape(val index: Int, val geometry: Geometry?, val attrs: Map<String, Any?>)

    /**
     * The shapes of layer [name] in [zip] (the entry ending in `<name>.shp` and its `.dbf`, read with the `.cpg`'s
     * charset, UTF-8 where it has none) whose box meets [filter] (null: all), each with its attributes, in the file's
     * order. Records outside [filter] are skipped unread.
     */
    fun read(zip: File, name: String, factory: GeometryFactory, filter: Envelope? = null, attributes: Boolean = true): List<Shape> {
        ZipFile(zip).use { z ->
            fun entry(ext: String) = z.entries().asSequence().firstOrNull { it.name.endsWith("$name.$ext") && !it.isDirectory }
            val shp = entry("shp") ?: throw IllegalArgumentException("$zip holds no $name.shp")
            val geometries = ArrayList<Pair<Int, Geometry?>>()
            z.getInputStream(shp).use { readShp(it, factory, filter) { i, g -> geometries += i to g } }
            val attrs: List<Map<String, Any?>> = if (!attributes) emptyList() else {
                val charset = entry("cpg")?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.US_ASCII).trim() } }
                    ?.let { runCatching { Charset.forName(if (it.equals("UTF8", true)) "UTF-8" else it) }.getOrNull() } ?: Charsets.UTF_8
                entry("dbf")?.let { e -> z.getInputStream(e).use { readDbf(it, charset) } } ?: emptyList()
            }
            return geometries.map { (i, g) -> Shape(i, g, attrs.getOrNull(i) ?: emptyMap()) }
        }
    }

    /** The records of a .shp stream: each one whose box meets [filter], as its index and geometry. */
    fun readShp(input: InputStream, factory: GeometryFactory, filter: Envelope?, each: (Int, Geometry?) -> Unit) {
        val data = DataInputStream(BufferedInputStream(input, 1 shl 20))
        val head = ByteArray(100)
        data.readFully(head)
        val hb = ByteBuffer.wrap(head)
        require(hb.order(ByteOrder.BIG_ENDIAN).getInt(0) == 9994) { "not a shapefile" }
        val fileBytes = hb.order(ByteOrder.BIG_ENDIAN).getInt(24).toLong() * 2
        var at = 100L
        var index = 0
        val recHead = ByteArray(8)
        var buf = ByteArray(1 shl 16)
        while (at < fileBytes) {
            try {
                data.readFully(recHead)
            } catch (e: EOFException) {
                break
            }
            val contentBytes = ByteBuffer.wrap(recHead).order(ByteOrder.BIG_ENDIAN).getInt(4) * 2
            at += 8 + contentBytes
            if (buf.size < contentBytes) buf = ByteArray(maxOf(contentBytes, buf.size * 2))
            // the type and the box first: a record outside the filter is skipped unread
            val first = minOf(contentBytes, 36)
            data.readFully(buf, 0, first)
            val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val type = if (first >= 4) b.getInt(0) else 0
            val base = baseType(type)
            if (base == 3 || base == 5) {
                val box = Envelope(b.getDouble(4), b.getDouble(20), b.getDouble(12), b.getDouble(28))
                if (filter != null && !filter.intersects(box)) {
                    data.skipNBytes((contentBytes - first).toLong())
                    index++
                    continue
                }
            }
            data.readFully(buf, first, contentBytes - first)
            each(index, geometry(b, base, factory, filter))
            index++
        }
    }

    private fun baseType(type: Int): Int = when (type) {
        0 -> 0
        1, 11, 21 -> 1
        3, 13, 23 -> 3
        5, 15, 25 -> 5
        else -> throw IllegalArgumentException("shape type $type")
    }

    private fun geometry(b: ByteBuffer, base: Int, factory: GeometryFactory, filter: Envelope?): Geometry? {
        when (base) {
            0 -> return null
            1 -> {
                val c = Coordinate(b.getDouble(4), b.getDouble(12))
                return if (filter == null || filter.contains(c)) factory.createPoint(c) else null
            }
        }
        val parts = b.getInt(36)
        val points = b.getInt(40)
        val starts = IntArray(parts) { b.getInt(44 + 4 * it) }
        val p0 = 44 + 4 * parts
        val rings = (0 until parts).map { k ->
            val from = starts[k]
            val to = if (k + 1 < parts) starts[k + 1] else points
            Array(to - from) { i -> Coordinate(b.getDouble(p0 + 16 * (from + i)), b.getDouble(p0 + 16 * (from + i) + 8)) }
        }
        if (base == 3) return factory.createMultiLineString(rings.filter { it.size >= 2 }.map { factory.createLineString(it) }.toTypedArray())
        return polygons(rings, factory)
    }

    /** Shapefile rings as polygons: each clockwise ring a shell, each counter-clockwise one a hole of the shell holding it. */
    fun polygons(rings: List<Array<Coordinate>>, factory: GeometryFactory): Geometry {
        val shells = ArrayList<LinearRing>()
        val holes = ArrayList<LinearRing>()
        for (r in rings) {
            if (r.size < 4) continue
            val closed = if (r.first().equals2D(r.last())) r else r + r.first()
            val ring = factory.createLinearRing(closed)
            if (Orientation.isCCW(ring.coordinateSequence)) holes += ring else shells += ring
        }
        if (shells.isEmpty()) return factory.createMultiPolygon(arrayOf())
        if (shells.size == 1) return factory.createMultiPolygon(arrayOf(factory.createPolygon(shells[0], holes.toTypedArray())))
        val own = shells.map { ArrayList<LinearRing>() }
        val shellPolys = shells.map { factory.createPolygon(it) }
        val locators = shellPolys.map { IndexedPointInAreaLocator(it) }
        for (h in holes) {
            val p = h.getCoordinateN(0)
            val k = shellPolys.indices.firstOrNull { shellPolys[it].envelopeInternal.contains(h.envelopeInternal) && locators[it].locate(p) != Location.EXTERIOR }
                ?: shellPolys.indices.firstOrNull { shellPolys[it].envelopeInternal.contains(h.envelopeInternal) }
            if (k != null) own[k] += h
        }
        return factory.createMultiPolygon(Array<Polygon>(shells.size) { factory.createPolygon(shells[it], own[it].toTypedArray()) })
    }

    /** A .dbf stream's records, each its fields by name: text trimmed, numbers as Long or Double, logicals as Boolean. */
    fun readDbf(input: InputStream, charset: Charset): List<Map<String, Any?>> {
        val data = DataInputStream(BufferedInputStream(input, 1 shl 16))
        val head = ByteArray(32)
        data.readFully(head)
        val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val count = hb.getInt(4)
        val headerBytes = hb.getShort(8).toInt() and 0xFFFF
        val recordBytes = hb.getShort(10).toInt() and 0xFFFF
        val fieldBytes = ByteArray(headerBytes - 32)
        data.readFully(fieldBytes)
        class Field(val name: String, val type: Char, val length: Int)
        val fields = ArrayList<Field>()
        var o = 0
        while (o + 32 <= fieldBytes.size && fieldBytes[o] != 0x0D.toByte()) {
            val nameEnd = (0 until 11).firstOrNull { fieldBytes[o + it] == 0.toByte() } ?: 11
            fields += Field(String(fieldBytes, o, nameEnd, Charsets.US_ASCII), (fieldBytes[o + 11].toInt() and 0xFF).toChar(), fieldBytes[o + 16].toInt() and 0xFF)
            o += 32
        }
        val out = ArrayList<Map<String, Any?>>(count)
        val rec = ByteArray(recordBytes)
        repeat(count) {
            data.readFully(rec)
            var at = 1
            val m = LinkedHashMap<String, Any?>()
            for (f in fields) {
                val text = String(rec, at, f.length, if (f.type == 'C') charset else Charsets.US_ASCII).trim()
                at += f.length
                m[f.name] = when (f.type) {
                    'N', 'F' -> if (text.isEmpty() || text.startsWith('*')) null else text.toLongOrNull() ?: text.toDoubleOrNull()
                    'L' -> when (text.uppercase()) { "Y", "T" -> true; "N", "F" -> false; else -> null }
                    else -> text.ifEmpty { null }
                }
            }
            out += m
        }
        return out
    }
}
