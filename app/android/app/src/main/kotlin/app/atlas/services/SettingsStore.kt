package app.atlas.services

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.atlas.geo.LatLon
import app.atlas.mapview.MapPart
import app.atlas.mapview.MapParts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Where the map looked when the app was last used. */
data class SavedCamera(val target: LatLon, val zoom: Double, val bearing: Double = 0.0, val pitch: Double = 0.0)

/** Units for distances and speeds. */
enum class Units { METRIC, IMPERIAL, NAUTICAL }

/**
 * The settings on this phone, in one DataStore file. Each feature keeps its own keys in its own package (as
 * `Preferences.Key`s) and reads and writes them through [flow] and [set]; the shell's own are in [SettingsKeys].
 * A broken or unreadable file reads as empty settings rather than crashing.
 */
class SettingsStore(private val store: DataStore<Preferences>) {
    val data: Flow<Preferences> = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> = data.map { it[key] ?: default }.distinctUntilChanged()

    fun <T> flow(key: Preferences.Key<T>): Flow<T?> = data.map { it[key] }.distinctUntilChanged()

    /** Sets [key] to [value], or removes it when [value] is null. */
    suspend fun <T> set(key: Preferences.Key<T>, value: T?) {
        store.edit { if (value == null) it.remove(key) else it[key] = value }
    }

    suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    val camera: Flow<SavedCamera?> = data.map { p ->
        val lat = p[SettingsKeys.CAMERA_LAT] ?: return@map null
        val lon = p[SettingsKeys.CAMERA_LON] ?: return@map null
        SavedCamera(LatLon(lat, lon), p[SettingsKeys.CAMERA_ZOOM] ?: 15.0, p[SettingsKeys.CAMERA_BEARING] ?: 0.0, p[SettingsKeys.CAMERA_PITCH] ?: 0.0)
    }.distinctUntilChanged()

    suspend fun saveCamera(c: SavedCamera) = edit {
        it[SettingsKeys.CAMERA_LAT] = c.target.lat
        it[SettingsKeys.CAMERA_LON] = c.target.lon
        it[SettingsKeys.CAMERA_ZOOM] = c.zoom
        it[SettingsKeys.CAMERA_BEARING] = c.bearing
        it[SettingsKeys.CAMERA_PITCH] = c.pitch
    }

    val units: Flow<Units> = data.map { p -> p[SettingsKeys.UNITS]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.METRIC }
        .distinctUntilChanged()

    /** The map's parts a debug build's Look settings leave out ([leftOutParts]). */
    val partsOff: Flow<Set<MapPart>> = data.map { leftOutParts(it) }.distinctUntilChanged()
}

/** The map's parts left out (SettingsKeys.MAP_PARTS_OFF, their names); unset, the defaults (MapParts.byDefault: none). */
fun leftOutParts(p: Preferences): Set<MapPart> = p[SettingsKeys.MAP_PARTS_OFF]?.let { MapParts.named(it) } ?: MapParts.byDefault

/** The shell's settings keys. Features add theirs in their own packages, prefixed with the package name. */
object SettingsKeys {
    val CAMERA_LAT = doublePreferencesKey("camera.lat")
    val CAMERA_LON = doublePreferencesKey("camera.lon")
    val CAMERA_ZOOM = doublePreferencesKey("camera.zoom")
    val CAMERA_BEARING = doublePreferencesKey("camera.bearing")
    val CAMERA_PITCH = doublePreferencesKey("camera.pitch")

    /** The map's look: a `mapview.Theme` name. */
    val MAP_THEME = stringPreferencesKey("map.theme")

    /** The map's optional layers: `mapview.MapLayer` names. */
    val MAP_LAYERS = stringSetPreferencesKey("map.layers")
    val MAP_3D = booleanPreferencesKey("map.3d")

    /** The tilt the user last left the map at with their fingers (degrees, `MapState.chosenTilt`): the 3D button's. */
    val MAP_TILT = doublePreferencesKey("map.tilt")

    /**
     * The screen's haze, vignette and grain over the map: off when unset, as each costs frame time (the user, 27 October:
     * "disabled by default if they add performance overhead"); on, the renders' look.
     */
    val MAP_HAZE = booleanPreferencesKey("map.haze")
    val MAP_VIGNETTE = booleanPreferencesKey("map.vignette")
    val MAP_GRAIN = booleanPreferencesKey("map.grain")

    /**
     * The frosted glass of the Map home's chrome and the bottom bar: off when unset, so they are solid and the map's core
     * blurs nothing under them (the user, 30 October: "For now turn the frosted glass off by default. It costs
     * performance isnt done right"); on, the boards' glass. Other screens keep their glass.
     */
    val FROSTED_GLASS = booleanPreferencesKey("ui.frostedGlass")

    /**
     * A debug build's switches of the core's parts: the names of the map's parts it leaves out (mapview.MapPart: every
     * layer and effect, each its own switch, and a switch for each group of them), so the map's panel shows on the phone
     * what each part costs (MapEngine.leaveOut). Unset, every part is drawn ([leftOutParts]): the clouds' shadows and
     * the water's light too since their rework (left out from alpha 19 until it, the user, 28 October 2026).
     */
    val MAP_PARTS_OFF = stringSetPreferencesKey("map.partsOff")

    /**
     * A debug build's frame pacing (off when unset): each frame presented at a timeline its recent work can make, as
     * alpha 15 drew, instead of as soon as it is ready (MapEngine.pacing), so the phone can tell whether the pacing's
     * going in alpha 16 stops the zoom at z 17 (the user, 29 October 2026).
     */
    val MAP_PACING = booleanPreferencesKey("map.pacing")

    /**
     * A debug or bench build's frame timings over the Map home: on when unset, as every such build drew them; off,
     * nothing is drawn over the map for them and its canvas no longer redraws each frame to show them (the user, 30
     * October: "Let me toggle off the debug overlay its in the way").
     */
    val MAP_TIMINGS = booleanPreferencesKey("map.timings")

    val UNITS = stringPreferencesKey("units")

    /** The language for place names, null for local names. */
    val NAME_LANGUAGE = stringPreferencesKey("names.language")
    val ONBOARDED = booleanPreferencesKey("onboarded")

    /** Providers the user switched off in Data sources. */
    val PROVIDERS_OFF = stringSetPreferencesKey("providers.off")
}
