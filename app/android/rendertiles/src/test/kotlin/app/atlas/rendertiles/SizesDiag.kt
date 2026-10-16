package app.atlas.rendertiles

import app.atlas.rendertiles.codec.LeSource
import app.atlas.rendertiles.codec.StringTable
import app.atlas.rendertiles.model.Codec
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Layer
import com.github.luben.zstd.Zstd

/** Where a pack's bytes go: per layer and column, raw and zstd'd alone (a diagnostic for tests). */
object SizesDiag {
    fun report(b: Packs.Built): String {
        val raw = HashMap<String, Long>()
        val packed = HashMap<String, Long>()
        var strings = 0L
        var stringsZ = 0L
        for (c in b.reader.chunks) {
            val t = b.reader.raw(c)
            val h = LeSource(t, 0, t.size)
            h.u32(); h.u16(); h.u8(); h.u8(); h.u32(); h.u32(); repeat(4) { h.i32() }
            val sAt = h.u32().toInt(); val sLen = h.u32().toInt(); h.u32(); h.u32()
            strings += sLen
            stringsZ += Zstd.compress(t.copyOfRange(sAt, sAt + sLen), 19).size
            val names = StringTable.read(LeSource(t, sAt, sAt + sLen))
            val n = h.u16(); h.u16()
            repeat(n) {
                val id = h.u8(); h.u8(); val cols = h.u16(); h.u32(); val at = h.u32().toInt(); val len = h.u32().toInt()
                val src = LeSource(t, at, at + len)
                repeat(cols) {
                    val name = names[src.varCount()]
                    val codec = Codec.of(src.u8())
                    val blk = src.varCount()
                    val bytes = src.bytes(blk)
                    val key = "${Layer.of(id)!!.label}.$name:${codec!!.name}"
                    raw.merge(key, bytes.size.toLong(), Long::plus)
                    packed.merge(key, Zstd.compress(bytes, 19).size.toLong(), Long::plus)
                }
            }
        }
        val sb = StringBuilder("strings raw $strings zstd $stringsZ\n")
        for ((k, v) in packed.entries.sortedByDescending { it.value }.take(25)) sb.append("  $k raw ${raw[k]} zstd $v\n")
        val byLayer = packed.entries.groupBy { it.key.substringBefore('.') }.mapValues { e -> e.value.sumOf { it.value } }
        sb.append("by layer (zstd alone): $byLayer\n")
        return sb.toString()
    }
}
