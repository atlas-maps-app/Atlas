package app.atlas.ui.go

import android.content.Context
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.geo.UnitSystem
import app.atlas.guide.FeatureLookup
import app.atlas.guide.GuideSettings
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.model.RouteRequest
import app.atlas.model.Vehicle
import app.atlas.places.TagFilter
import app.atlas.route.RouteResult
import app.atlas.search.covers
import app.atlas.search.within
import app.atlas.services.LocationQuality
import app.atlas.services.LocalData
import app.atlas.services.Units
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The go package's long-lived part, one per process (`Services.go`): the trip being guided and Ears only, both kept
 * here rather than in a screen so they go on with the screen off; what the Go tab keeps ([store]); planning with the
 * demo fallback; and the guide's settings from the app's.
 */
class GoHub(private val services: Services) {
    val store: GoStore by lazy { GoStore(services.settings) }

    private val navState = MutableStateFlow<NavSession?>(null)
    private val earsState = MutableStateFlow<EarsSession?>(null)
    private val mutedState = MutableStateFlow(false)

    /** The trip being guided, if any. */
    val nav: StateFlow<NavSession?> = navState.asStateFlow()

    /** Ears only, while it runs. */
    val ears: StateFlow<EarsSession?> = earsState.asStateFlow()

    /** The voice is off (vibrations stay). */
    val muted: StateFlow<Boolean> = mutedState.asStateFlow()

    /** The driver's hours as kept, current (trucks). */
    val tacho: StateFlow<TachoState?> by lazy { store.tacho.stateIn(services.scope, SharingStarted.Eagerly, null) }

    /** The units of Settings, kept current. */
    private val unitsState: StateFlow<Units> by lazy { services.settings.units.stateIn(services.scope, SharingStarted.Eagerly, Units.METRIC) }
    val units: Units get() = unitsState.value

    /** The same, for screens to observe: a change in Settings reaches an open screen. */
    val unitsFlow: StateFlow<Units> get() = unitsState

    fun setMuted(muted: Boolean) {
        mutedState.value = muted
        (services.voice as? TtsVoice)?.setMuted(muted)
        if (muted && services.voice !is TtsVoice) services.voice.stop()
    }

    /** What the guide needs to know: the mode and vehicle, the units, the language and the time zone of the app's clock. */
    suspend fun guideSettings(mode: Mode, vehicle: Vehicle): GuideSettings {
        val units = services.settings.units.first()
        val locale = Locale.getDefault()
        val system = when {
            mode.water || units == Units.NAUTICAL -> UnitSystem.NAUTICAL
            units == Units.IMPERIAL -> if (locale.country == "GB") UnitSystem.IMPERIAL_UK else UnitSystem.IMPERIAL
            else -> UnitSystem.METRIC
        }
        return GuideSettings(
            mode = mode,
            units = system,
            language = locale.toLanguageTag(),
            vehicle = vehicle,
            zone = services.clock.zone,
            leftHandTraffic = locale.country in LEFT_HAND,
        )
    }

    /**
     * Routes [req] with the app's router. In [demo] mode a trip the phone has no data for falls back to the matching
     * demo route ([DemoRoutes]), so CI and "simulate" show real guidance without the 80 MB download.
     */
    suspend fun plan(req: RouteRequest, demo: Boolean = false): RouteResult {
        val result = try {
            services.router.route(req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RouteResult.Error(e.message ?: e.toString(), e)
        }
        if (result is RouteResult.Found || !demo) return result
        val fallback = withContext(Dispatchers.IO) { DemoRoutes.find(services.context, req.mode, req.waypoints.first(), req.waypoints.last()) }
        return if (fallback != null) RouteResult.Found(listOf(fallback)) else result
    }

    /**
     * Starts guiding [route] (found for [req]) and, from the visible screen ([context]), the service that keeps it going.
     * [demo] drives the simulator instead of the phone's location. Any trip guided before ends.
     */
    fun startNav(context: Context, req: RouteRequest, route: Route, name: String?, settings: GuideSettings, demo: Boolean): NavSession {
        stopNav()
        stopEars()
        // A trip begins with the voice on: a mute left from an earlier one would make this one silent without warning.
        if (mutedState.value) setMuted(false)
        // The hours carry on from the last trip; a demo drive shows them but does not add to them.
        val tachoStart = if (req.mode == Mode.TRUCK) tacho.value ?: TachoState() else null
        val session = NavSession(
            request = req, route = route, destinationName = name, router = services.router, voice = services.voice,
            settings = settings, features = features, scope = services.scope, demo = demo, tachoStart = tachoStart,
            onTacho = { t -> if (!demo) services.scope.launch { store.setTacho(t) } },
        )
        navState.value = session
        val fixes = if (demo) NavSession.demoFixes(route, services.clock.instant()) else services.location.fixes(LocationQuality.NAVIGATION)
        session.start(fixes)
        // A simulated drive is not a place you went to.
        if (!demo) services.scope.launch { store.visited(SavedPlace(name ?: "", req.waypoints.last()), services.clock.millis()) }
        NavigationService.start(context)
        return session
    }

    fun stopNav() {
        navState.value?.stop()
        navState.value = null
    }

    /** Starts Ears only around where you are, and the service that keeps it going with the screen off. */
    fun startEars(context: Context, headings: kotlinx.coroutines.flow.Flow<Double?>? = null): EarsSession {
        earsState.value?.let { return it }
        val session = EarsSession(services.voice, GuideSettings(mode = Mode.WALK, language = Locale.getDefault().toLanguageTag(), zone = services.clock.zone),
            services.scope, ::placesAround, services.clock.zone)
        earsState.value = session
        session.start(services.location.fixes(LocationQuality.NAVIGATION), headings)
        NavigationService.start(context)
        return session
    }

    fun stopEars() {
        earsState.value?.stop()
        earsState.value = null
    }

    fun stopAll() {
        stopNav()
        stopEars()
    }

    /** The landmarks near a maneuver, from the offline place store (empty without one). */
    val features: FeatureLookup = FeatureLookup { p, r ->
        val store = services.placeStores
        if (store.size == 0) emptyList() else runCatching { store.within(p, r).map { it.place } }.getOrDefault(emptyList())
    }

    /**
     * The places within [radiusM] of [at] with every tag: from the offline place store where it covers [at], else the
     * map's packs on the phone, else Overpass online ([LocalData.places]).
     */
    suspend fun placesAround(at: LatLon, radiusM: Double): List<Place> = withContext(Dispatchers.IO) {
        val store = services.placeStores
        if (store.covers(at)) return@withContext runCatching { store.within(at, radiusM).map { it.place } }.getOrDefault(emptyList())
        runCatching { services.localData.places(at, radiusM, ANY_PLACE, 300).value }.getOrDefault(emptyList())
    }

    private companion object {
        val LEFT_HAND = setOf("GB", "IE", "JP", "AU", "NZ", "IN", "ZA", "MT", "CY", "SG", "MY", "TH", "ID", "HK", "KE", "TZ", "UG", "PK", "BD", "LK", "JM")
        val ANY_PLACE = TagFilter.KeyMatches("^(amenity|shop|tourism|leisure|highway|public_transport|railway|historic)$")
    }
}
