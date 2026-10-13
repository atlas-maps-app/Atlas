package app.atlas.tiles

import org.junit.Assert.assertTrue
import org.junit.Test

/** Decode speed on the densest fixture, 14/8414/5384 (912 KB). The brief's target is under 60 ms after warm-up. */
class MvtBenchmarkTest {
    private val bytes = Fixtures.bytes(Fixtures.CENTRE)

    /** Decodes everything: every layer, feature, geometry, tag and value. Returns a checksum so nothing is optimised away. */
    private fun decodeAll(input: ByteArray): Long {
        var sum = 0L
        val tile = Mvt.decode(input)
        for (layer in tile.layers.values) {
            sum += layer.values.size
            for (f in layer.features) {
                val g = f.geometry
                sum += g.pointCount + g.partCount + f.tags.size
            }
        }
        return sum
    }

    private fun median(runs: Int, block: () -> Unit): Double {
        val times = DoubleArray(runs) {
            val t0 = System.nanoTime()
            block()
            (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        return times[runs / 2]
    }

    @Test
    fun decodeIsFast() {
        val gz = Gzip.compress(bytes)
        repeat(40) { decodeAll(bytes); decodeAll(gz) }
        var check = 0L
        val index = median(31) { check += Mvt.decode(bytes).layers.values.sumOf { it.size } }
        val full = median(31) { check += decodeAll(bytes) }
        val fullGzip = median(31) { check += decodeAll(gz) }
        val gunzip = median(31) { check += Gzip.decompress(gz).size }
        println(String.format(java.util.Locale.ROOT, "MVT 14/8414/5384 (%d KB): index %.2f ms, full %.2f ms, full from gzip %.2f ms (gunzip %.2f ms) [%d]",
            bytes.size / 1024, index, full, fullGzip, gunzip, check))
        // Generous bounds keep the test steady on a busy machine; the printed medians are the real numbers.
        assertTrue("full decode $full ms", full < 60)
        assertTrue("full decode from gzip $fullGzip ms", fullGzip < 90)
    }
}
