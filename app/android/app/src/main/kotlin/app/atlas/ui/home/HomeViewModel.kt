package app.atlas.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.datastore.preferences.core.Preferences
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import app.atlas.Services
import app.atlas.geo.BBox
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
import app.atlas.services.SavedCamera
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.CurrentWeather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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

/**
 * The Map home's state: the camera (restored, saved as it moves), the location and follow mode, the map's look from
 * settings, the weather and its moments, and the places of the chosen category. A link's [focus] starts the camera
 * there; [pinned] (the map is all the link opened, a map view) pins it too.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val services: Services,
    focus: LatLon?,
    focusZoom: Double?,
    pinned: Boolean = false,
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
            // CI's demo (and the bench shortcut) opens on the Westerkerk, whatever view was saved: the same frame on the
            // emulator (the canal ring CI puts there) and on a phone (the windows of the Netherlands' tiles)
            services.ciDemo -> HomeView.westerkerk
            saved != null -> CameraPosition(saved.target, saved.zoom, saved.bearing, saved.pitch)
            else -> FirstLook.around(services.location.last.value?.location, ::coveredByMap)
        },
    ).apply { time = clock() }

    /**
     * Whether the map has data at [p] on the phone (Q5, plan-vector §8): a window on the phone holds the place (else the
     * first look is the Main board's view of the canal ring, whose windows the Netherlands' tiles give); with no core in
     * this process (the JVM's tests) every place counts. The list is read as the core starts (MapEngine.warmPacks); only
     * if that has not finished is it read here.
     */
    private fun coveredByMap(p: LatLon): Boolean {
        if (MapEngine.core is CoreState.None) return true
        val list = runCatching { MapEngine.packs() }.getOrNull() ?: return true
        if (!list.loaded) runCatching { list.load() }
        return list.packs.any { p in it.box }
    }

    /**
     * The point of a link that opened only the map (a map view), drawn as a pin until the map is moved away from it;
     * null otherwise. Under a link's screen the map pins nothing: the screen shows its own point, which leaves with it.
     */
    var pin: LatLon? by mutableStateOf(if (pinned) focus else null)
        private set

    private val cameraMoves = MutableStateFlow(map.camera)

    /** The phone's position while the map is on screen (the GPS stops a few seconds after it leaves). */
    val location: StateFlow<Fix?> = services.location.fixes(LocationQuality.MAP)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), services.location.last.value)

    val permitted: StateFlow<Boolean> get() = services.location.permitted

    val style: StateFlow<MapStyle> = services.settings.data.map { mapStyleOf(it, services.ciTheme) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MapStyle(calm = true))

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

    /** Where and when (elapsed time, not the wall clock) the forecast was last looked for, and how that went. */
    private var triedAt: LatLon? = null
    private var triedAtMs = 0L
    private var gotFresh = false
    private var wasOnline = false

    /** The fingers' last tilt as the settings keep it ([SettingsKeys.MAP_TILT]). */
    private var savedTilt: Double? = null

    init {
        // Save the view a moment after it stops moving, for the next launch; but not the stand-in view of a first
        // launch without a fix, which would keep the next launch there instead of going to where the phone is.
        viewModelScope.launch {
            cameraMoves.debounce(800).collect { c ->
                if (!cameraUnset) runCatching { services.settings.saveCamera(SavedCamera(c.target, c.zoom, c.bearing, c.pitch)) }
                // the tilt last set with the fingers (a tilt ends in a move), which the 3D button brings back next time too
                map.chosenTilt?.let { t -> if (t != savedTilt) runCatching { services.settings.set(SettingsKeys.MAP_TILT, t) }.onSuccess { savedTilt = t } }
            }
        }
        viewModelScope.launch {
            val kept = services.settings.flow(SettingsKeys.MAP_TILT).first()
            savedTilt = kept
            if (kept != null && map.chosenTilt == null) map.chosenTilt = kept
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
        // CI's scripted runs of the core (MainActivity's atlas.vk.script; debug and bench builds): away from its tiles and
        // back, and the world beyond the countries
        when (MapEngine.script) {
            AWAY_SCRIPT -> {
                MapEngine.script = null
                viewModelScope.launch { awayScript() }
            }
            WORLD_SCRIPT -> {
                MapEngine.script = null
                viewModelScope.launch { worldScript() }
            }
        }
    }

    /**
     * CI's scripted jump away from the core's tiles (plan-vector K11, ENGINE.md step 3): once the core's first picture is
     * final, the camera jumps to a place no pack on the phone holds ([AWAY]), where the core draws its own cover and the
     * map says why (CI's runs build no windows: no map data there), and back to the start, where its picture is complete
     * again; `vk script …` lines mark each step with the map's status for smoke.sh. Until 5 November the script switched
     * the transitional rule to today's map and back.
     */
    private suspend fun awayScript() {
        val log = { line: String -> android.util.Log.i("AtlasMap", line) }
        val start = map.camera
        awaitStatus(SCRIPT_WAIT_MS) { it is MapStatus.Ready }
        delay(SCRIPT_SETTLE_MS)
        log("vk script: away to ${AWAY.lat},${AWAY.lon}")
        map.camera = start.copy(target = AWAY)
        delay(SCRIPT_SETTLE_MS)
        awaitStatus(SCRIPT_WAIT_MS) { it !is MapStatus.Loading }
        log("vk script: away ${said(map.status)}")
        delay(SCRIPT_SETTLE_MS)
        log("vk script: back")
        map.camera = start
        delay(SCRIPT_SETTLE_MS)
        awaitStatus(SCRIPT_WAIT_MS) { it is MapStatus.Ready }
        log("vk script: back ${said(map.status)}")
        delay(SCRIPT_SETTLE_MS)
        log("vk script done")
    }

    /** What the map says of its picture, for a script's line. */
    private fun said(s: MapStatus): String = when (s) {
        is MapStatus.Ready -> "ready"
        is MapStatus.Loading -> "loading"
        is MapStatus.Downloading -> "downloading ${s.region}"
        is MapStatus.NoData -> "no map data: ${s.reason}"
    }

    /**
     * CI's scripted look at the world beyond the countries (ENGINE.md step 5; smoke.sh's world phase, with
     * `atlas.ci.world`): once the core's first picture is final, the camera goes out from the start to each of
     * [WORLD_VIEWS] in turn, waits there for the world's packs of the start's block (the first start's index and globe,
     * then those packs' heads, which the view's first frames there ask for) and then for the map's picture to be final
     * (the chunks of the tiles in view, which its frames ask for by ranges),
     * and holds it [WORLD_HOLD_MS] for smoke.sh's screenshot; then back. `vk script …` lines mark each step for smoke.sh,
     * which reads the world's fetch (`vk world` lines) and the core's rests (`vk tiles … world` lines) between them.
     */
    private suspend fun worldScript() {
        val log = { line: String -> android.util.Log.i("AtlasMap", line) }
        val start = map.camera
        awaitStatus(SCRIPT_WAIT_MS) { it is MapStatus.Ready }
        delay(SCRIPT_SETTLE_MS)
        for ((km, pitch) in WORLD_VIEWS) {
            log("vk script: world out to $km km")
            map.camera = start.copy(zoom = HomeView.zoomForDistance(km * 1_000.0, HomeView.HEIGHT_DP, start.target.lat), pitch = pitch)
            val here = kotlinx.coroutines.withTimeoutOrNull(WORLD_WAIT_MS) {
                while (!worldHere(start.target)) delay(1_000)
            } != null
            log("vk script: world's packs ${if (here) "on the phone" else "not on the phone within ${WORLD_WAIT_MS / 1_000} s"}")
            // the maps handed the packs, and the core's frames with them under way
            delay(WORLD_SETTLE_MS)
            val final = awaitStatus(WORLD_WAIT_MS) { it is MapStatus.Ready }
            log("vk script: world at $km km ${if (final) "final" else "not final: ${map.status}"}")
            delay(WORLD_HOLD_MS)
        }
        log("vk script: back")
        map.camera = start
        delay(SCRIPT_SETTLE_MS)
        awaitStatus(SCRIPT_WAIT_MS) { it is MapStatus.Ready }
        delay(SCRIPT_SETTLE_MS)
        log("vk script done")
    }

    /**
     * Whether the world's index and its globe are on the phone (WorldFetch's files) and the maps have the packs holding
     * [place] (their heads in the world's cache, so PackList lists them).
     */
    private suspend fun worldHere(place: LatLon): Boolean = withContext(Dispatchers.IO) {
        val dir = MapEngine.worldDir() ?: return@withContext false
        val idx = java.io.File(dir, app.atlas.tiles.WorldFetch.INDEX)
        if (!idx.isFile || !java.io.File(dir, app.atlas.tiles.WorldFetch.GLOBE).isFile) return@withContext false
        val index = runCatching { app.atlas.tiles.WorldIndex.read(idx.readBytes()) }.getOrNull() ?: return@withContext false
        val listed = MapEngine.packs().worldRefs.mapTo(HashSet()) { it.id }
        app.atlas.tiles.WorldFetch.packsAt(index, place.lat, place.lon).all { app.atlas.tiles.WorldFetch.idOf(it) in listed }
    }

    /** Waits until the map's status passes [test], at most [timeoutMs]; false where it did not. */
    private suspend fun awaitStatus(timeoutMs: Long, test: (MapStatus) -> Boolean): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { androidx.compose.runtime.snapshotFlow { map.status }.first(test) } != null

    fun onCameraMove(camera: CameraPosition, byUser: Boolean) {
        if (byUser) {
            cameraUnset = false
            if (map.follow != Follow.NONE) map.follow = Follow.NONE
        }
        // the ground in view is the moved camera's: the map sets it just before it tells of the move
        if (pin != null) pin = pinAfterMove(pin, map.visibleBounds)
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

    /**
     * The 3D button: tilts the view (to the tilt last set with the fingers, [DEFAULT_PITCH] until then) and raises the
     * buildings, or flattens both.
     */
    fun toggle3d() {
        val on = MapLayer.BUILDINGS_3D !in style.value.layers
        viewModelScope.launch {
            try {
                services.settings.set(SettingsKeys.MAP_3D, on)
            } catch (e: IOException) {
                // A full or read-only disk: the look is a setting, and it could not change. Nothing else is wrong.
                return@launch
            }
            map.animateTo(map.camera.copy(pitch = if (on) map.chosenTilt ?: DEFAULT_PITCH else 0.0))
        }
    }

    /**
     * The place opened from the map or the results, shown over the map (PlaceOverMap): the view stays as the user left
     * it (the user, 30 October: "When you click a place it shouldnt take you away from your view and in that overhead
     * camera"). Null while none is.
     */
    var opened: Place? by mutableStateOf(null)
        private set

    /** The opened place's model: kept while it stays open (across the screens it opens) and cleared when it closes. */
    val placeModels: ViewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    /** Opens [place] over the map, in place of the one open; null closes it. */
    fun open(place: Place?) {
        if (place?.key == opened?.key) return
        placeModels.viewModelStore.clear()
        opened = place
    }

    /**
     * Brings [place] into [band] (screen px) with the least pan, the zoom, bearing and tilt kept, so an opened place
     * shows above its sheet: nothing moves when it is there already.
     */
    fun reveal(place: LatLon, band: Rect) {
        val to = revealTarget(
            map.camera.target, place, band,
            toScreen = { p -> map.latLonToScreen(p)?.let { (x, y) -> Offset(x, y) } },
            toGround = { o -> map.screenToLatLon(o.x, o.y) },
        ) ?: return
        map.follow = Follow.NONE
        viewModelScope.launch { map.animateTo(map.camera.copy(target = to), REVEAL_MS) }
    }

    override fun onCleared() {
        placeModels.viewModelStore.clear()
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

        /** The glide that brings an opened place out from under its sheet. */
        private const val REVEAL_MS = 450
        private const val HIGHLIGHT_MOVE_M = 150.0
        private const val LABEL_MOVE_M = 200.0

        /** How far around the target names are looked for at zoom 16 (twice at 15, half at 17). */
        private const val LABEL_RADIUS_M = 1_500.0
        private const val WEATHER_MOVE_M = 5_000.0
        private const val WEATHER_MAX_AGE_MS = 30 * 60_000L
        private const val WEATHER_RETRY_MS = 2 * 60_000L

        /** CI's script away from the core's tiles and back (MainActivity's `atlas.vk.script away`). */
        const val AWAY_SCRIPT = "away"

        /**
         * Where the script goes: Lille's centre, which no pack on the phone holds and no country's tiles the core draws, so
         * the core draws its own cover there (the canal ring's pack, the nearest) and the map says it has no data.
         */
        val AWAY = LatLon(50.6292, 3.0573)
        private const val SCRIPT_WAIT_MS = 180_000L
        private const val SCRIPT_SETTLE_MS = 2_000L

        /** CI's script of the world beyond the countries (MainActivity's `atlas.vk.script world`, with `atlas.ci.world`). */
        const val WORLD_SCRIPT = "world"

        /**
         * Where the world script looks from, km from the start's target and pitch: the countries' cover beside the world
         * (the canal ring's tiles at 25 km pitched 60°, ENGINE.md step 5's seam), and the world alone at 100 km pitched 30°.
         */
        private val WORLD_VIEWS = listOf(25 to 60.0, 100 to 30.0)

        /** How long the script waits for the world's packs' heads, then for the picture: the index, the globe's 9.7 MB and the chunks over the emulator's network. */
        private const val WORLD_WAIT_MS = 300_000L
        private const val WORLD_SETTLE_MS = 10_000L
        private const val WORLD_HOLD_MS = 15_000L
    }
}

/** The map's look the user picked in the Look settings ([ciTheme]: the sky a CI run sets), as the Map home draws it. */
internal fun mapStyleOf(p: Preferences, ciTheme: Theme?): MapStyle {
    val theme = ciTheme ?: p[SettingsKeys.MAP_THEME]?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: Theme.AUTO
    // The board's map: the home's own markers stand for places, so the tiles' points of interest are off until asked for.
    val layers = p[SettingsKeys.MAP_LAYERS]?.mapNotNull { runCatching { MapLayer.valueOf(it) }.getOrNull() }?.toSet() ?: HomeView.style.layers
    val with3d = if (p[SettingsKeys.MAP_3D] ?: true) layers + MapLayer.BUILDINGS_3D else layers - MapLayer.BUILDINGS_3D
    return MapStyle(theme = theme, layers = with3d, language = p[SettingsKeys.NAME_LANGUAGE], calm = true)
}

/**
 * A link's [pin] once the map has moved to show [ground] (the ground in view; null before the view's first frame): it
 * goes when the map is moved away from it, its point out of view, by the user's fingers or a button.
 */
internal fun pinAfterMove(pin: LatLon?, ground: BBox?): LatLon? = pin?.takeUnless { ground != null && it !in ground }

/**
 * Where the camera's target goes for the ground at [place] to show inside [band] (screen px), nothing else of the view
 * changed: null when it shows there already, or when the view cannot say where its ground is (before its first frame).
 * The least move: the place lands on the band's nearest point, or on its middle when the view does not show it. A pan
 * moves all the ground alike, so the target moves by what lies between the ground under that point and the place.
 */
internal fun revealTarget(target: LatLon, place: LatLon, band: Rect, toScreen: (LatLon) -> Offset?, toGround: (Offset) -> LatLon?): LatLon? {
    val at = toScreen(place)
    if (at != null && band.contains(at)) return null
    val to = at?.let { Offset(it.x.coerceIn(band.left, band.right), it.y.coerceIn(band.top, band.bottom)) } ?: band.center
    val under = toGround(to) ?: return null
    return LatLon(target.lat + place.lat - under.lat, target.lon + place.lon - under.lon)
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
