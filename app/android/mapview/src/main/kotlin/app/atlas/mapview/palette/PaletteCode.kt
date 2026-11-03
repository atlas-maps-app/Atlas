package app.atlas.mapview.palette

import java.util.zip.CRC32

/**
 * A palette as a code to type, paste or scan elsewhere (the user, 1 November: "a simple value you can enter elsewhere
 * to transfer over a custom preset. Or a qr code"): `AT1-<preset>-<groups>`, the groups Crockford's base 32 of the
 * bytes `switches, tone, count, count × (slot, r, g, b), crc` in fives, e.g. `AT1-TODAY-K7QF2-M4XRB-9WZ3D`.
 *
 * The switches' byte is 255 for the preset's own, the Tone's its strength in steps of 5 % (0 to 40) or 255 for the
 * preset's, and the crc the low 16 bits of the CRC-32 of the preset's name and the bytes before it, so a mistyped
 * code is refused rather than read as other colours. Reading takes lower case, spaces and the look-alikes Crockford's
 * alphabet allows (O for 0, I and L for 1).
 */
object PaletteCode {
    const val PREFIX = "AT1"
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val KEEP = 255

    /** Why a code could not be read. */
    enum class Fault { FORM, PRESET, LETTER, CHECK, SLOT }

    /** A code read: the palette, or why not. */
    sealed interface Read {
        data class Ok(val palette: Palette) : Read
        data class Bad(val fault: Fault) : Read
    }

    /** The code of [palette]: its preset, then its changed colours, its Tone and its switches where it sets them. */
    fun write(palette: Palette): String {
        val out = ArrayList<Int>()
        out += palette.flags ?: KEEP
        out += palette.tone?.let { Math.round(it * 20f).coerceIn(0, 40) } ?: KEEP
        val changed = palette.colours.entries.sortedBy { it.key.code }
        require(changed.size <= 255) { "at most 255 colours" }
        out += changed.size
        for ((slot, c) in changed) {
            out += slot.code
            out += (c shr 16) and 0xFF
            out += (c shr 8) and 0xFF
            out += c and 0xFF
        }
        val crc = crc(palette.base.code, out)
        out += (crc shr 8) and 0xFF
        out += crc and 0xFF
        return "$PREFIX-${palette.base.code}-" + base32(out).chunked(5).joinToString("-")
    }

    /** The palette [code] holds. */
    fun read(code: String): Read {
        val parts = code.trim().uppercase().split('-', ' ').filter { it.isNotEmpty() }
        if (parts.size < 3 || parts[0] != PREFIX) return Read.Bad(Fault.FORM)
        val preset = Preset.ofCode(parts[1]) ?: return Read.Bad(Fault.PRESET)
        val bytes = unbase32(parts.drop(2).joinToString("")) ?: return Read.Bad(Fault.LETTER)
        if (bytes.size < 5) return Read.Bad(Fault.FORM)
        val n = bytes[2]
        if (bytes.size != 5 + 4 * n) return Read.Bad(Fault.FORM)
        val body = bytes.subList(0, bytes.size - 2)
        val crc = (bytes[bytes.size - 2] shl 8) or bytes[bytes.size - 1]
        if (crc != crc(preset.code, body)) return Read.Bad(Fault.CHECK)
        val flags = bytes[0].takeIf { it != KEEP }
        if (flags != null && flags and PaletteFlags.KNOWN.inv() != 0) return Read.Bad(Fault.FORM)
        val tone = bytes[1].takeIf { it != KEEP }?.let { if (it > 40) return Read.Bad(Fault.FORM) else it / 20f }
        val colours = LinkedHashMap<Slot, Int>()
        for (i in 0 until n) {
            val at = 3 + 4 * i
            val slot = Slot.ofCode(bytes[at]) ?: return Read.Bad(Fault.SLOT)
            colours[slot] = (bytes[at + 1] shl 16) or (bytes[at + 2] shl 8) or bytes[at + 3]
        }
        return Read.Ok(Palette(preset, colours, tone, flags))
    }

    private fun crc(preset: String, bytes: List<Int>): Int {
        val c = CRC32()
        c.update(preset.toByteArray(Charsets.US_ASCII))
        c.update(ByteArray(bytes.size) { bytes[it].toByte() })
        return (c.value and 0xFFFF).toInt()
    }

    private fun base32(bytes: List<Int>): String {
        val sb = StringBuilder()
        var acc = 0
        var bits = 0
        for (b in bytes) {
            acc = (acc shl 8) or b
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(acc shr (bits - 5)) and 31])
                bits -= 5
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(acc shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** The bytes of Crockford's base 32 [text], or null for a letter outside it or padding that is not zero. */
    private fun unbase32(text: String): List<Int>? {
        val out = ArrayList<Int>()
        var acc = 0
        var bits = 0
        for (ch in text) {
            val v = when (ch) {
                'O' -> 0
                'I', 'L' -> 1
                else -> ALPHABET.indexOf(ch).takeIf { it >= 0 } ?: return null
            }
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                out += (acc shr (bits - 8)) and 0xFF
                bits -= 8
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits >= 5 || acc != 0) return null
        return out
    }
}
