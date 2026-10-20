package app.atlas.tiles

import java.io.ByteArrayOutputStream
import java.io.File

/** A protobuf writer for building test data. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(v: Long): ProtoWriter {
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
        return this
    }

    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    fun uint(field: Int, v: Long) = tag(field, 0).varint(v)
    fun sint(field: Int, v: Long) = tag(field, 0).varint((v shl 1) xor (v shr 63))
    fun bytes(field: Int, b: ByteArray) = tag(field, 2).varint(b.size.toLong()).raw(b)
    fun string(field: Int, s: String) = bytes(field, s.toByteArray())
    fun message(field: Int, w: ProtoWriter) = bytes(field, w.toByteArray())
    fun fixed32(field: Int, v: Int) = tag(field, 5).raw(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
    fun fixed64(field: Int, v: Long) = tag(field, 1).raw(ByteArray(8) { (v shr (8 * it)).toByte() })
    fun float(field: Int, f: Float) = fixed32(field, java.lang.Float.floatToIntBits(f))
    fun double(field: Int, d: Double) = fixed64(field, java.lang.Double.doubleToLongBits(d))

    fun packed(field: Int, values: IntArray): ProtoWriter {
        val p = ProtoWriter()
        for (v in values) p.varint(v.toLong() and 0xffffffffL)
        return bytes(field, p.toByteArray())
    }

    fun raw(b: ByteArray): ProtoWriter { out.write(b); return this }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** A directory of its own under the build's temporary folder for one test. */
fun tempDir(name: String): File =
    kotlin.io.path.createTempDirectory("atlas-tiles-$name").toFile().also { it.deleteOnExit() }
