package app.atlas.ui.go

import app.atlas.geo.Geo
import app.atlas.guide.Cue
import app.atlas.guide.Detour
import app.atlas.guide.EarconKind
import app.atlas.guide.GuideSettings
import app.atlas.guide.HapticMeaning
import app.atlas.guide.Priority
import app.atlas.guide.Simulator
import app.atlas.model.Mode
import app.atlas.model.RouteRequest
import app.atlas.route.RouteResult
import app.atlas.route.Router
import app.atlas.services.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * A guided trip end to end on the real demo routes (BRouter's, from the Westerkerk): the `:guide` navigator's cues
 * reach the voice as speech, earcons and vibrations; leaving the route asks the router for a new one; the truck's hours
 * are counted.
 */
class NavSessionTest {
    /** A voice that keeps every cue. */
    class RecordingVoice : Voice {
        val cues = ArrayList<Cue>()
        private val said = MutableStateFlow<String?>(null)
        override val ready: StateFlow<Boolean> = MutableStateFlow(true)
        override val lastSaid: StateFlow<String?> = said
        override fun play(cue: Cue) {
            cues += cue
            if (cue is Cue.Speak) said.value = cue.text
        }

        override fun say(text: String, priority: Priority) = play(Cue.Speak(text, priority = priority))
        override fun repeat() = Unit
        override fun stop() = Unit
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val start = Instant.parse("2026-10-14T08:00:00Z")

    private fun session(mode: Mode, voice: Voice = RecordingVoice(), router: Router = Router { RouteResult.NoRoute("none") }, tacho: TachoState? = null): NavSession {
        val route = GoFixtures.fixture(mode)!!
        val req = RouteRequest(mode, listOf(route.points.first(), route.points.last()), vehicle = GoStore.defaultVehicle(mode))
        val settings = GuideSettings(mode = mode, vehicle = req.vehicle, zone = ZoneId.of("Europe/Amsterdam"))
        return NavSession(req, route, "Amsterdam Centraal", router, voice, settings, null, scope, demo = true, tachoStart = tacho)
    }

    @Test
    fun aRideIsSpokenPlayedAndFeltToTheEnd() {
        val voice = RecordingVoice()
        val s = session(Mode.BIKE, voice)
        val fixes = Simulator(s.view.value.route, start = start).fixes().toList()
        var lastAlong = -1.0
        for (f in fixes) {
            s.onFix(f)
            val v = s.view.value
            assertTrue("along goes forward", v.travelled >= lastAlong - 1.0)
            lastAlong = v.travelled
        }
        val v = s.view.value
        assertTrue(v.arrived)
        assertEquals(fixes.size, v.fixes)
        val spoken = voice.cues.filterIsInstance<Cue.Speak>().map { it.text }
        assertTrue("$spoken", spoken.any { "Singel" in it })
        // Each turn is heard before it is said, and felt: rising for right, two taps for left.
        val earcons = voice.cues.filterIsInstance<Cue.Earcon>().map { it.kind }
        assertTrue("$earcons", EarconKind.ARRIVE in earcons)
        assertTrue("$earcons", earcons.any { it == EarconKind.TURN_RIGHT || it == EarconKind.KEEP_RIGHT })
        val felt = voice.cues.filterIsInstance<Cue.Haptic>()
        assertTrue(felt.any { it.meaning == HapticMeaning.ARRIVE })
        felt.filter { it.meaning == HapticMeaning.RIGHT }.forEach { assertEquals(3, it.pulses) }
        felt.filter { it.meaning == HapticMeaning.LEFT }.forEach { assertEquals(2, it.pulses) }
        assertNotNull(v.instruction)
    }

    @Test
    fun theViewSaysTheNextTurnAndItsStreet() {
        val s = session(Mode.WALK)
        val fixes = Simulator(s.view.value.route, start = start).fixes().take(20).toList()
        fixes.forEach(s::onFix)
        val v = s.view.value
        val st = v.state!!
        assertNotNull(st.next)
        assertEquals(st.next!!.street ?: st.next!!.ref, v.street)
        assertTrue(v.instruction!!.isNotBlank())
        assertTrue(st.distanceToManeuver > 0)
        assertEquals(st.along, v.travelled, 1e-9)
    }

    @Test
    fun leavingTheRouteAsksForANewOneFromWhereYouAre() {
        val asked = ArrayList<RouteRequest>()
        val walk = GoFixtures.fixture(Mode.WALK)!!
        val router = Router { req ->
            asked += req
            RouteResult.Found(listOf(walk))
        }
        val s = session(Mode.WALK, router = router)
        // A wrong turn 300 m in, walking 250 m away without coming back.
        val fixes = Simulator(walk, start = start, detours = listOf(Detour(at = 300.0, turn = 90.0, length = 250.0))).fixes().toList()
        fixes.forEach(s::onFix)
        assertTrue("rerouted", asked.isNotEmpty())
        val req = asked.first()
        assertEquals(Mode.WALK, req.mode)
        assertTrue(Geo.distance(req.waypoints.last(), walk.points.last()) < 1.0)
        assertTrue("from off the route", Geo.distance(req.waypoints.first(), walk.points.first()) > 100)
        assertFalse(s.view.value.rerouting)
        assertNull(s.view.value.rerouteFailed)
    }

    @Test
    fun aFailedRerouteIsShown() {
        val walk = GoFixtures.fixture(Mode.WALK)!!
        val s = session(Mode.WALK, router = Router { RouteResult.NoData(listOf("E0_N50.rd5")) })
        Simulator(walk, start = start, detours = listOf(Detour(at = 300.0, turn = 90.0, length = 250.0))).fixes().forEach(s::onFix)
        assertNotNull(s.view.value.rerouteFailed)
    }

    @Test
    fun aTrucksHoursAreCounted() {
        val saved = ArrayList<TachoState>()
        val route = GoFixtures.fixture(Mode.TRUCK)!!
        val req = RouteRequest(Mode.TRUCK, listOf(route.points.first(), route.points.last()), vehicle = GoStore.defaultVehicle(Mode.TRUCK))
        val s = NavSession(
            req, route, "Haarlem", Router { RouteResult.NoRoute("") }, RecordingVoice(), GuideSettings(mode = Mode.TRUCK, vehicle = req.vehicle),
            null, scope, demo = true, tachoStart = TachoState(drivingSinceBreak = 3600), onTacho = { saved += it },
        )
        val fixes = Simulator(route, start = start).fixes().toList()
        fixes.forEach(s::onFix)
        val t = s.view.value.tacho!!
        val trip = (fixes.last().time.epochSecond - fixes.first().time.epochSecond)
        // Driven: the hour before, plus the trip less the stops at its end.
        assertTrue("block ${t.drivingSinceBreak} after a $trip s trip", t.drivingSinceBreak in 3600 + trip - 120..3600 + trip)
        assertTrue(saved.isNotEmpty())
    }

    @Test
    fun stopEndsTheSession() {
        val s = session(Mode.BIKE)
        s.stop()
        assertTrue(s.finished)
        val before = s.view.value
        Simulator(s.view.value.route, start = start).fixes().take(5).forEach(s::onFix)
        assertEquals(before, s.view.value)
    }

    @Test
    fun theDemoFeedIsTheSimulatorsFixes() = runBlocking {
        val route = GoFixtures.fixture(Mode.BIKE)!!
        val fixes = NavSession.demoFixes(route, start, interval = 1.0, speedup = 1000.0).take(5).toList()
        assertEquals(5, fixes.size)
        assertEquals(start, fixes.first().time)
        assertTrue(Geo.distance(fixes.first().location, route.points.first()) < 1.0)
    }
}
