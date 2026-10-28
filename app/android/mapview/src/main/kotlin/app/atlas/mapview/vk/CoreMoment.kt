package app.atlas.mapview.vk

import app.atlas.mapcore.state.ThemeBlock
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.live.EngineState
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveMoment
import app.atlas.render.live.Moment
import java.nio.ByteBuffer
import java.time.ZoneId
import kotlin.concurrent.withLock

/**
 * The light the core draws the map in (plan-vector D14, K11): the moment the map's style names, as today's map has
 * always mapped MapStyle's themes to the port's moments (MapController's momentOf: DAY → sunny, NIGHT → night,
 * AUTO and CHART → the live moment, the clock in five-minute steps lit by the forecast), written as the
 * theme block the core reads (`MK/state/ThemeBlock.kt`) from the port's own theme dict and its sun (Amsterdam.sunOf).
 *
 * The port keeps its moments in module state shared with today's map, so the block is made under the engine's lock
 * ([LiveEngine.lock]) with the place set to the bundled packs' (Regions.resetPlace: the Westerkerk's frame, its
 * recorded weather and fixed moments), and the state today's map left is put back after. The profile's TONE is the
 * core's to set from the pack it draws (`CP/frame/MapFrame.hpp`), so the block leaves it off. Off the main thread: the
 * lock may be held by a frame of today's map being made.
 */
internal object CoreMoment {
    /**
     * Writes the theme block of [moment] for a pack in [zone] into [out] (little-endian, [ThemeBlock.SIZE] bytes), with
     * the renders' dusk ([daylight], RenderOptions.daylight). Fails on a moment the port does not know.
     */
    fun write(out: ByteBuffer, moment: Moment, zone: ZoneId, daylight: Boolean) {
        LiveEngine.lock.withLock {
            val saved = EngineState.capture()
            try {
                Regions.resetPlace(daylight)
                val key = when (moment) {
                    is Moment.Named -> moment.name.also { require(it in Amsterdam.THEMES) { "the engine has no moment '$it'" } }
                    is Moment.Live -> {
                        LiveMoment.install(moment.at, zone, moment.hours, daylight)
                        LiveMoment.KEY
                    }
                }
                val th = Amsterdam.THEMES.getValue(key)
                val (azimuth, elevation, sun) = Amsterdam.sunOf(th)
                ThemeBlock.write(out, th, doubleArrayOf(sun.x, sun.y, sun.z), azimuth, elevation, golden = key == "golden", tone = false)
            } finally {
                saved.restore()
            }
        }
    }
}
