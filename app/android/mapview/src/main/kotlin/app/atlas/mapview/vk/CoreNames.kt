package app.atlas.mapview.vk

import android.graphics.Typeface
import android.os.Handler
import android.util.Log
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.state.LabelBlock
import app.atlas.mapview.LabelKind
import app.atlas.mapview.LabelPainter
import app.atlas.mapview.LabelSprites
import app.atlas.mapview.MapEngine
import app.atlas.mapview.MapLabel
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The world's names for one map's core (ENGINE.md step 5, "Names"): the core picks the names its frames show and says
 * which it has no sprite of ([MapCore.namesWanted]: id, look, text); each is drawn here with Android's own text stack,
 * as the boards' labels are ([LabelSprites]: at its own size and at the band's top, in the boards' faces where they
 * hold the glyphs and the system's fonts where they do not), and handed back ([MapCore.names]). A name the device has
 * no glyphs for goes back without an image, and the core leaves it out.
 *
 * A step follows a presented frame ([framePresented], at most every [STEP_MS]): a frame is what can make the core want
 * a name, and the sprites handed back make it draw another, so the steps go on while names come and end with them, at
 * rest too. The drawing runs on one background thread of its own.
 */
internal class CoreNames(
    private val core: MapCore,
    private val main: Handler,
    private val worker: Executor = shared,
) {
    /** What the names are drawn with: the screen's density, the boards' faces and the readable floor, dp. */
    class Look(val density: Float, val face: Typeface?, val globeFace: Typeface?, val floorDp: Float)

    /** Set by the map (main thread) whenever its density, faces or Android's text size change; null: no names yet. */
    @Volatile
    var look: Look? = null

    /** Names drawn and handed over, and names skipped for want of glyphs, since the map began (logs and tests). */
    @Volatile
    var handed = 0
        private set

    @Volatile
    var skipped = 0
        private set

    @Volatile
    private var posted = false

    @Volatile
    private var closed = false
    private val busy = AtomicBoolean(false)

    @Volatile
    private var again = false

    // the worker's: the sprites' maker for the look's density
    private var sprites: LabelSprites? = null

    private val step = Runnable {
        posted = false
        if (closed) return@Runnable
        if (!busy.compareAndSet(false, true)) {
            again = true
            return@Runnable
        }
        worker.execute {
            try {
                hand()
            } catch (e: RuntimeException) {
                Log.w(MapEngine.TAG, "vk names: ${e.message ?: e}")
            } finally {
                busy.set(false)
                if (again) {
                    again = false
                    framePresented()
                }
            }
        }
    }

    /** A frame was presented (the render thread's upcall: allocates nothing): a step follows within [STEP_MS]. */
    fun framePresented() {
        if (posted || closed || look == null) return
        posted = true
        main.postDelayed(step, STEP_MS)
    }

    /** The map goes: no step after this. */
    fun close() {
        closed = true
        main.removeCallbacks(step)
    }

    /** One step, on the worker: the names the core wants, drawn and handed back. Returns how many it handed. */
    internal fun hand(): Int {
        val look = look ?: return 0
        val wants = core.namesWanted()
        if (wants.isEmpty()) return 0
        val maker = sprites?.takeIf { it.painter.density == look.density }
            ?: LabelSprites(LabelPainter(look.density), KEPT_TEXELS).also { sprites = it }
        look.face?.let { maker.painter.typeface = it }
        look.globeFace?.let { maker.painter.globeTypeface = it }
        var none = 0
        val block = LabelBlock.write(wants.map { want ->
            val kind = kindOf(want.look)
            if (!maker.painter.hasGlyphs(kind, want.text)) {
                none++
                LabelBlock.Sprite(want.id, 0, 0f, 0f, 0f, 0f, EMPTY, null, NO_POINTS)
            } else {
                val s = maker.sprite(MapLabel("", want.text, kind, emptyList()), want.id)
                LabelBlock.Sprite(s.id, 0, s.half, s.pad, s.textHeight, s.baseline, s.own, s.large, NO_POINTS)
            }
        })
        val why = core.names(block, look.floorDp)
        if (why.isNotEmpty()) {
            Log.w(MapEngine.TAG, "vk names: $why")
            return 0
        }
        handed += wants.size - none
        skipped += none
        Log.i(MapEngine.TAG, "vk names: ${wants.size - none} handed${if (none > 0) ", $none without glyphs" else ""} ($handed in all)")
        return wants.size
    }

    companion object {
        /** The least time between a presented frame and the step after it, and so between two steps, ms. */
        const val STEP_MS = 100L

        /** The names' images kept here for a name the core asks for again (4 MB): the core keeps what it shows. */
        private const val KEPT_TEXELS = 1L shl 20

        private val EMPTY = LabelBlock.Image(0, 0, 0, 0, IntArray(0))
        private val NO_POINTS = DoubleArray(0)

        /** The names' one drawing thread (the process's: one map draws through the core at a time). */
        private val shared: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-names").apply { isDaemon = true } }

        /** The label kind of a look the core names (`MapCore.NameWant`). */
        fun kindOf(look: Int): LabelKind = when (look) {
            MapCore.NameWant.TOWN -> LabelKind.TOWN
            MapCore.NameWant.VILLAGE -> LabelKind.VILLAGE
            MapCore.NameWant.GLOBE -> LabelKind.GLOBE
            else -> LabelKind.CITY
        }
    }
}
