package app.atlas.rendertiles

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled set's sizes (plan-vector §3, Pack sizes): each pack within its ceiling, and its bytes per feature pinned
 * within ±10 % of what they measured when the format was settled, so a change that makes tiles fatter, or drops what
 * they hold, shows.
 *
 * The gates are 2 MB for the canal ring (L14 and L12) and 162 KB for the Westerkerk (the plan's 150 KB left out the
 * 8,039 address points the living map's doors and numbers are drawn from: about 44 KB of the pack, 7 KB more with the
 * building each door is on; and 2 KB more for the style file every pack carries, which grows with each look the core
 * draws: step 4's windows, sills, shop glazing, plinths and cornices took the pack past 160 KB, its runways',
 * taxiways' and park edges' tables past 161 KB).
 */
class RenderTilesSizeTest {
    private class Pin(val name: String, val ceiling: Long, val bytesPerFeature: Double)

    private val pins = listOf(
        Pin("westerkerk", WESTERKERK_CEILING, 13.21),
        Pin("grachten", 2_000_000L, 28.33),
    )

    @Test
    fun `the bundled packs keep within their sizes`() {
        for (p in pins) {
            val file = if (p.name == "westerkerk") Packs.westerkerkPack else Packs.grachtenPack
            if (!file.isFile) continue
            val b = Packs.build(file, p.name)
            val bytes = b.file.length()
            val features = b.written.chunks.sumOf { it.count }
            val per = bytes.toDouble() / features
            println("SIZE ${p.name}: $bytes bytes (ceiling ${p.ceiling}), $features features, %.2f bytes a feature (pinned %.2f)".format(per, p.bytesPerFeature))
            println("SIZE " + SizesDiag.report(b).trimEnd().replace("\n", "\nSIZE "))
            assertTrue("${p.name} is $bytes bytes", bytes <= p.ceiling)
            assertTrue("${p.name}: %.2f bytes a feature, pinned at %.2f ± 10 %%".format(per, p.bytesPerFeature), Math.abs(per / p.bytesPerFeature - 1) <= 0.10)
        }
    }

    companion object {
        /** The Westerkerk's gate. */
        const val WESTERKERK_CEILING = 162_000L
    }
}
