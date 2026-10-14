package app.atlas.ui.go

import androidx.datastore.preferences.core.stringPreferencesKey
import app.atlas.geo.Geo
import app.atlas.model.Mode
import app.atlas.model.RoutePrefs
import app.atlas.model.Vehicle
import app.atlas.services.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the Go tab keeps on this phone, in the app's settings under `go.` keys: home and work, the places you went to,
 * your vehicles' dimensions per mode, your route preferences and the mode you used last. Nothing leaves the phone.
 */
class GoStore(private val settings: SettingsStore) {
    val home: Flow<SavedPlace?> = settings.flow(HOME).map { GoJson.place(GoJson.parse(it)) }.distinctUntilChanged()
    val work: Flow<SavedPlace?> = settings.flow(WORK).map { GoJson.place(GoJson.parse(it)) }.distinctUntilChanged()

    /** The places you went to, latest first. */
    val recent: Flow<List<SavedPlace>> = settings.flow(RECENT).map { GoJson.places(it) }.distinctUntilChanged()

    val prefs: Flow<RoutePrefs> = settings.flow(PREFS).map { GoJson.prefs(GoJson.parse(it)) ?: RoutePrefs() }.distinctUntilChanged()

    /** The driver's hours as last counted (trucks), so they carry over from one trip to the next. */
    val tacho: Flow<TachoState?> = settings.flow(TACHO).map { TachoState.fromJson(GoJson.parse(it) as? kotlinx.serialization.json.JsonObject) }.distinctUntilChanged()

    /** The mode last chosen, walking until one is. */
    val mode: Flow<Mode> = settings.flow(MODE).map { n -> n?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.WALK }.distinctUntilChanged()

    /** Every vehicle entered, by mode. */
    val vehicles: Flow<Map<Mode, Vehicle>> = settings.data.map { p ->
        Mode.entries.mapNotNull { m -> p[vehicleKey(m)]?.let { t -> GoJson.vehicle(GoJson.parse(t))?.let { m to it } } }.toMap()
    }.distinctUntilChanged()

    fun vehicle(mode: Mode): Flow<Vehicle> = vehicles.map { it[mode] ?: defaultVehicle(mode) }.distinctUntilChanged()

    suspend fun setHome(p: SavedPlace?) = settings.set(HOME, p?.let { GoJson.place(it).toString() })
    suspend fun setWork(p: SavedPlace?) = settings.set(WORK, p?.let { GoJson.place(it).toString() })
    suspend fun setPrefs(p: RoutePrefs) = settings.set(PREFS, GoJson.prefs(p).toString())
    suspend fun setMode(m: Mode) = settings.set(MODE, m.name)
    suspend fun setVehicle(mode: Mode, v: Vehicle?) = settings.set(vehicleKey(mode), v?.let { GoJson.vehicle(it).toString() })
    suspend fun setTacho(t: TachoState?) = settings.set(TACHO, t?.toJson()?.toString())

    /**
     * Remembers a trip to [p] at [now]: it moves to the top of the recent places, and an earlier visit of the same place
     * (within 30 m, or the same OSM id) gives way to it. At most [MAX_RECENT] are kept.
     */
    suspend fun visited(p: SavedPlace, now: Long) {
        settings.edit { prefs ->
            val list = GoJson.places(prefs[RECENT])
            val kept = list.filterNot { same(it, p) }
            prefs[RECENT] = GoJson.places((listOf(p.copy(usedAt = now)) + kept).take(MAX_RECENT))
        }
    }

    suspend fun forget(p: SavedPlace) {
        settings.edit { prefs -> prefs[RECENT] = GoJson.places(GoJson.places(prefs[RECENT]).filterNot { same(it, p) }) }
    }

    /** The vehicle for [mode] now, entered or default. */
    suspend fun vehicleNow(mode: Mode): Vehicle = vehicle(mode).first()

    companion object {
        val HOME = stringPreferencesKey("go.home")
        val WORK = stringPreferencesKey("go.work")
        val RECENT = stringPreferencesKey("go.recent")
        val PREFS = stringPreferencesKey("go.prefs")
        val MODE = stringPreferencesKey("go.mode")
        val TACHO = stringPreferencesKey("go.tacho")
        const val MAX_RECENT = 12

        fun vehicleKey(mode: Mode) = stringPreferencesKey("go.vehicle.${mode.name.lowercase()}")

        /** Two saved places that are the same place. */
        fun same(a: SavedPlace, b: SavedPlace): Boolean =
            (a.osmId != null && a.osmId == b.osmId) || Geo.distance(a.at, b.at) < 30.0

        /**
         * What Atlas assumes until you enter your own: nothing for most modes (no limits checked); for the truck a common
         * European articulated lorry, for the camper a common van conversion and for boats a canal sloop or a small yacht,
         * so their restrictions (heights, weights, bridges) are checked from the start rather than ignored.
         */
        fun defaultVehicle(mode: Mode): Vehicle = when (mode) {
            Mode.TRUCK -> Vehicle(heightM = 4.0, widthM = 2.55, lengthM = 16.5, weightT = 40.0, axleLoadT = 11.5)
            Mode.CAMPER -> Vehicle(heightM = 3.1, widthM = 2.3, lengthM = 7.2, weightT = 3.5)
            // A canal sloop, the commonest boat on inland water; a sailing yacht of nine or ten metres.
            Mode.BOAT -> Vehicle(lengthM = 6.5, beamM = 2.3, draftM = 0.6, airDraftM = 1.6)
            Mode.SAIL -> Vehicle(lengthM = 9.5, beamM = 3.2, draftM = 1.6, airDraftM = 13.5)
            else -> Vehicle()
        }
    }
}
