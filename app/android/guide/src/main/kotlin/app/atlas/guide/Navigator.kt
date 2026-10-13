package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Sun
import app.atlas.model.Fix
import app.atlas.model.ManeuverType
import app.atlas.model.Route
import app.atlas.model.Turn
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * One guidance session along a [Route]: feed it every location fix and it says where you are and what to say, play or
 * feel now. It map-matches, tracks the step, estimates the arrival, announces maneuvers and warnings on time, detects
 * leaving the route (then asks for a new one with [Cue.Reroute]) and the arrival.
 *
 * A session may be shared between threads: the app feeds fixes from its location thread while the media button asks
 * for [repeat] on the main thread. Its functions are synchronised and its properties read volatile fields.
 *
 * @param features the map around a maneuver, for landmarks ("turn left after the bridge"); optional.
 * @param destination where the traveller is really going (the door, not the end of the road), for the arrival side.
 */
class Navigator(
    route: Route,
    val settings: GuideSettings,
    features: FeatureLookup? = null,
    private var destination: LatLon? = null,
) {
    private val profile = ModeProfile.of(settings.mode, settings.vehicle)

    /** The phrases in the traveller's language, for the app's own labels. */
    val phrasebook: Phrasebook = Phrasebook.of(settings)
    private val landmarks = Landmarks(features)
    @Volatile private var index = RouteIndex(route, profile.defaultSpeed)
    private val matcher = MapMatcher(index, profile)
    private val announcer = Announcer(index, settings, profile, phrasebook, landmarks)
    @Volatile private var callouts = Callouts.build(index, settings, phrasebook, profile)
    private var calloutReach = reachOf(callouts)
    private val calloutsDone = HashSet<String>()
    private var calloutStart = 0
    private val eta = EtaEstimator(profile)
    private var speed = Double.NaN
    private var lastTime: Instant? = null
    private var stepIndex = 0
    private var arrived = false
    private var arrivalSide = arrivalSide()
    private var lastReroute: Instant? = null
    private var announceNewRoute = false
    private var lastContext: Announcer.Context? = null
    /** Metres along the current route at the last fix; reset by [replaceRoute]. */
    private var lastAlong = 0.0

    /** The route being followed. */
    val route: Route get() = index.route

    /** The prepared route: maneuver distances, time profile, span facts. */
    val routeIndex: RouteIndex get() = index

    /** The warnings planned for this route, in order along it. */
    val plannedCallouts: List<Callout> get() = callouts

    /**
     * The warnings still ahead within [withinM] metres, nearest first, with the distance to each: for a "road ahead" list
     * or the sign of the next height limit. Measured from the last fix.
     */
    @Synchronized
    fun upcoming(withinM: Double = 10_000.0): List<Pair<Callout, Double>> {
        val along = lastAlong
        val out = ArrayList<Pair<Callout, Double>>()
        for (k in calloutStart until callouts.size) {
            val d = callouts[k].along - along
            if (d > withinM) break
            if (d >= 0) out += callouts[k] to d
        }
        return out
    }

    /** The state after the last fix, or null before the first. */
    @Volatile
    var state: NavState? = null
        private set

    /** Takes the next location fix (about once a second) and returns where things stand and what to say, play or feel now. */
    @Synchronized
    fun update(fix: Fix): Guidance {
        val m = matcher.match(fix)
        lastAlong = m.along
        val dt = lastTime?.let { Duration.between(it, fix.time).toMillis() / 1000.0 }?.coerceIn(0.05, 30.0) ?: 1.0
        lastTime = fix.time
        val raw = fix.speed ?: m.speed
        val tau = when (profile.group) { ModeGroup.WALK -> 5.0; ModeGroup.BIKE -> 4.0; ModeGroup.BOAT -> 8.0; else -> 3.0 }
        speed = if (speed.isNaN()) raw else speed + (1 - exp(-dt / tau)) * (raw - speed)

        if (!m.offRoute) stepIndex = stepAfter(m)
        eta.update(index, m.along, fix.time, speed, onRoute = !m.offRoute && !m.coarse)
        val remaining = (index.length - m.along).coerceAtLeast(0.0)
        val cues = ArrayList<Cue>()

        val newRoute = announceNewRoute && !m.offRoute
        if (newRoute) {
            announceNewRoute = false
            if (settings.earcons) cues += Cue.Earcon(EarconKind.REROUTED)
        }
        val firstOwnCue = cues.size

        when {
            arrived -> {}
            // Reached the end by a way of one's own (across a square, through a car park): arrived all the same.
            m.offRoute && nearEnd(fix, m) -> {
                arrived = true
                arrive(cues)
            }
            m.becameOffRoute -> {
                lastReroute = fix.time
                cues += Cue.Reroute(fix)
                if (settings.earcons) cues += Cue.Earcon(EarconKind.OFF_ROUTE)
                if (settings.haptics) cues += Haptics.offRoute()
                speak(phrasebook.phrase("off_route"), Priority.HIGH, false, 0f, "off_route", cues)
            }
            m.offRoute -> {
                // Still off: ask again now and then, in case the first request failed; no more noise.
                val since = lastReroute?.let { Duration.between(it, fix.time).seconds } ?: Long.MAX_VALUE
                if (since >= REROUTE_RETRY_S) {
                    lastReroute = fix.time
                    cues += Cue.Reroute(fix)
                }
            }
            else -> {
                if (m.wrongWay) {
                    cues += Cue.Reroute(fix)
                    if (settings.earcons) cues += Cue.Earcon(EarconKind.U_TURN)
                    if (settings.haptics) cues += Haptics.uTurn()
                    speak(phrasebook.phrase("wrong_way"), Priority.HIGH, false, 0f, "wrong_way", cues)
                }
                if (reachedEnd(fix, m, remaining)) {
                    arrived = true
                    arrive(cues)
                } else {
                    val ctx = context(m.along, fix.time, remaining)
                    val before = cues.size
                    announcer.update(ctx, cues)
                    if (newRoute) newRouteSaid(ctx, firstOwnCue, cues)
                    callouts(m.along, fix, spokeManeuver = cues.size > before && cues.drop(before).any { it is Cue.Speak }, cues)
                }
            }
        }

        val remainingTime = if (arrived) 0.0 else eta.remaining(index, m.along)
        val next = index.steps.getOrNull(stepIndex + 1)
        val distToManeuver = if (next != null) (index.stepAlong[stepIndex + 1] - m.along).coerceAtLeast(0.0) else remaining
        val s = NavState(
            time = fix.time, location = fix.location, snapped = m.snapped, routeBearing = m.routeBearing, along = m.along,
            remaining = if (arrived) 0.0 else remaining, remainingTime = remainingTime,
            eta = fix.time.plusMillis((remainingTime * 1000).toLong()), stepIndex = stepIndex, distanceToManeuver = distToManeuver,
            next = next, following = index.steps.getOrNull(stepIndex + 2), lanes = next?.lanes ?: emptyList(),
            facts = index.factsAt(m.segment), offRoute = m.offRoute, offset = m.offset, confidence = m.confidence,
            progress = if (index.length > 0) (m.along / index.length).coerceIn(0.0, 1.0) else 1.0,
            arrivalSide = arrivalSide, speed = if (speed.isNaN()) 0.0 else speed, arrived = arrived,
        )
        state = s
        return Guidance(s, cues)
    }

    /**
     * Follows [newRoute] from now on (after a [Cue.Reroute]). The session keeps its context: the pace learned for the
     * arrival time, the maneuvers and warnings already announced where the new route shares them, and the voice. The
     * next fix on the new route says "New route" with the coming instruction.
     */
    @Synchronized
    fun replaceRoute(newRoute: Route, newDestination: LatLon? = destination) {
        index = RouteIndex(newRoute, profile.defaultSpeed)
        destination = newDestination
        matcher.reset(index)
        announcer.replace(index)
        callouts = Callouts.build(index, settings, phrasebook, profile)
        calloutReach = reachOf(callouts)
        calloutStart = 0
        lastAlong = 0.0
        // The old route's context would give the new route's steps the wrong distances: "repeat" waits for a fix.
        lastContext = null
        eta.restart()
        stepIndex = 0
        arrived = false
        arrivalSide = arrivalSide()
        lastReroute = null
        announceNewRoute = true
    }

    /**
     * "New route", joined to the instruction due now when there is one (so it is not said twice), else followed by the
     * coming instruction with its distance.
     */
    private fun newRouteSaid(ctx: Announcer.Context, from: Int, cues: MutableList<Cue>) {
        if (!settings.voice) return
        val head = phrasebook.phrase("rerouted")
        val i = (from until cues.size).firstOrNull { cues[it] is Cue.Speak }
        if (i != null) {
            val s = cues[i] as Cue.Speak
            val text = phrasebook.sentences(listOf(head, s.text))
            cues[i] = s.copy(text = text, ssml = Announcer.ssml(text))
            return
        }
        val next = announcer.repeat(ctx)
        val text = phrasebook.sentences(listOfNotNull(head, next?.text))
        cues += Cue.Speak(text, Announcer.ssml(text), Priority.NORMAL, true, next?.pan ?: 0f, "rerouted")
    }

    /** "Repeat" (the headset button): the next instruction with its distance refreshed, or where things stand. */
    @Synchronized
    fun repeat(): List<Cue> {
        val ctx = lastContext ?: return emptyList()
        val text = when {
            arrived -> index.steps.lastOrNull()?.let { phrasebook.instruction(it, StepContext(arrivalSide = arrivalSide)).text } ?: return emptyList()
            matcher.offRoute -> phrasebook.phrase("off_route")
            else -> return listOfNotNull(announcer.repeat(ctx))
        }
        return listOf(Cue.Speak(text, Announcer.ssml(text), Priority.HIGH, false, 0f, "repeat"))
    }

    /** Distance to go, time to go and the arrival time, for the "previous" button or a glance. */
    @Synchronized
    fun summary(): List<Cue> {
        val s = state ?: return emptyList()
        val text = phrasebook.phrase(
            "summary", "distance" to Instr(phrasebook.distance(s.remaining)), "duration" to Instr(phrasebook.duration(s.remainingTime)),
            "time" to Instr(phrasebook.time(s.eta, settings.zone)),
        )
        return listOf(Cue.Speak(text, Announcer.ssml(text), Priority.NORMAL, true, 0f, "summary"))
    }

    private fun context(along: Double, time: Instant, remaining: Double): Announcer.Context {
        val rt = eta.remaining(index, along)
        return Announcer.Context(
            along = along, speed = if (speed.isNaN()) 0.0 else speed, time = time, stepIndex = stepIndex, arrivalSide = arrivalSide,
            remaining = remaining, remainingTime = rt, eta = time.plusMillis((rt * 1000).toLong()),
        ).also { lastContext = it }
    }

    /**
     * The step being driven after match [m]: a maneuver is behind you once the match lies on the leg after it. A match
     * held at the corner of the leg before it (noise, or the moment of turning) has not passed it yet, so its "now" is
     * still said.
     */
    private fun stepAfter(m: Match): Int {
        var k = index.stepAt(m.along + 2.0).coerceAtMost((index.steps.size - 1).coerceAtLeast(0))
        while (k > stepIndex && m.segment < index.steps[k].pointIndex && m.along <= index.stepAlong[k] + 2.0) k--
        return max(stepIndex, k)
    }

    /** The arrival radius, a little wider for a poor fix (but not so wide that a vague one arrives early). */
    private fun arriveRadius(fix: Fix) = profile.arriveRadiusM + (fix.accuracy - 10).coerceIn(0.0, 40.0) * 0.5

    private fun nearEnd(fix: Fix, m: Match): Boolean {
        if (m.coarse) return false
        val end = index.line.points.lastOrNull() ?: return false
        return Geo.distance(fix.location, end) <= arriveRadius(fix)
    }

    private fun reachedEnd(fix: Fix, m: Match, remaining: Double): Boolean {
        val pts = index.line.points
        if (pts.isEmpty()) return false
        val r = arriveRadius(fix)
        if (remaining <= r && m.onRoute && !m.coarse) return true
        if (m.coarse) return false
        // Near the end point and near the end along the route: a route that loops round the block past the destination
        // (one-way streets) is not over when it first passes close by.
        return Geo.distance(fix.location, pts.last()) <= r && m.along >= index.length - r - 30
    }

    private fun arrive(cues: MutableList<Cue>) {
        val last = index.steps.size - 1
        if (settings.earcons) cues += Cue.Earcon(EarconKind.ARRIVE, arrivalSide.pan)
        if (settings.haptics) cues += Haptics.arrive()
        val step = index.steps.getOrNull(last)
        if (step != null) {
            val toldSide = last >= 0 && announcer.spokenStages(last) and Stage.NOW.bit != 0
            val main = phrasebook.instruction(step, StepContext(arrivalSide = arrivalSide, short = toldSide || settings.verbosity == Verbosity.MINIMAL))
            val entrance = step.landmarks.firstOrNull { it.kind == "entrance" && it.side != 0 }
                ?.takeIf { profile.group == ModeGroup.WALK && settings.verbosity != Verbosity.MINIMAL }
                ?.let { phrasebook.phrase(if (it.side > 0) "entrance_right" else "entrance_left") }
            speak(phrasebook.sentences(listOfNotNull(main.text, entrance)), Priority.HIGH, false, arrivalSide.pan, "arrived", cues)
        }
        cues += Cue.Arrived
    }

    private fun callouts(along: Double, fix: Fix, spokeManeuver: Boolean, cues: MutableList<Cue>) {
        if (callouts.isEmpty()) return
        val v = max(if (speed.isNaN()) 0.0 else speed, profile.speedFloor)
        // Callouts are sorted along the route: skip those left behind for good.
        while (calloutStart < callouts.size && callouts[calloutStart].along < along - PASSED_M) calloutStart++
        val due = ArrayList<Callout>()
        val ids = ArrayList<String>()
        for (k in calloutStart until callouts.size) {
            val c = callouts[k]
            val d = c.along - along
            if (d < -5) continue
            if (d > calloutReach) break
            if (c.whenDark && Sun.position(fix.time, fix.location).altitudeDeg > CIVIL_DUSK) continue
            val firstDone = c.id in calloutsDone
            val secondId = c.id + "/2"
            if (!firstDone && d <= c.lead.at(v)) {
                due += c
                ids += c.id
                // Found already inside the close warning's range: one warning, not two in a row.
                if (c.second != null && d <= c.second.at(v)) ids += secondId
            } else if (firstDone && c.second != null && secondId !in calloutsDone && d <= c.second.at(v)) {
                due += c
                ids += secondId
            }
        }
        if (due.isEmpty()) return
        val critical = due.any { it.priority == Priority.CRITICAL }
        // A maneuver was just announced: let it be heard first, unless this cannot wait.
        if (spokeManeuver && !critical) return
        calloutsDone += ids
        val speakable = if (settings.verbosity == Verbosity.MINIMAL) due.filter { it.priority == Priority.CRITICAL } else due
        val priority = due.maxOf { it.priority }
        val pan = due.first().pan
        if (settings.earcons && priority >= Priority.HIGH) cues += Cue.Earcon(EarconKind.HAZARD, pan)
        if (settings.haptics && critical) cues += Haptics.hazard()
        if (speakable.isEmpty()) return
        val text = phrasebook.sentences(speakable.map { it.text((it.along - along).coerceAtLeast(0.0)) })
        // The close-by repeat of a warning is its own utterance: an app that drops stale speech by id must not drop it.
        val spokenIds = speakable.map { c -> if (c.id + "/2" in ids && c.id !in ids) c.id + "/2" else c.id }
        speak(text, priority, priority < Priority.CRITICAL, pan, "callout:" + spokenIds.joinToString(","), cues)
    }

    private fun speak(text: String, priority: Priority, interruptible: Boolean, pan: Float, id: String, cues: MutableList<Cue>) {
        if (settings.voice && text.isNotBlank()) cues += Cue.Speak(text, Announcer.ssml(text), priority, interruptible, pan, id)
    }

    /** Which side the destination is on: from the last step's turn, the [destination] point, or a landmark. */
    private fun arrivalSide(): Side {
        val last = index.steps.lastOrNull()
        if (last != null && last.maneuver.type == ManeuverType.ARRIVE && last.maneuver.turn != Turn.STRAIGHT) {
            return if (last.maneuver.turn.left) Side.LEFT else if (last.maneuver.turn.right) Side.RIGHT else Side.UNKNOWN
        }
        val dest = destination
        val pts = index.line.points
        if (dest != null && pts.size >= 2) {
            val snap = index.line.snap(dest, (pts.size - 40).coerceAtLeast(0), pts.size - 2)
            if (snap != null) {
                if (snap.offset < 3) return Side.AHEAD
                if (snap.along >= index.length - 0.5) {
                    val d = Geo.turnAngle(Geo.bearing(pts[pts.size - 2], pts.last()), Geo.bearing(pts.last(), dest))
                    if (abs(d) < 25) return Side.AHEAD
                    return if (d < 0) Side.LEFT else Side.RIGHT
                }
                return if (snap.side > 0) Side.RIGHT else Side.LEFT
            }
        }
        last?.landmarks?.firstOrNull { it.side != 0 }?.let { return if (it.side > 0) Side.RIGHT else Side.LEFT }
        return Side.UNKNOWN
    }

    private companion object {
        const val REROUTE_RETRY_S = 30L

        /** How far ahead any of [callouts] can be due: the longest lead at the fastest speed it allows. */
        fun reachOf(callouts: List<Callout>): Double =
            callouts.maxOfOrNull { maxOf(it.lead.maxM, it.second?.maxM ?: 0.0) } ?: 0.0
        /** A callout this far behind will not come back (the matcher's window reaches less far back). */
        const val PASSED_M = 200.0
        /** The sun this far below the horizon (civil dusk) makes an unlit path dark. */
        const val CIVIL_DUSK = -6.0
    }
}
