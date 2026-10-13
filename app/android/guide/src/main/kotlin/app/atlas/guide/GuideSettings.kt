package app.atlas.guide

import app.atlas.geo.UnitSystem
import app.atlas.model.Mode
import app.atlas.model.Vehicle
import java.time.ZoneId

/**
 * How much the guide says. MINIMAL: earcons, haptics and the bare turn with its distance. NORMAL: full instructions,
 * landmarks, lanes and warnings. CHATTY: also periodic reassurance with distance to go and arrival time.
 */
enum class Verbosity { MINIMAL, NORMAL, CHATTY }

/** How Ears-only callouts name a direction: "at 2 o'clock" or "slightly right". */
enum class DirectionStyle { CLOCK, WORDS }

/**
 * Everything the guide needs to know about the traveller.
 *
 * @property units how distances are spoken: metres and kilometres; feet (US) or yards (UK) and miles; metres, cables and
 *   nautical miles at sea.
 * @property heightsInFeet clearances and vehicle heights in feet and inches (the default with imperial units; US boaters
 *   with nautical units set it too).
 * @property language a BCP 47 tag; the osrm templates cover 17 languages and Atlas's own phrases en, nl, de and fr,
 *   anything else falls back to English.
 * @property voice false keeps the guide to earcons and haptics.
 * @property vehicle dimensions for truck and boat callouts (height, weight, width, length, air draft, draft).
 * @property audioLatencyS seconds from a cue to the first sound: 0.2 normally, 1.5 over a Bluetooth headset call link.
 * @property speedCameras speak speed cameras; the app sets it only where the law allows such warnings.
 * @property leftHandTraffic driving on the left (UK, Japan): off-ramps on the left are then the ordinary side.
 * @property zone the time zone for spoken arrival times.
 */
data class GuideSettings(
    val mode: Mode = Mode.CAR,
    val units: UnitSystem = UnitSystem.METRIC,
    val heightsInFeet: Boolean = units == UnitSystem.IMPERIAL || units == UnitSystem.IMPERIAL_UK,
    val language: String = "en",
    val verbosity: Verbosity = Verbosity.NORMAL,
    val voice: Boolean = true,
    val earcons: Boolean = true,
    val haptics: Boolean = true,
    val vehicle: Vehicle = Vehicle(),
    val audioLatencyS: Double = 0.2,
    val speedCameras: Boolean = false,
    val leftHandTraffic: Boolean = false,
    val directionStyle: DirectionStyle = DirectionStyle.WORDS,
    val zone: ZoneId = ZoneId.systemDefault(),
)
