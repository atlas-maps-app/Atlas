package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.model.Mode
import app.atlas.model.RouteRequest
import app.atlas.model.Vehicle
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.route.RouteResult
import app.atlas.services.Units
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.BackButton
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.ProgressRing
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.rememberHere
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.cos

/** Everything a guidance screen draws from, gathered once per frame of the trip. */
@Immutable
data class NavShow(
    val view: NavView,
    val mode: Mode,
    val destination: String?,
    val units: Units,
    val zone: ZoneId,
    val lastSaid: String?,
    val muted: Boolean,
    val haptics: Boolean,
    /** The trip goes on with the screen off (the navigation service runs). */
    val background: Boolean,
    /** The wind against you in km/h (negative: behind you) and its speed, when a forecast is on the phone. */
    val headwindKmh: Double?,
    val windKmh: Double?,
    val vehicle: Vehicle,
    val demo: Boolean,
    val night: Boolean,
)

/** What a guidance screen can ask. */
class NavActions(
    val end: () -> Unit,
    val repeat: () -> Unit,
    val mute: () -> Unit,
    val haptics: () -> Unit,
)

/**
 * Turn by turn: the trip being guided (or, opened by a link or CI, a new one from here to the destination), shown by the
 * screen of its mode's family: Ride for bikes, Drive for cars and motorbikes, Truck, Walk, Sail. [Dest.Navigate.demo]
 * drives the simulator.
 */
@Composable
fun NavigateScreen(args: Dest.Navigate, nav: Navigator) {
    val services = LocalServices.current
    val hub = services.go
    val context = LocalContext.current
    val here = rememberHere()
    val mode = remember(args.mode) { runCatching { Mode.valueOf(args.mode) }.getOrDefault(Mode.WALK) }
    val session by hub.nav.collectAsStateWithLifecycle()
    val current = session?.takeIf { Geo.distance(it.destination, args.to) < 30.0 && it.request.mode == mode && !it.finished }
    var failure by remember(args) { mutableStateOf<Plan?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // The trip ended from outside (the notification's End button): there is nothing left to show, so leave.
    var shown by rememberSaveable { mutableStateOf(false) }
    if (current != null) shown = true
    LaunchedEffect(current == null, shown) { if (shown && current == null) nav.back() }

    LaunchedEffect(args, attempt) {
        if (current != null) return@LaunchedEffect
        failure = null
        try {
            val vehicle = hub.store.vehicleNow(mode)
            val req = RouteRequest(mode, listOf(services.location.last.value?.location ?: here, args.to), hub.store.prefs.first(), vehicle)
            when (val r = hub.plan(req, args.demo)) {
                is RouteResult.Found -> hub.startNav(context, req, r.best, args.toName, hub.guideSettings(mode, vehicle), args.demo)
                is RouteResult.NoData -> failure = Plan.NeedsData(r.missingTiles.map { t ->
                    val name = t.removeSuffix(".rd5")
                    SegmentNeed(name, SegmentOffer.size(context, services.segments, name, services.connectivity.online.value))
                })
                is RouteResult.Failure -> failure = Plan.Failed(Plan.Failed.Kind.NO_ROUTE, r.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = Plan.Failed(Plan.Failed.Kind.ERROR, e.message ?: e.toString())
        }
    }

    // Ending the trip empties the hub, which the effect above answers by leaving.
    val end: () -> Unit = { hub.stopNav() }
    when {
        current != null -> Guidance(current, args, end)
        failure != null -> AtlasTheme(dark = true, night = true) {
            Column(
                Modifier.fillMaxSize().background(NavColours.black).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BackButton({ nav.back() })
                Spacer(Modifier.height(40.dp))
                when (val f = failure) {
                    is Plan.NeedsData -> {
                        var downloading by remember { mutableStateOf<Float?>(null) }
                        StateMessage(
                            "ui-download", stringResource(R.string.go_needs_data_title),
                            text = stringResource(R.string.go_needs_data_text, f.needs.joinToString { it.label }), tint = NavColours.amber,
                        )
                        PrimaryButton(
                            downloading?.let { stringResource(R.string.go_downloading, (it * 100).toInt()) } ?: stringResource(R.string.go_download_data),
                            onClick = {
                                if (downloading == null) scope.launch {
                                    downloading = 0f
                                    runCatching {
                                        for (n in f.needs) SegmentOffer.download(services.regions, services.segments, n.tile).collect { downloading = it }
                                    }
                                    downloading = null
                                    attempt++
                                }
                            },
                            symbol = "ui-download",
                            enabled = services.connectivity.online.value && downloading == null,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    is Plan.Failed -> StateMessage("ui-alert-triangle", stringResource(R.string.go_no_route), text = f.message, tint = NavColours.end)
                    else -> Unit
                }
            }
        }
        else -> AtlasTheme(dark = true, night = true) {
            Box(Modifier.fillMaxSize().background(NavColours.black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    ProgressRing(0.25f, size = 120.dp, stroke = 8.dp, colour = NavColours.lime, track = NavColours.panel) {
                        app.atlas.ui.kit.Symbol(modeSymbol(mode), tint = NavColours.text, size = 44.dp)
                    }
                    Text(stringResource(R.string.go_finding_route), color = NavColours.textSoft, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** The guided trip [session], on its family's screen. */
@Composable
private fun Guidance(session: NavSession, args: Dest.Navigate, end: () -> Unit) {
    val services = LocalServices.current
    val hub = services.go
    val view by session.view.collectAsStateWithLifecycle()
    val said by services.voice.lastSaid.collectAsStateWithLifecycle()
    val muted by hub.muted.collectAsStateWithLifecycle()
    val voice = services.voice as? TtsVoice
    val haptics by (voice?.hapticsOn ?: remember { MutableStateFlow(true) }).collectAsStateWithLifecycle()
    val serviceOn by hub.nav.collectAsStateWithLifecycle()
    val unitsNow by hub.unitsFlow.collectAsStateWithLifecycle()
    val wind by produceWind(session)
    val bearing = view.state?.routeBearing
    val headwind = wind?.let { (speed, from) -> bearing?.let { speed * cos(Math.toRadians(from - it)) } }
    val night = remember(view.state?.time?.epochSecond?.div(600)) {
        app.atlas.ui.kit.isAfterDark(view.state?.location ?: session.destination, services.clock.instant())
    }
    val show = NavShow(
        view = view, mode = session.request.mode, destination = session.destinationName ?: args.toName, units = unitsNow,
        zone = services.clock.zone, lastSaid = said, muted = muted, haptics = haptics, background = serviceOn != null,
        headwindKmh = headwind, windKmh = wind?.first, vehicle = session.request.vehicle, demo = session.demo, night = night,
    )
    val actions = remember(session) {
        NavActions(
            end = end,
            repeat = { session.repeat() },
            mute = { hub.setMuted(!hub.muted.value) },
            haptics = { voice?.let { it.setHaptics(!it.hapticsOn.value) } },
        )
    }
    when (familyOf(session.request.mode)) {
        Family.RIDE -> RideView(show, actions)
        Family.DRIVE -> DriveView(show, actions)
        Family.TRUCK -> DriveView(show, actions, truck = true)
        Family.WALK -> WalkView(show, actions)
        Family.SAIL -> SailView(show, actions)
    }
}

/**
 * The wind where the trip starts, from the forecast (fetched when online, else the one kept on the phone): its speed in
 * km/h and the bearing it comes from; null when neither is there.
 */
@Composable
private fun produceWind(session: NavSession): androidx.compose.runtime.State<Pair<Double, Double>?> {
    val services = LocalServices.current
    return androidx.compose.runtime.produceState<Pair<Double, Double>?>(null, session) {
        val at = session.request.waypoints.first()
        val f = try {
            if (services.connectivity.online.value) services.weather.forecast(at) else services.weather.kept(at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val hour = f?.hourAt(services.clock.instant())
        val speed = f?.now?.windSpeed?.takeIf { it.isFinite() } ?: hour?.windSpeed
        val from = f?.now?.windDirection?.takeIf { it.isFinite() } ?: hour?.windDirection
        value = if (speed != null && from != null && speed.isFinite() && from.isFinite()) speed to from else null
    }
}
