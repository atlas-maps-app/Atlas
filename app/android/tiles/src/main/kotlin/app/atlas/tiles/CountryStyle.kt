package app.atlas.tiles

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * A window's style as its country's tiles hold it (ENGINE.md step 3, one pack per country: its size). Every window of a
 * country carries the same `style` section but for its region record (the scene's region, view and R: 137 of
 * Noord-Holland's 7,841 bytes), and compressed one by one the styles were 7 % of the Netherlands' tiles. So the tiles
 * file holds the country's style once, in its index ([TilesIndex.style]), and each window's as what differs from it
 * ([difference]); the phone writes the window's style back whole into its pack, stored, before the core reads it
 * ([unpack]). Every other byte of the pack is as the tiles file holds it.
 *
 * In a window's pack as the tiles file holds it ([store]), the style is the last section, its method [METHOD] (none of
 * the core's: 0 stored, 1 deflate, 2 zstd), its stored bytes the difference, its raw length the style's and its CRC-32
 * over the difference. A difference is the length of the longest prefix the style shares with the country's (a
 * varint), of the longest suffix it shares with the rest (a varint), and the bytes between.
 */
object CountryStyle {
    /** The directory's method of a style held as what differs from the country's. */
    const val METHOD = 0x80

    /** The core's method of a section stored as it is. */
    const val STORED = 0

    /** The core's name of the section. */
    const val SECTION = "style"

    private const val MAGIC = "ATLASPCK"

    /** The container's header: the magic, a zero byte, the format, two zero bytes, the manifest's and the directory's lengths (big-endian u32). */
    private const val HEADER = 20

    /** A directory entry after its name: offset, stored and raw lengths (big-endian u64), CRC-32 (u32) and method. */
    private const val ENTRY = 29

    /** The difference of [raw] from the country's [template]. */
    fun difference(template: ByteArray, raw: ByteArray): ByteArray {
        var p = 0
        while (p < raw.size && p < template.size && raw[p] == template[p]) p++
        var q = 0
        while (q < raw.size - p && q < template.size - p && raw[raw.size - 1 - q] == template[template.size - 1 - q]) q++
        val out = ByteArrayOutputStream(raw.size - p - q + 10)
        varint(out, p)
        varint(out, q)
        out.write(raw, p, raw.size - p - q)
        return out.toByteArray()
    }

    /** The style whose [difference] from the country's [template] it is; throws IllegalArgumentException where none is. */
    fun apply(template: ByteArray, difference: ByteArray): ByteArray {
        val at = intArrayOf(0)
        val p = varint(difference, at)
        val q = varint(difference, at)
        require(p.toLong() + q <= template.size) { "a style sharing $p and $q bytes with a country's of ${template.size}" }
        val between = difference.size - at[0]
        val out = ByteArray(p + between + q)
        template.copyInto(out, 0, 0, p)
        difference.copyInto(out, p, at[0], difference.size)
        template.copyInto(out, p + between, template.size - q, template.size)
        return out
    }

    /**
     * [pack], a window's as the core writes it with its style the last section, whose raw bytes are [raw], as the
     * country's tiles hold it: its style as what differs from the country's [template].
     */
    fun store(pack: ByteArray, raw: ByteArray, template: ByteArray): ByteArray {
        val style = style(pack)
        require(style.raw == raw.size.toLong()) { "a style of ${raw.size} bytes, its pack says ${style.raw}" }
        val d = difference(template, raw)
        val out = pack.copyOf(style.offset + d.size)
        d.copyInto(out, style.offset)
        entry(out, style, d, METHOD)
        return out
    }

    /**
     * The pack the core reads from [stored], a window's pack as its country's tiles hold it: its style written back
     * whole from the country's [template], stored; every other byte as it was. Throws IllegalArgumentException where
     * [stored] is not one.
     */
    fun unpack(stored: ByteArray, template: ByteArray): ByteArray {
        val style = style(stored)
        require(style.method == METHOD) { "the style's method is ${style.method}, not a country's style" }
        val raw = apply(template, stored.copyOfRange(style.offset, stored.size))
        require(raw.size.toLong() == style.raw) { "the style comes back in ${raw.size} bytes, its pack says ${style.raw}" }
        val out = stored.copyOf(style.offset + raw.size)
        raw.copyInto(out, style.offset)
        entry(out, style, raw, STORED)
        return out
    }

    /** One directory entry: where its fields lie in the pack ([at], after its name) and what they say. */
    private class Section(val name: String, val at: Int, val offset: Int, val stored: Long, val raw: Long, val method: Int)

    /** [pack]'s style: its last section, its bytes the pack's last. */
    private fun style(pack: ByteArray): Section {
        require(pack.size >= HEADER && String(pack, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC) { "not an Atlas pack" }
        val b = ByteBuffer.wrap(pack)
        val end = HEADER + (b.getInt(12).toLong() and 0xFFFFFFFFL) + (b.getInt(16).toLong() and 0xFFFFFFFFL)
        require(end <= pack.size) { "the manifest and the directory run past the pack" }
        val at = intArrayOf(HEADER + b.getInt(12))
        var last: Section? = null
        while (at[0] < end) {
            val n = varint(pack, at)
            require(n <= end - at[0] - ENTRY) { "a section's name runs past the directory" }
            val name = String(pack, at[0], n, Charsets.UTF_8)
            val e = at[0] + n
            val offset = b.getLong(e)
            val stored = b.getLong(e + 8)
            require(offset in end..pack.size && stored in 0..pack.size - offset) { "section $name lies past the pack" }
            last = Section(name, e, offset.toInt(), stored, b.getLong(e + 16), pack[e + 28].toInt() and 0xFF)
            at[0] = e + ENTRY
        }
        require(at[0].toLong() == end) { "the directory's entries do not fill it" }
        require(last != null && last.name == SECTION) { "the style is not the pack's last section" }
        require(last.offset + last.stored == pack.size.toLong()) { "${pack.size - last.offset - last.stored} bytes after the style" }
        return last
    }

    /** [out]'s directory entry [s] saying its bytes are [bytes], by [method]; its offset and raw length as they were. */
    private fun entry(out: ByteArray, s: Section, bytes: ByteArray, method: Int) {
        ByteBuffer.wrap(out).putLong(s.at + 8, bytes.size.toLong()).putInt(s.at + 24, CRC32().also { it.update(bytes) }.value.toInt())
        out[s.at + 28] = method.toByte()
    }

    private fun varint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    /** An unsigned varint of [b] at [at] (moved past it), within an Int. */
    private fun varint(b: ByteArray, at: IntArray): Int {
        var v = 0L
        var shift = 0
        while (true) {
            require(at[0] < b.size && shift <= 28) { "a varint runs past its bytes" }
            val c = b[at[0]++].toInt() and 0xFF
            v = v or ((c and 0x7F).toLong() shl shift)
            if (c < 0x80) break
            shift += 7
        }
        require(v <= Int.MAX_VALUE) { "a varint of $v" }
        return v.toInt()
    }
}
