package app.atlas.mapview.vk

import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteTheme
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.py.PyMath
import app.atlas.render.live.EngineState
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveMoment
import app.atlas.render.live.Moment
import java.nio.ByteBuffer
import java.time.ZoneId
import kotlin.concurrent.withLock

/**
 * The light the core draws the map in (plan-vector D14, K11): the moment the map's style names, as the map has
 * always mapped MapStyle's themes to the port's moments (MapController's momentOf: DAY → sunny, NIGHT → night,
 * AUTO and CHART → the live moment, the clock in five-minute steps lit by the forecast), written as the
 * theme block the core reads (`MK/state/ThemeBlock.kt`) from the port's own theme dict and its sun (Amsterdam.sunOf).
 *
 * The light is the place's: the live moment's sun stands where it stands over the place the map draws (the camera's
 * target, or a render pack's own origin, as its render is lit), at the instant, in a zone of any offset; a named
 * moment is a look, the bundled packs' own sun (the Westerkerk's at the moment's fixed time), which every place is
 * shown in. The block carries the place, so the core's world look draws the terminator and the night's caps where
 * that sun puts them: the true ones for the live moment, and for a named one those that leave the camera's place lit
 * as the moment is.
 *
 * The port keeps its moments in module state shared with its other users (the likeness's references; until 5 November
 * today's map), so the block is made under the engine's lock ([LiveEngine.lock]) from the bundled packs' state
 * (Regions.resetPlace: the Westerkerk's frame, its recorded weather and fixed moments) with the live moment's place
 * set over it, and the state found is put back after. The profile's TONE is the core's to set from the pack it draws (`CP/frame/MapFrame.hpp`), so the
 * block leaves it off. Off the main thread: the port's work takes milliseconds.
 */
internal object CoreMoment {
    /**
     * Writes the theme block of [moment] for a pack in [zone] into [out] (little-endian, [ThemeBlock.SIZE] bytes), with
     * the renders' daylight ([daylight], RenderOptions.daylight: by day its dusk, at night the open places' pools) and
     * the user's strength of TONE ([tone], the Map look's Tone: 1 the renders'), for the place at [placeLat], [placeLon]
     * (the bundled packs' own unless named: the live moment's sun is the place's, and the block carries the place).
     * Fails on a moment the port does not know.
     */
    fun write(
        out: ByteBuffer, moment: Moment, zone: ZoneId, daylight: Boolean, tone: Float = 1f, palette: Palette = Palette(),
        placeLat: Double = ThemeBlock.PLACE_LAT, placeLon: Double = ThemeBlock.PLACE_LON,
    ) {
        LiveEngine.lock.withLock {
            val saved = EngineState.capture()
            try {
                Regions.resetPlace(daylight)
                val key = when (moment) {
                    is Moment.Named -> moment.name.also { require(it in Amsterdam.THEMES) { "the engine has no moment '$it'" } }
                    is Moment.Live -> {
                        // the sun over the place lit (the port's sunPosition reads its place from LAT0 and LON0)
                        if (placeLat != Amsterdam.LAT0 || placeLon != Amsterdam.LON0) {
                            Amsterdam.LAT0 = placeLat
                            Amsterdam.LON0 = placeLon
                            Amsterdam.KX = 111320 * PyMath.cos(PyMath.radians(placeLat))
                        }
                        LiveMoment.install(moment.at, zone, moment.hours, daylight)
                        LiveMoment.KEY
                    }
                }
                val th = PaletteTheme.recolour(Amsterdam.THEMES.getValue(key), palette)
                val (azimuth, elevation, sun) = Amsterdam.sunOf(th)
                ThemeBlock.write(
                    out, th, doubleArrayOf(sun.x, sun.y, sun.z), azimuth, elevation, golden = key == "golden", tone = false, toneStrength = tone,
                    daylight = daylight, placeLat = placeLat, placeLon = placeLon,
                )
            } finally {
                saved.restore()
            }
        }
    }
}
