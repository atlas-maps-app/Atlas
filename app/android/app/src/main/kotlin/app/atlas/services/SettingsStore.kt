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
}

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

    /**
     * The screen's haze, vignette and grain over the map: off when unset, as each costs frame time (the user, 27 October:
     * "disabled by default if they add performance overhead"); on, the renders' look.
     */
    val MAP_HAZE = booleanPreferencesKey("map.haze")
    val MAP_VIGNETTE = booleanPreferencesKey("map.vignette")
    val MAP_GRAIN = booleanPreferencesKey("map.grain")

    /**
     * Whether what stands up is drawn at its true depth (on when unset, the engine's own) or in the renderer's painter's
     * order (whole buildings by one point each, which melts some into each other: a drawing's look).
     */
    val MAP_TRUE_DEPTH = booleanPreferencesKey("map.trueDepth")

    /**
     * A debug build's switches of the core's parts: the clouds' shadows and the water's light (off when unset, until
     * they are worked on: the user, 28 October 2026), and the facades' detail (on when unset). Off, the map leaves that
     * part out, so its panel shows on the phone what the part costs (MapEngine.leaveOut); the trees and the soft shadows
     * are the map's own layers.
     */
    val MAP_CLOUD_SHADOWS = booleanPreferencesKey("map.cloudShadows")
    val MAP_WATER_LIGHT = booleanPreferencesKey("map.waterLight")
    val MAP_FACADES = booleanPreferencesKey("map.facades")
    val UNITS = stringPreferencesKey("units")

    /** The language for place names, null for local names. */
    val NAME_LANGUAGE = stringPreferencesKey("names.language")
    val ONBOARDED = booleanPreferencesKey("onboarded")

    /** Providers the user switched off in Data sources. */
    val PROVIDERS_OFF = stringSetPreferencesKey("providers.off")
}
