package app.atlas.ui.local

import android.content.Context
import android.content.SharedPreferences
import app.atlas.R
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.ui.local.astro.IssPass
import app.atlas.ui.local.astro.OrbitData
import app.atlas.ui.local.astro.SatellitePasses
import app.atlas.ui.local.astro.Sgp4
import app.atlas.ui.local.astro.TleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** What the sky holds for the space station tonight: the orbit data in hand and the passes it gives. */
class IssOutlook(val orbit: OrbitData?, val passes: List<IssPass>) {
    /** The next pass that can be seen, if any in the window. */
    val nextVisible: IssPass? get() = passes.firstOrNull { it.visible }

    /** True when there is no orbit data at all (never online, nothing bundled). */
    val noData: Boolean get() = orbit == null
}

/** Small per-viewer settings of the local package (reminders, the waste calendar's night-before alarm), in SharedPreferences. */
class LocalPrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("atlas_local", Context.MODE_PRIVATE)

    fun bool(key: String, default: Boolean = false) = sp.getBoolean(key, default)
    fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    fun int(key: String, default: Int) = sp.getInt(key, default)
    fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    fun string(key: String): String? = sp.getString(key, null)

    /** Every key that starts with [prefix]. */
    fun keys(prefix: String): List<String> = sp.all.keys.filter { it.startsWith(prefix) }
    fun putString(key: String, v: String?) = sp.edit().apply { if (v == null) remove(key) else putString(key, v) }.apply()
}

/**
 * The local package's long-lived part (`Services.local`): the space station's orbit data and passes, the zoom ladder's
 * pictures, the waste calendar and the small settings. Built on first use.
 */
class LocalHub(private val services: Services) {
    val prefs: LocalPrefs by lazy { LocalPrefs(services.context) }

    /** Reminders set for a time (the seasonal alert, the waste calendar's night before). */
    var reminders: Reminders = WorkReminders(services.context, prefs, now = { services.clock.instant() })

    /** The waste calendar you imported. */
    val waste: WasteStore by lazy { WasteStore(File(services.dirs.files, "local/waste.ics")) { services.clock.zone } }

    /** Tests: pretend the phone is online (true) or offline (false); null reads the real state. */
    @Volatile
    var onlineOverride: Boolean? = null

    /** True when the network may be used: not in a test's offline mode, and the phone is connected. */
    fun online(): Boolean = onlineOverride ?: (!services.config.offline && services.connectivity.online.value)

    /** The sound meter: the phone's microphone; tests give a fake one. */
    var soundMeter: SoundMeter = MicSoundMeter()
        set(v) { field = v; soundMeterIsFake = true }

    /** True when [soundMeter] is a stand-in, which needs no microphone permission. */
    var soundMeterIsFake: Boolean = false
        private set

    /** Tests: the outline, paths and trees of a park, instead of Overpass's `out geom`. */
    @Volatile
    var parkShape: ((app.atlas.model.Place, app.atlas.geo.BBox) -> ParkShape?)? = null

    /** Tests: the marine forecast (waves, tides, water temperature) to use instead of Open-Meteo's. */
    @Volatile
    var marineOverride: (suspend (app.atlas.geo.LatLon) -> app.atlas.providers.MarineForecast?)? = null

    /** The marine forecast at [at]: water temperature and sea level by the hour, or null inland, offline and when the model has none. */
    suspend fun marine(at: app.atlas.geo.LatLon): app.atlas.providers.MarineForecast? =
        marineOverride?.invoke(at) ?: if (online()) runCatching { services.weather.marine(at) }.getOrNull() else null

    /** Wikidata for "named after"; tests replace the way it reads a URL. */
    var etymology: Etymology = Etymology { url -> services.http.text(url) }

    /** Tests: the height above sea level of a point, instead of the forecast's. */
    @Volatile
    var elevationOverride: (suspend (app.atlas.geo.LatLon) -> Double?)? = null

    /** The height above sea level at [at], metres, as the weather model has it (fetched, else kept); null when neither is there. */
    suspend fun elevation(at: app.atlas.geo.LatLon): Double? =
        elevationOverride?.invoke(at)
            ?: runCatching { if (online()) services.weather.forecast(at) else services.weather.kept(at) }.getOrNull()?.elevation?.takeIf { !it.isNaN() }

    /** Street photos: Panoramax; tests give their own. */
    var photos: StreetPhotoSource = PanoramaxSource(services)

    /** "What of this kind is near me", over the place store and Overpass. */
    val finder: LocalFinder by lazy { LocalFinder(services) }

    /** The drawings of this package's boards that the symbol catalogue does not hold (the foods' glyphs). */
    val art: Map<String, app.atlas.places.Icon> by lazy {
        app.atlas.ui.mine.art.MineArt.parse(services.context.resources.openRawResource(R.raw.local_art).bufferedReader().use { it.readText() })
    }

    /** The pictures of the zoom ladder, read from the raw resource the first time. */
    val cosmos: CosmosData by lazy { CosmosData.of(services.context) }

    private val orbits: TleStore by lazy {
        TleStore(
            file = File(services.dirs.files, "local/iss.tle"),
            bundled = { services.context.resources.openRawResource(R.raw.local_iss).bufferedReader().use { it.readText() } },
            fetch = { services.http.text(TleStore.URL) },
            clock = services.clock,
        )
    }

    @Volatile
    private var orbitOverride: (suspend () -> OrbitData?)? = null
    private var cached: Pair<String, IssOutlook>? = null

    /** What "Plan around the seasons" asks the planner: the month, and what is on. */
    fun askAboutSeasons(today: java.time.LocalDate): String =
        "What is worth doing outdoors around here in " + today.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH).lowercase() + "?"

    /** Tests: use [orbit] instead of the kept or bundled data. */
    fun useOrbit(orbit: OrbitData?) { orbitOverride = { orbit }; cached = null }

    /**
     * The passes of the space station over [at] in the next [window] after [now]. Orbit data comes from CelesTrak when
     * online (at most every two hours), else what is kept, else what the app was built with; the passes are computed
     * on the phone with SGP4.
     */
    suspend fun iss(at: LatLon, now: Instant, window: Duration = Duration.ofHours(48)): IssOutlook = withContext(Dispatchers.Default) {
        val orbit = orbitOverride?.invoke() ?: orbits.orbit(online = online())
        if (orbit == null || orbit.useless) return@withContext IssOutlook(orbit, emptyList())
        val key = "${orbit.tle.epochDays1950}|${(at.lat * 20).roundToInt()}|${(at.lon * 20).roundToInt()}|${now.epochSecond / 600}|${window.toHours()}"
        cached?.takeIf { it.first == key }?.let { return@withContext it.second }
        val outlook = IssOutlook(orbit, SatellitePasses(Sgp4(orbit.tle)).passes(now, window, at.lat, at.lon))
        cached = key to outlook
        outlook
    }
}
