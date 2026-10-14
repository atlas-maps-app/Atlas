package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.model.ManeuverType
import app.atlas.model.Step
import app.atlas.model.Turn
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/** The stages of announcing one maneuver, in order; each is spoken at most once. */
enum class Stage(val bit: Int) { PREPARE(1), APPROACH(2), NOW(4), POST(8) }

/**
 * Whether a new route that reaches a place [newAlong] metres from its start brings the traveller there about as soon as
 * the old route would have, which still had [oldAhead] metres to go: then what was told about it still holds.
 */
internal fun sameApproach(oldAhead: Double, newAlong: Double): Boolean =
    oldAhead >= -5 && newAlong <= oldAhead + max(200.0, oldAhead * 0.5)

/**
 * Decides when to speak and what. Each maneuver has up to three stages before it (prepare far ahead, approach, now)
 * whose leads are time-based at the current speed with per-mode floors and ceilings, plus the time the sentence takes
 * to say. Close maneuvers are chained ("then immediately turn right"), long stretches get a sparse "continue for 3 km",
 * the arrival side is announced, and chatty mode adds a periodic reassurance. No stage is ever repeated.
 */
class Announcer(
    private var index: RouteIndex,
    private val settings: GuideSettings,
    private val profile: ModeProfile,
    private val book: Phrasebook,
    private val landmarks: Landmarks,
) {
    private var spoken = IntArray(index.steps.size)
    private var departed = false
    private var lastStep = 0
    private var lastSpeech: Instant? = null
    private var lastReassure: Instant? = null
    private val landmarkCache = HashMap<Int, LandmarkRef?>()
    private val lengthCache = HashMap<Int, Int>()

    /** What the announcer needs from one update. */
    data class Context(
        val along: Double,
        val speed: Double,
        val time: Instant,
        val stepIndex: Int,
        val arrivalSide: Side,
        val remaining: Double,
        val remainingTime: Double,
        val eta: Instant,
    )

    /** The stages already spoken for step [i], as a bit set of [Stage.bit]s. */
    fun spokenStages(i: Int): Int = spoken.getOrElse(i) { 0 }

    /**
     * Switches to [newIndex] after a reroute; [oldAlong] is where the traveller was on the old route. A maneuver of the
     * new route at the same place as an old one, reached as soon as the old route would have reached it, keeps the
     * stages already spoken, so a reroute does not repeat the approach you just heard. A maneuver passed, or whose
     * moment to turn was already told (you went straight on, and the new route brings you round to it again), is
     * announced afresh, as is one the new route reaches only by a longer way.
     */
    fun replace(newIndex: RouteIndex, oldAlong: Double = Double.NaN) {
        val old = index
        val oldSpoken = spoken
        index = newIndex
        spoken = IntArray(newIndex.steps.size)
        for (i in newIndex.steps.indices) {
            val s = newIndex.steps[i]
            if (s.maneuver.type == ManeuverType.DEPART) { spoken[i] = ALL; continue }
            for (j in old.steps.indices) {
                val o = old.steps[j]
                if (oldSpoken[j] and (PASSED or Stage.NOW.bit) != 0 || o.maneuver.type != s.maneuver.type || o.maneuver.turn != s.maneuver.turn) continue
                if (Geo.distance(o.location, s.location) >= 20) continue
                if (!oldAlong.isNaN() && !sameApproach(old.stepAlong[j] - oldAlong, newIndex.stepAlong[i])) continue
                spoken[i] = oldSpoken[j] and Stage.POST.bit.inv()
                break
            }
        }
        departed = true
        lastStep = 0
        landmarkCache.clear()
        lengthCache.clear()
    }

    fun update(c: Context, out: MutableList<Cue>) {
        val v = max(c.speed, profile.speedFloor)
        var departCue: Cue.Speak? = null
        if (!departed) {
            departed = true
            if (c.stepIndex > 0) {
                joinMidway(c, v, out)
            } else {
                val before = out.size
                depart(c, v, out)
                departCue = out.subList(before, out.size).filterIsInstance<Cue.Speak>().lastOrNull()
            }
        }
        if (c.stepIndex > lastStep) {
            for (k in lastStep + 1..c.stepIndex) {
                if (k < spoken.size) {
                    if (index.steps[k].maneuver.type == ManeuverType.WAYPOINT && spoken[k] and PASSED == 0) waypointReached(k, c, out)
                    spoken[k] = spoken[k] or Stage.PREPARE.bit or Stage.APPROACH.bit or Stage.NOW.bit or PASSED
                }
            }
            lastStep = c.stepIndex
            post(c, v, out)
        }
        val n = c.stepIndex + 1
        if (n < index.steps.size) {
            val dist = index.stepAlong[n] - c.along
            if (spoken[n] and SILENT_NOW != 0 && spoken[n] and BUZZED == 0 && dist <= lead(n, Stage.NOW, v)) {
                spoken[n] = spoken[n] or BUZZED
                buzz(index.steps[n], c, out)
            }
            val stage = chooseStage(n, dist, v)
            if (stage != null) {
                speakStage(n, stage, dist, v, c, out)
                if (departCue != null) mergeDeparture(departCue, out)
                return
            }
        }
        reassure(c, v, out)
    }

    /**
     * The turn whose words were already said with the one before ("then immediately…") or with its approach still gets
     * its sound and its urgent taps at the moment to turn: the ear and the skin know when, not only the voice.
     */
    private fun buzz(step: Step, c: Context, out: MutableList<Cue>) {
        val type = step.maneuver.type
        if (type == ManeuverType.ARRIVE || type == ManeuverType.WAYPOINT) return
        if (settings.earcons) out += Cue.Earcon(earcon(step), pan(step, c.arrivalSide))
        if (settings.haptics) out += Haptics.forManeuver(type, step.maneuver.turn, urgent = true)
    }

    /** The departure and a first instruction due at once become one utterance: "Head east on X. In 300 metres, turn left". */
    private fun mergeDeparture(depart: Cue.Speak, out: MutableList<Cue>) {
        val i = out.indexOfLast { it is Cue.Speak }
        val stage = out[i] as Cue.Speak
        if (stage === depart) return
        val text = book.sentences(listOf(depart.text, stage.text))
        out[i] = stage.copy(text = text, ssml = ssml(text))
        out.remove(depart)
    }

    /** The instruction for the next maneuver as it stands now, for the "repeat" button. */
    fun repeat(c: Context): Cue.Speak? {
        val v = max(c.speed, profile.speedFloor)
        val n = c.stepIndex + 1
        if (n >= index.steps.size) return null
        val dist = index.stepAlong[n] - c.along
        val now = dist <= lead(n, Stage.NOW, v)
        val stage = if (now) Stage.NOW else Stage.APPROACH
        val text = compose(n, stage, dist, v, c).first
        return Cue.Speak(text, ssml(text), Priority.HIGH, interruptible = false, pan = pan(index.steps[n], c.arrivalSide), id = "repeat:$n")
    }

    private fun depart(c: Context, v: Double, out: MutableList<Cue>) {
        if (index.steps.isEmpty()) return
        spoken[0] = ALL
        if (settings.verbosity == Verbosity.MINIMAL || !settings.voice) return
        val first = index.steps[0]
        var instr = book.instruction(first, stepContext(c))
        val n = 1
        if (n < index.steps.size) {
            val gap = index.stepAlong[n] - c.along
            val next = index.steps[n]
            if (gap <= max(profile.chainMinM, profile.chainSeconds * v) && next.maneuver.type != ManeuverType.ARRIVE) {
                val immediate = gap <= profile.immediateM
                instr = book.linked(instr, book.instruction(next, stepContext(c)), if (gap >= 50) gap else null, immediate)
                spoken[n] = spoken[n] or Stage.PREPARE.bit or Stage.APPROACH.bit or (if (immediate) Stage.NOW.bit or SILENT_NOW else 0)
            } else if (gap >= profile.postMinM && first.maneuver.type == ManeuverType.DEPART) {
                instr = book.departFor(first, gap, stepContext(c))
            }
        }
        say(instr.text, Priority.NORMAL, true, 0f, "step:0:depart", c.time, out)
    }

    /**
     * The first fix is already past maneuvers (the app restarted during a trip, or guidance began on the way): what is
     * behind is not announced, not even a waypoint, and a long stretch ahead gets its "continue for".
     */
    private fun joinMidway(c: Context, v: Double, out: MutableList<Cue>) {
        for (k in 0..minOf(c.stepIndex, spoken.size - 1)) spoken[k] = ALL
        if (c.stepIndex < spoken.size) spoken[c.stepIndex] = ALL and Stage.POST.bit.inv()
        lastStep = c.stepIndex
        post(c, v, out)
    }

    private fun waypointReached(k: Int, c: Context, out: MutableList<Cue>) {
        if (!settings.voice) return
        val legs = index.steps.count { it.maneuver.type == ManeuverType.WAYPOINT } + 1
        val leg = index.steps.subList(0, k).count { it.maneuver.type == ManeuverType.WAYPOINT }
        val text = book.instruction(index.steps[k], StepContext(legIndex = leg, legCount = legs)).text
        if (settings.earcons) out += Cue.Earcon(EarconKind.ARRIVE)
        say(text, Priority.HIGH, false, 0f, "step:$k:reached", c.time, out)
    }

    private fun post(c: Context, v: Double, out: MutableList<Cue>) {
        val k = c.stepIndex
        if (settings.verbosity == Verbosity.MINIMAL || !settings.voice || k + 1 >= index.steps.size) return
        if (spoken[k] and Stage.POST.bit != 0) return
        spoken[k] = spoken[k] or Stage.POST.bit
        val leg = index.stepAlong[k + 1] - c.along
        val firstLead = profile.prepare?.let { lead(k + 1, Stage.PREPARE, v) } ?: lead(k + 1, Stage.APPROACH, v)
        if (leg < profile.postMinM || leg - firstLead < max(300.0, v * 30)) return
        val step = index.steps[k]
        val text = book.continueFor(leg, step.street ?: step.ref).text
        say(text, Priority.LOW, true, 0f, "step:$k:post", c.time, out)
    }

    private fun chooseStage(n: Int, dist: Double, v: Double): Stage? {
        val sp = spoken[n]
        if (sp and Stage.NOW.bit != 0) return null
        if (dist <= lead(n, Stage.NOW, v)) return Stage.NOW
        if (sp and Stage.APPROACH.bit != 0) return null
        if (dist <= lead(n, Stage.APPROACH, v)) return Stage.APPROACH
        if (sp and Stage.PREPARE.bit != 0 || profile.prepare == null || settings.verbosity == Verbosity.MINIMAL) return null
        // The destination far ahead is no news: it is announced from the approach on.
        if (index.steps[n].maneuver.type == ManeuverType.ARRIVE) return null
        if (dist <= lead(n, Stage.PREPARE, v)) {
            // Prepare only when it stands clear of the approach; otherwise it would be said twice in a breath.
            if (dist - lead(n, Stage.APPROACH, v) > max(100.0, v * 20)) return Stage.PREPARE
            spoken[n] = spoken[n] or Stage.PREPARE.bit
        }
        return null
    }

    /** The lead of [stage] for step [n]: the mode's distance plus what is covered while the sentence is said. */
    private fun lead(n: Int, stage: Stage, v: Double): Double {
        val base = when (stage) {
            Stage.PREPARE -> profile.prepare?.at(v) ?: 0.0
            Stage.APPROACH -> profile.approach.at(v)
            else -> profile.now.at(v)
        }
        val chars = lengthCache.getOrPut(n) { book.instruction(index.steps[n], StepContext(upcoming = true)).text.length } +
            if (stage == Stage.NOW) 0 else 16
        val sayS = (chars / CHARS_PER_SECOND).coerceAtMost(6.0)
        return base + v * (settings.audioLatencyS + sayS)
    }

    private fun speakStage(n: Int, stage: Stage, dist: Double, v: Double, c: Context, out: MutableList<Cue>) {
        val step = index.steps[n]
        if (stage == Stage.NOW && step.maneuver.type == ManeuverType.ARRIVE && spoken[n] and Stage.APPROACH.bit != 0 &&
            destinationKey(c.arrivalSide) == null
        ) {
            // Nothing to add to the approach until "You have arrived".
            spoken[n] = spoken[n] or Stage.NOW.bit
            return
        }
        var marks = when (stage) {
            Stage.PREPARE -> Stage.PREPARE.bit
            Stage.APPROACH -> Stage.PREPARE.bit or Stage.APPROACH.bit
            else -> Stage.PREPARE.bit or Stage.APPROACH.bit or Stage.NOW.bit
        }
        // An approach that falls just before the now stage takes its place: one sentence, with the distance.
        if (stage == Stage.APPROACH && (dist - lead(n, Stage.NOW, v)) < v * profile.speechGapS) marks = marks or Stage.NOW.bit or SILENT_NOW
        spoken[n] = spoken[n] or marks
        val (text, chained, immediate) = compose(n, stage, dist, v, c)
        if (chained != null) {
            spoken[chained] = spoken[chained] or Stage.PREPARE.bit or Stage.APPROACH.bit or (if (immediate) Stage.NOW.bit or SILENT_NOW else 0)
        }
        val arrive = step.maneuver.type == ManeuverType.ARRIVE || step.maneuver.type == ManeuverType.WAYPOINT
        val pan = pan(step, c.arrivalSide)
        if (stage != Stage.PREPARE) {
            if (settings.earcons) out += Cue.Earcon(if (arrive) EarconKind.ARRIVE else earcon(step), pan)
            if (settings.haptics && !arrive) out += Haptics.forManeuver(step.maneuver.type, step.maneuver.turn, urgent = stage == Stage.NOW)
        }
        val priority = if (stage == Stage.NOW) Priority.HIGH else Priority.NORMAL
        say(text, priority, stage != Stage.NOW, pan, "step:$n:${stage.name.lowercase()}", c.time, out)
    }

    /** The sentence for step [n] at [stage]; also which step it chained (if any) and whether "immediately". */
    private fun compose(n: Int, stage: Stage, dist: Double, v: Double, c: Context): Triple<String, Int?, Boolean> {
        val step = index.steps[n]
        val minimal = settings.verbosity == Verbosity.MINIMAL
        val arrive = step.maneuver.type == ManeuverType.ARRIVE || step.maneuver.type == ManeuverType.WAYPOINT
        val ctx = stepContext(c)
        var instr: Instr = when {
            arrive && stage == Stage.NOW -> destination(n, c)
            arrive -> book.instruction(step, ctx.copy(upcoming = true, short = minimal, legIndex = legOf(n), legCount = legCount()))
            else -> book.instruction(step, ctx.copy(short = minimal))
        }
        if (!arrive && !minimal && stage != Stage.PREPARE) instr = book.withLandmark(instr, landmarkPhrase(n))
        var chained: Int? = null
        var immediate = false
        var then: String? = null
        if (stage != Stage.PREPARE && !arrive && n + 1 < index.steps.size) {
            val gap = index.stepAlong[n + 1] - index.stepAlong[n]
            if (gap <= max(profile.chainMinM, profile.chainSeconds * v)) {
                val next = index.steps[n + 1]
                val nextArrive = next.maneuver.type == ManeuverType.ARRIVE || next.maneuver.type == ManeuverType.WAYPOINT
                chained = n + 1
                if (next.maneuver.type == ManeuverType.ARRIVE && destinationKey(c.arrivalSide) != null) {
                    // "Turn left onto North 12th. Your destination is on the left": the arrival is told once, here.
                    then = destination(n + 1, c).text
                    immediate = true
                } else {
                    immediate = gap <= profile.immediateM
                    val second = book.instruction(next, ctx.copy(upcoming = nextArrive, short = minimal, legIndex = legOf(n + 1), legCount = legCount()))
                    instr = book.linked(instr, second, if (gap >= 50) gap else null, immediate)
                }
            }
        }
        val spokenDist = (dist - v * (settings.audioLatencyS + 1.0)).coerceAtLeast(10.0)
        val main = when (stage) {
            Stage.PREPARE, Stage.APPROACH -> book.inDistance(spokenDist, instr)
            else -> if (profile.nowWord && !arrive) book.now(instr) else instr
        }
        val lanes = if (!minimal && (stage == Stage.APPROACH || stage == Stage.PREPARE)) book.lanes(step.lanes) else null
        return Triple(book.sentences(listOfNotNull(main.text, lanes, then)), chained, immediate)
    }

    /** "Your destination is on the left, after the bakery". */
    private fun destination(n: Int, c: Context): Instr {
        val key = if (index.steps[n].maneuver.type == ManeuverType.WAYPOINT) null else destinationKey(c.arrivalSide)
        if (key == null) {
            return book.instruction(index.steps[n], stepContext(c).copy(upcoming = true, legIndex = legOf(n), legCount = legCount()))
        }
        val base = book.phraseInstr(key)
        val lm = if (settings.verbosity == Verbosity.MINIMAL) null else landmarkPhrase(n)
        return if (lm != null) book.phraseInstr("dest_with_landmark", "text" to base, "landmark" to Instr(lm)) else base
    }

    /** The Atlas phrase for the destination's side, when the side is known and the language has it. */
    private fun destinationKey(side: Side): String? =
        when (side) { Side.LEFT -> "dest_left"; Side.RIGHT -> "dest_right"; Side.AHEAD -> "dest_ahead"; else -> null }?.takeIf { book.has(it) }

    private fun landmarkPhrase(n: Int): String? {
        val ref = landmarkCache.getOrPut(n) { landmarks.forStep(index, n, profile.group) } ?: return null
        return landmarks.phrase(ref, book)
    }

    private fun reassure(c: Context, v: Double, out: MutableList<Cue>) {
        if (settings.verbosity != Verbosity.CHATTY || !settings.voice) return
        val sinceSpeech = lastSpeech?.let { Duration.between(it, c.time).seconds } ?: Long.MAX_VALUE
        val sinceReassure = lastReassure?.let { Duration.between(it, c.time).seconds } ?: Long.MAX_VALUE
        if (sinceSpeech < profile.reassureSeconds / 2 || sinceReassure < profile.reassureSeconds) return
        val n = c.stepIndex + 1
        if (n < index.steps.size) {
            val dist = index.stepAlong[n] - c.along
            val firstLead = profile.prepare?.let { lead(n, Stage.PREPARE, v) } ?: lead(n, Stage.APPROACH, v)
            if (dist < firstLead + v * 30) return
        }
        if (c.remaining < 200) return
        lastReassure = c.time
        val text = book.phrase("reassure", "distance" to Instr(book.distance(c.remaining)), "time" to Instr(book.time(c.eta, settings.zone)))
        say(text, Priority.LOW, true, 0f, "reassure", c.time, out)
    }

    private fun say(text: String, priority: Priority, interruptible: Boolean, pan: Float, id: String, time: Instant, out: MutableList<Cue>) {
        if (!settings.voice || text.isBlank()) return
        out += Cue.Speak(text, ssml(text), priority, interruptible, pan, id)
        lastSpeech = time
    }

    private fun stepContext(c: Context) = StepContext(arrivalSide = c.arrivalSide, leftHandTraffic = settings.leftHandTraffic)

    private fun legOf(n: Int): Int? = if (index.steps[n].maneuver.type == ManeuverType.WAYPOINT) index.steps.subList(0, n).count { it.maneuver.type == ManeuverType.WAYPOINT } else null
    private fun legCount(): Int = index.steps.count { it.maneuver.type == ManeuverType.WAYPOINT } + 1

    companion object {
        private const val CHARS_PER_SECOND = 14.0
        private const val PASSED = 16
        /** The now stage was said with another sentence: only its earcon and taps are still to come. */
        private const val SILENT_NOW = 32
        private const val BUZZED = 64
        private const val ALL = 1 or 2 or 4 or 8 or PASSED or BUZZED

        /** The earcon for a maneuver: turns rise or fall, forks and ramps are the soft "keep" sounds. */
        fun earcon(step: Step): EarconKind {
            val t = step.maneuver.turn
            val type = step.maneuver.type
            if (type == ManeuverType.U_TURN || t == Turn.U_TURN) return EarconKind.U_TURN
            val soft = type == ManeuverType.KEEP || type == ManeuverType.RAMP_OFF || type == ManeuverType.RAMP_ON || type == ManeuverType.MERGE ||
                t == Turn.SLIGHT_LEFT || t == Turn.SLIGHT_RIGHT
            return when {
                t.left -> if (soft) EarconKind.KEEP_LEFT else EarconKind.TURN_LEFT
                t.right -> if (soft) EarconKind.KEEP_RIGHT else EarconKind.TURN_RIGHT
                else -> EarconKind.STRAIGHT
            }
        }

        /** Where a cue about [step] sounds from: the side of the turn, or of the destination. */
        fun pan(step: Step, arrivalSide: Side): Float {
            if (step.maneuver.type == ManeuverType.ARRIVE) return arrivalSide.pan
            if (step.maneuver.type == ManeuverType.WAYPOINT) return 0f
            return when (step.maneuver.turn) {
                Turn.LEFT, Turn.SHARP_LEFT -> -0.7f
                Turn.SLIGHT_LEFT -> -0.4f
                Turn.RIGHT, Turn.SHARP_RIGHT -> 0.7f
                Turn.SLIGHT_RIGHT -> 0.4f
                else -> 0f
            }
        }

        /** Minimal SSML for a sentence: the text, escaped, with a short pause at sentence breaks. */
        fun ssml(text: String): String {
            val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return "<speak>" + esc.replace(". ", ".<break time=\"300ms\"/> ") + "</speak>"
        }
    }
}
