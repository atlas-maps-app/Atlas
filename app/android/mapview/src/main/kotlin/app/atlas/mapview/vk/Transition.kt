package app.atlas.mapview.vk

import app.atlas.mapview.CountryTiles
import kotlin.math.max
import kotlin.math.min

/**
 * The transitional rule (plan-vector §0.1, K11; until A5): per place, the core where it has tiles, today's map (the
 * Canvas path) elsewhere, with a cross-fade of [FADE_MS] where the place drawn changes from one to the other, and
 * never a blank frame.
 *
 * The core draws under today's map (its window behind the app's), so the rule is one number, [canvas]: how much of
 * today's map covers the core, from 0 (the core alone) to 1 (today's map alone). It moves toward the path the place
 * wants only while that path has its picture there ([update]'s readiness: the core a complete frame; today's map its
 * frame, or its own honest state, "No map data here" or a download's progress), so the path switched from stays whole
 * on screen until the one switched to can be shown, and a switch back before the fade ends turns it round from where
 * it is. At the start it covers the core with today's map's paper (no frame of it, no "Loading"), which goes once
 * the core's first picture is complete.
 *
 * It logs each switch as its fade begins: `vk transitional <region> (<n> this session)` when today's map takes a place
 * over (Appendix C: reported until A4, a failure from A5), then `vk transition core>canvas <where>: …` or
 * `vk transition canvas>core <where>: …` with how long the incoming picture took and that the outgoing one was held
 * until then (smoke.sh reads them). Main thread only.
 */
internal class Transition(private val log: (String) -> Unit) {
    /** Which path draws a place. */
    enum class Path { CORE, CANVAS }

    /** How much of today's map covers the core: 0 the core alone, 1 today's map alone. */
    var canvas: Float = 1f
        private set

    /** The path the place wants, as last told. */
    var wanted: Path? = null
        private set

    /** Whether the core's first picture has been shown (the start's paper gone). */
    var started: Boolean = false
        private set

    /** How many times today's map took a place over this session. */
    var transitional: Int = 0
        private set

    private var wantedSince = 0L
    private var lastMs = -1L
    private var fading = false

    /** The path fully shown, or null mid-fade. */
    val shown: Path?
        get() = when (canvas) {
            0f -> Path.CORE
            1f -> Path.CANVAS
            else -> null
        }

    /** Whether the core is covered whole by today's map where today's map is wanted, and need not draw. */
    val coreHidden: Boolean get() = canvas >= 1f && wanted == Path.CANVAS

    /** Whether today's map is not seen at all, and its frames need not be made. */
    val canvasHidden: Boolean get() = canvas <= 0f && wanted == Path.CORE

    /**
     * The rule at [nowMs] for a place that [wanted] draws: [coreReady] says the core's picture is complete for the
     * camera, [canvasReady] that today's map has its picture (or honest state) there; [where] names the place for the
     * log (its region or pack). True while a switch waits for its picture or fades (frames are due).
     */
    fun update(nowMs: Long, wanted: Path, coreReady: Boolean, canvasReady: Boolean, where: String): Boolean {
        if (wanted != this.wanted) {
            this.wanted = wanted
            wantedSince = nowMs
            fading = false
        }
        val target = if (wanted == Path.CANVAS) 1f else 0f
        val ready = if (wanted == Path.CANVAS) canvasReady else coreReady
        val dt = if (lastMs < 0) 0L else max(0L, nowMs - lastMs)
        lastMs = nowMs
        if (canvas == target) {
            fading = false
            if (wanted == Path.CORE && ready) started = true
            return false
        }
        if (!ready) return true // the outgoing picture stays whole until the incoming one exists
        if (!fading) {
            fading = true
            val held = nowMs - wantedSince
            if (!started && wanted == Path.CORE) {
                // The start: the paper goes once the core's first picture is complete.
            } else if (wanted == Path.CANVAS) {
                transitional++
                log("vk transitional $where ($transitional this session)")
                log("vk transition core>canvas $where: today's map ready after $held ms, the core's picture held until then; fading $FADE_MS ms")
            } else {
                log("vk transition canvas>core $where: the core's picture complete after $held ms, today's map held until then; fading $FADE_MS ms")
            }
            // The fade's first step starts now, not at the last update before the picture came.
            return step(target, 0L)
        }
        return step(target, dt)
    }

    private fun step(target: Float, dt: Long): Boolean {
        val k = dt.toFloat() / FADE_MS
        canvas = if (target > canvas) min(target, canvas + k) else max(target, canvas - k)
        if (canvas == target) {
            fading = false
            if (target == 0f) started = true
            return false
        }
        return true
    }

    companion object {
        /** The cross-fade's length, ms (§0.1). */
        const val FADE_MS = 300L

        /**
         * The path the place of a camera on [lat], [lon], [distance] metres from it wants: the core where a pack on the
         * phone holds the target within its cover's reach ([PackList.covers]), and where the target lies within that
         * reach in a country whose tiles the core draws ([inCountry]): its window on its way (from the country's tiles,
         * or built on the phone), the core draws its own cover and the windows say what they are doing, never today's
         * map's other look in between (one look within a country, ENGINE.md step 3; the user, 30 October, on today's map
         * where a window had not come: "looks different then the engine"). Within such a country whose tiles of this
         * phone's core are published the core's reach is its levels' ([PackList.LEVELS_REACH]: step 7, its frame cells'
         * L13 and L12 tiles, which come from those tiles alone), else its windows' ([PackList.CORE_REACH]). Today's map
         * elsewhere: beyond the core's reach, and outside those countries where no window is on the phone.
         */
        fun pathOf(packs: PackList, countryAt: (Double, Double) -> CountryTiles?, lat: Double, lon: Double, distance: Double): Path {
            if (packs.covers(lat, lon, distance)) return Path.CORE
            val country = countryAt(lat, lon) ?: return Path.CANVAS
            return if (distance <= (if (country.levels) PackList.LEVELS_REACH else PackList.CORE_REACH)) Path.CORE else Path.CANVAS
        }
    }
}
