package app.atlas.ui.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.CoreState
import app.atlas.mapview.HomeView
import app.atlas.mapview.LabelKind
import app.atlas.mapview.MapEngine
import app.atlas.mapview.MapLabel
import app.atlas.mapview.MapStatus
import app.atlas.model.Place
import app.atlas.mapview.Follow
import app.atlas.mapview.MapLayer
import app.atlas.mapview.MapState
import app.atlas.mapview.MapStyle
import app.atlas.mapview.Theme
import app.atlas.model.Fix
import app.atlas.providers.Forecast
import app.atlas.providers.Weather
import app.atlas.services.LocationQuality
import app.atlas.services.NearbyPlaces
import app.atlas.services.PlaceOrigin
import app.atlas.services.SavedCamera
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.CurrentWeather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.pow

/** The category row's answer: which category, whether it is still looking, the places and where they came from. */
data class CategoryResults(
    val categoryId: String,
    val loading: Boolean,
    val places: List<app.atlas.model.Place> = emptyList(),
    val source: PlaceOrigin? = null,
    val error: String? = null,
    val centre: LatLon,
)

/**
 * The Map home's state: the camera (restored, saved as it moves), the location and follow mode, the map's look from
 * settings, the weather and its moments, and the places of the chosen category.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val services: Services,
    focus: LatLon?,
    focusZoom: Double?,
    private val clock: () -> Instant = { services.clock.instant() },
    private val zone: ZoneId = services.clock.zone,
) : ViewModel() {
    private val saved: SavedCamera? = services.startCameraNow()

    /**
     * True when the camera came from nowhere in particular (the first fix may move it) or is the demo's: either way it is
     * not saved until the user moves the map, so the demo never replaces the view the user left.
     */
    private var cameraUnset = focus == null && (saved == null || services.ciDemo)

    val map = MapState(
        when {
            focus != null -> CameraPosition(focus, focusZoom ?: 17.0, 0.0, DEFAULT_PITCH)
            // CI's demo (and the bench shortcut) opens on the Westerkerk, which the bundled canal ring draws at once without
            // the network, whatever view was saved: the same frame on the emulator and on a phone
            services.ciDemo -> HomeView.westerkerk
            saved != null -> CameraPosition(saved.target, saved.zoom, saved.bearing, saved.pitch)
            else -> FirstLook.around(services.location.last.value?.location, ::coveredByMap)
        },
    ).apply { time = clock() }

    /**
     * Whether the map has data at [p] on the phone (Q5, plan-vector §8): where the core draws the maps, a window on the
     * phone holds the place (else the first look is the Main board's view of the canal ring, whose windows the
     * Netherlands' tiles give); today's map alone counts every place, as it always has (it fetches what it needs). The
     * list is read as the core starts (MapEngine.warmPacks); only if that has not finished is it read here.
     */
    private fun coveredByMap(p: LatLon): Boolean {
        if (MapEngine.core is CoreState.Canvas) return true
        val list = runCatching { MapEngine.packs() }.getOrNull() ?: return true
        if (!list.loaded) runCatching { list.load() }
        return list.packs.any { p in it.box }
    }

    /** The focus of a geo: link, drawn as a pin. */
    val focus: LatLon? = focus

    private val cameraMoves = MutableStateFlow(map.camera)

    /** The phone's position while the map is on screen (the GPS stops a few seconds after it leaves). */
    val location: StateFlow<Fix?> = services.location.fixes(LocationQuality.MAP)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), services.location.last.value)

    val permitted: StateFlow<Boolean> get() = services.location.permitted

    val style: StateFlow<MapStyle> = services.settings.data.map { p ->
        val theme = services.ciTheme ?: p[SettingsKeys.MAP_THEME]?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: Theme.AUTO
        // The board's map: the home's own markers stand for places, so the tiles' points of interest are off until asked for.
        val layers = p[SettingsKeys.MAP_LAYERS]?.mapNotNull { runCatching { MapLayer.valueOf(it) }.getOrNull() }?.toSet() ?: HomeView.style.layers
        val with3d = if (p[SettingsKeys.MAP_3D] ?: true) layers + MapLayer.BUILDINGS_3D else layers - MapLayer.BUILDINGS_3D
        MapStyle(theme = theme, layers = with3d, language = p[SettingsKeys.NAME_LANGUAGE], calm = true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MapStyle(calm = true))

    private val forecastState = MutableStateFlow<Forecast?>(null)

    /** The forecast behind the chip and the moments; kept fresh while [weather] is watched. */
    val forecast: StateFlow<Forecast?> = forecastState.asStateFlow()

    /** A tick a minute, so what depends on the time (the sky now, the moments) moves on by itself. */
    private val minutes = flow {
        while (true) {
            emit(clock())
            delay(60_000)
        }
    }

    /**
     * Keeps the forecast fresh while it is watched: for where you are (or, without a fix, where the map looks), again
     * when you move far, when it ages, a little after a failed try, and as soon as the connection comes back. It reads
     * the last fix rather than asking for new ones, so it never keeps the GPS on by itself.
     */
    private val forecastKeeper = combine(services.location.last, cameraMoves.debounce(1_500), services.connectivity.online, minutes) { fix, cam, online, _ ->
        refreshWeather(fix?.location ?: cam.target, online && !services.config.offline)
    }

    /** The sky and temperature now, for the chip; null while no forecast reaches now. Watching it keeps it fresh. */
    val weather: StateFlow<CurrentWeather?> = channelFlow {
        launch { forecastKeeper.collect() }
        combine(forecastState, minutes) { f, now -> CurrentWeather.of(f, now) }.collect { send(it) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val moments: StateFlow<List<Moment>> = combine(forecastState, cameraMoves.debounce(500), minutes) { f, cam, now ->
        Moments.of(f, location.value?.location ?: cam.target, now, zone)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The area's highlights on the map before a category is chosen: around you (or, without a fix, where the map
     * looks), again when either moves on, as time passes (what is open) and as saved places change.
     */
    val highlights: StateFlow<List<Highlight>> = combine(
        location.map { it?.location }.distinctUntilChanged { a, b -> a != null && b != null && Geo.distance(a, b) < HIGHLIGHT_MOVE_M },
        cameraMoves.debounce(800).map { it.target }.distinctUntilChanged { a, b -> Geo.distance(a, b) < HIGHLIGHT_MOVE_M },
        minutes,
        services.saved.lists,
        services.regionsChanged,
    ) { you, target, now, lists, _ ->
        val anchor = you ?: target
        val saved = lists.flatMap { l -> l.places.map { it.place } }.distinctBy { it.key }
        withContext(Dispatchers.IO) {
            runCatching { HomeHighlights.pick(services.placeStores, anchor, now.atZone(zone).toLocalDateTime(), saved) }.getOrDefault(emptyList())
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The Main board's names over the map ("IJ", "Jordaan") for where the map looks, from the packs, again when it
     * moves on; the map sets each where it fits.
     */
    val labels: StateFlow<List<MapLabel>> = cameraMoves.debounce(800)
        .distinctUntilChanged { a, b -> Geo.distance(a.target, b.target) < LABEL_MOVE_M && abs(a.zoom - b.zoom) < 0.5 }
        .map { c ->
            withContext(Dispatchers.IO) {
                val radius = (LABEL_RADIUS_M * 2.0.pow(16.0 - c.zoom)).coerceIn(600.0, 6_000.0)
                runCatching { services.localData.mapNames(c.target, radius) }.getOrDefault(emptyList()).map { n ->
                    MapLabel(if (n.water) "water:${n.name}" else "area:${n.name}", n.name, if (n.water) LabelKind.WATER else LabelKind.AREA, n.points, n.rank)
                }
            }
        }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val resultsState = MutableStateFlow<CategoryResults?>(null)
    val results: StateFlow<CategoryResults?> = resultsState.asStateFlow()
    private var resultsJob: Job? = null

    /** Where and when (elapsed time, not the wall clock) the forecast was last looked for, and how that went. */
    private var triedAt: LatLon? = null
    private var triedAtMs = 0L
    private var gotFresh = false
    private var wasOnline = false

    init {
        // Save the view a moment after it stops moving, for the next launch; but not the stand-in view of a first
        // launch without a fix, which would keep the next launch there instead of going to where the phone is.
        viewModelScope.launch {
            cameraMoves.debounce(800).collect { c ->
                if (!cameraUnset) runCatching { services.settings.saveCamera(SavedCamera(c.target, c.zoom, c.bearing, c.pitch)) }
            }
        }
        // The first fix moves a camera that had nowhere better to be. It waits on the last fix (which the screen's own
        // watching updates) so that, indoors with no fix, it does not keep the GPS on after the map has gone.
        viewModelScope.launch {
            val fix = services.location.last.filterNotNull().first()
            if (cameraUnset && !services.ciDemo) {
                cameraUnset = false
                val covered = withContext(Dispatchers.IO) { coveredByMap(fix.location) }
                val look = FirstLook.around(fix.location) { covered }
                if (look != map.camera) map.animateTo(look)
            }
        }
        // The light and shadows follow the app's clock (a test's fixed one too), a minute at a time.
        viewModelScope.launch { minutes.collect { map.time = it } }
        // CI's scripted run of the core's transitional rule (MainActivity's atlas.vk.script; debug and bench builds).
        if (MapEngine.script == TRANSITION_SCRIPT) {
            MapEngine.script = null
            viewModelScope.launch { transitionScript() }
        }
    }

    /**
     * CI's scripted switch of the transitional rule (plan-vector K11): once the core's first picture is final, the camera
     * jumps to a place the core has no tiles for ([AWAY]: today's map there, or its own honest state offline), and
     * once today's map has its picture there and the cross-fade is done, back to the start; `vk script …` lines mark
     * each step for smoke.sh, which reads the rule's own `vk transition` lines between them.
     */
    private suspend fun transitionScript() {
        val log = { line: String -> android.util.Log.i("AtlasMap", line) }
        val start = map.camera
        awaitStatus(SCRIPT_WAIT_MS) { it is MapStatus.Ready }
        delay(SCRIPT_SETTLE_MS)
        log("vk script: away to ${AWAY.lat},${AWAY.lon}")
        map.camera = start.copy(target = AWAY)
        delay(SCRIPT_SETTLE_MS)
        awaitStatus(SCRIPT_WAIT_MS) { it !is MapStatus.Loading }
        delay(SCRIPT_SETTLE_MS)
        log("vk script: back")
        map.camera = start
        delay(SCRIPT_SETTLE_MS)
        awaitStatus(SCRIPT_WAIT_MS) { it is MapStatus.Ready }
        delay(SCRIPT_SETTLE_MS)
        log("vk script done")
    }

    /** Waits until the map's status passes [test], at most [timeoutMs]. */
    private suspend fun awaitStatus(timeoutMs: Long, test: (MapStatus) -> Boolean) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { androidx.compose.runtime.snapshotFlow { map.status }.first(test) }
    }

    fun onCameraMove(camera: CameraPosition, byUser: Boolean) {
        if (byUser) {
            cameraUnset = false
            if (map.follow != Follow.NONE) map.follow = Follow.NONE
        }
        cameraMoves.value = camera
    }

    /** The locate button: centre on the phone and follow it; again, follow its heading; again, just its position. */
    fun locate() {
        val fix = location.value ?: services.location.last.value
        map.follow = when (map.follow) {
            Follow.NONE -> Follow.POSITION
            Follow.POSITION -> Follow.HEADING
            else -> Follow.POSITION
        }
        if (fix != null) viewModelScope.launch {
            map.animateTo(map.camera.copy(target = fix.location, zoom = maxOf(map.camera.zoom, 16.5)))
        }
    }

    /** The compass button: north up and looking straight down, gliding there. */
    fun northUp() {
        viewModelScope.launch {
            if (map.follow == Follow.HEADING) map.follow = Follow.POSITION
            map.animateTo(map.camera.copy(bearing = 0.0))
        }
    }

    /** The 3D button: tilts the view and raises the buildings, or flattens both. */
    fun toggle3d() {
        val on = MapLayer.BUILDINGS_3D !in style.value.layers
        viewModelScope.launch {
            try {
                services.settings.set(SettingsKeys.MAP_3D, on)
            } catch (e: IOException) {
                // A full or read-only disk: the look is a setting, and it could not change. Nothing else is wrong.
                return@launch
            }
            map.animateTo(map.camera.copy(pitch = if (on) DEFAULT_PITCH else 0.0))
        }
    }

    /** Shows the places of [categoryId] around the view; the same again clears them. */
    fun selectCategory(categoryId: String?) {
        resultsJob?.cancel()
        if (categoryId == null || resultsState.value?.categoryId == categoryId) {
            resultsState.value = null
            return
        }
        val centre = map.camera.target
        val radius = radiusFor(map.camera.zoom)
        resultsState.value = CategoryResults(categoryId, loading = true, centre = centre)
        resultsJob = viewModelScope.launch {
            var last: NearbyPlaces? = null
            try {
                services.places.nearby(categoryId, centre, radius, MAX_RESULTS).collect { r ->
                    // An online answer replaces the tiles' unless it failed and the tiles had something.
                    val keep = last
                    val use = if (r.error != null && keep != null && keep.places.isNotEmpty()) keep.copy(error = r.error) else r
                    last = use
                    resultsState.value = CategoryResults(categoryId, loading = false, places = use.places, source = use.source, error = use.error, centre = centre)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resultsState.value = CategoryResults(categoryId, false, last?.places.orEmpty(), last?.source, e.message ?: e.toString(), centre)
                return@launch
            }
            if (last == null) resultsState.value = CategoryResults(categoryId, loading = false, centre = centre)
        }
    }

    /**
     * Fetches the forecast for [at] when it is due: never looked for yet, moved [WEATHER_MOVE_M] since, older than
     * [WEATHER_MAX_AGE_MS], or [WEATHER_RETRY_MS] after a try that brought nothing fresh while online, or at once when
     * the connection comes back after one. Ages are measured on the elapsed-time clock, which the wall clock (or a
     * test's fixed one) does not move.
     */
    private suspend fun refreshWeather(at: LatLon, online: Boolean) {
        val nowMs = SystemClock.elapsedRealtime()
        val cameBack = online && !wasOnline
        wasOnline = online
        val last = triedAt
        val wait = if (gotFresh || !online) WEATHER_MAX_AGE_MS else WEATHER_RETRY_MS
        val due = last == null || Geo.distance(last, at) >= WEATHER_MOVE_M || nowMs - triedAtMs >= wait || (cameBack && !gotFresh)
        if (!due) return
        triedAt = at
        triedAtMs = nowMs
        val f = withContext(Dispatchers.IO) { forecastOrKept(services.weather, at, online) }
        gotFresh = f != null && !f.kept
        if (f != null) forecastState.value = f
    }

    companion object {
        const val DEFAULT_PITCH = 50.0
        private const val HIGHLIGHT_MOVE_M = 150.0
        private const val LABEL_MOVE_M = 200.0

        /** How far around the target names are looked for at zoom 16 (twice at 15, half at 17). */
        private const val LABEL_RADIUS_M = 1_500.0
        const val MAX_RESULTS = 60
        private const val WEATHER_MOVE_M = 5_000.0
        private const val WEATHER_MAX_AGE_MS = 30 * 60_000L
        private const val WEATHER_RETRY_MS = 2 * 60_000L

        /** How far around the view to look for a category: about the visible area at that zoom. */
        fun radiusFor(zoom: Double): Double = (800.0 * Math.pow(2.0, 16.0 - zoom)).coerceIn(300.0, 5_000.0)

        /** CI's script of the core's transitional rule (MainActivity's `atlas.vk.script transition`). */
        const val TRANSITION_SCRIPT = "transition"

        /** Where the script goes: Utrecht's centre, which no pack of the core holds. */
        val AWAY = LatLon(52.0907, 5.1214)
        private const val SCRIPT_WAIT_MS = 180_000L
        private const val SCRIPT_SETTLE_MS = 2_000L
    }
}

/**
 * The forecast for [at]: fetched when [online], else, or when the fetch fails in any way (no network, a server error, a
 * captive portal's login page where JSON should be), the one kept on the phone; null when there is neither.
 */
internal suspend fun forecastOrKept(weather: Weather, at: LatLon, online: Boolean): Forecast? {
    if (online) {
        try {
            return weather.forecast(at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fall back to the kept forecast below.
        }
    }
    return try {
        weather.kept(at)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
