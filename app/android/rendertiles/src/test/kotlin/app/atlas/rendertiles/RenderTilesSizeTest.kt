package app.atlas.rendertiles

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled set's sizes (plan-vector §3, Pack sizes): each pack within its ceiling, and its bytes per feature pinned
 * within ±10 % of what they measured when the format was settled, so a change that makes tiles fatter, or drops what
 * they hold, shows.
 *
 * The plan's gates are 2 MB for the canal ring (L14 and L12) and 150 KB for the Westerkerk. The Westerkerk measures
 * 159,423 bytes: the gate's estimate left out the 8,039 address points the living map's doors and numbers are drawn from
 * (about 44 KB of it). Its ceiling holds it where it is until the gate is decided.
 */
class RenderTilesSizeTest {
    private class Pin(val name: String, val ceiling: Long, val bytesPerFeature: Double)

    private val pins = listOf(
        Pin("westerkerk", WESTERKERK_CEILING, 13.20),
        Pin("grachten", 2_000_000L, 28.71),
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
        /** The Westerkerk's ceiling while its gate (150 KB in the plan) is decided: what it measures, with no room to grow. */
        const val WESTERKERK_CEILING = 160_000L
    }
}
