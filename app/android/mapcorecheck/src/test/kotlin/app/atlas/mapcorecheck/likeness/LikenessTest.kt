package app.atlas.mapcorecheck.likeness

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * D16's L3 run (K4): every case of the tier drawn by the candidate (the core's host build on lavapipe; until the frame
 * core K7 registers passes it draws the clear colour), measured against the reference set `:likeness` made for the
 * current port, style and case files, and judged by thresholds.json's gates for this platform at the file's stage.
 *
 * The gates fail the run from stage A1 on a gating platform (`lavapipe-x86_64` on CI); at A0 (no engine yet) and on
 * guidance platforms they are reported. The log lines (`likeness <case>: … PASS|FAIL|REPORT`), the report and, after a
 * green gating run, the ratchet's proposal go to `-Datlas.likeness.report` (CI's `likeness` artifact).
 */
class LikenessTest {
    @Test
    fun `the tier's cases drawn by the candidate, measured and judged`() {
        val t0 = System.nanoTime()
        val set = RefSetReader.open()
        val t = Thresholds.read()
        val platform = Likeness.platform
        val stage = t.stageOf(platform)
        val gating = t.role(platform) == "gate"
        val enforced = gating && stage != "A0"
        // at A0 there is no engine and no gate: the candidate is judged by A1's, for the report only
        val judgeStage = if (stage == "A0") t.stages.first() else stage
        val judge = Judge(t, platform, judgeStage)
        val core = HostCore.load()
        val candidate: Candidate = core ?: object : Candidate {
            override val name = "no candidate"
            override fun draw(entry: RefEntry) = CandidateFrame(name, entry.w, entry.h, null, null, null, HostCore.unavailable)
        }
        val only = Likeness.only?.split(',')?.map { it.trim() }
        val entries = set.entries.values.filter { (Likeness.tier == "full" || it.tier == "smoke") && (only == null || only.any { o -> it.name.contains(o) }) }
        // structural cases first: the full-mode cases' FULL gate reads their structural twin's MAD; paths on their own
        val ordered = entries.filter { it.pathName == null }.sortedBy { when (it.mode) { "structural" -> 0; "full" -> 1; else -> 2 } }
        val results = ArrayList<CaseResult>()
        val mads = HashMap<String, Double>()
        val log = ArrayList<String>()
        for (e in ordered) {
            val f = candidate.draw(e)
            val r = Measure.case(e, f, e.structural?.let { mads[it] })
            r.frame?.let { mads[e.name] = it.mad }
            r.judged = judge.case(e.name, e.klass, e.gates, r.measured)
            results.add(r)
            log.add(line(r, enforced))
        }
        val sweeps = HashMap<String, MutableList<Double>>()
        for (r in results) for (g in r.entry.gates) if (g.startsWith("SWEEP")) sweeps.getOrPut(g) { ArrayList() }.add(r.measured["mad"])
        val aggregates = ArrayList(judge.aggregate(sweeps))
        // the scripted paths (full tier): each frame against the ceiling, the path's pops against the port's own;
        // a step at a time, so only two frames of each are held
        val paths = entries.filter { it.pathName != null }.groupBy { it.pathName!! }
        for ((name, steps) in paths) {
            val pops = Continuity.PathPops()
            var worstMad = 0.0
            val ceiling = ArrayList<Judged>()
            for (e in steps.sortedBy { it.pathStep }) {
                val ref = e.readRef()
                val f = candidate.draw(e)
                val r = Measure.case(e, f, null, ref, null, null)
                ceiling.addAll(judge.case(e.name, e.klass, e.gates, r.measured))
                r.frame?.let { worstMad = maxOf(worstMad, it.mad) }
                if (f.px != null) pops.add(f.px, ref.px)
            }
            val m = Measured(name)
            if (pops.steps >= 2) m["popsOverPort"] = pops.worst
            // the hand-over and band F's steps are measured on the level packages' own pairs (K9, K20b), not here
            m.na.addAll(listOf("handOverMad", "handOverBlock", "bandStep"))
            val j = judge.path(name, m)
            aggregates.addAll(j)
            ceiling.filter { it.outcome == "FAIL" }.take(1).forEach { aggregates.add(Judged(it.gate, it.limit, worstMad, "$name: the worst of ${steps.size} frames")) }
            log.add("likeness $name: ${steps.size} frames, pops ${pops.candPops} against the port's ${pops.portPops}, worst MAD %.3f %s".format(Locale.ROOT, worstMad,
                j.joinToString(" ") { it.toString() }))
        }
        for (a in aggregates) log.add("likeness ${a.gate}: $a")
        val failed = results.filter { it.outcome == "FAIL" }.map { it.entry.name } + aggregates.filter { it.outcome == "FAIL" }.map { it.gate + if (it.note.isEmpty()) "" else " (${it.note})" }
        // the ratchet: a green gating run proposes tighter thresholds (the coordinator commits them)
        val ratchet = ArrayList<String>()
        val report = Likeness.report
        val loose = Ratchet.loosenings(t.json, System.getProperty("atlas.likeness.previous")?.let { Likeness.json(File(it)) })
        if (gating && enforced && failed.isEmpty()) {
            val p = Ratchet.propose(t.json, t.resolve(platform), results.flatMap { it.judged } + aggregates, Likeness.commit)
            Ratchet.write(File(report, "thresholds.proposed.json"), p.json)
            ratchet.addAll(p.changes.ifEmpty { listOf("nothing to tighten") })
        } else {
            ratchet.add(if (!gating) "$platform is a guidance platform: no proposal" else if (!enforced) "stage $stage: the gates are not in force yet, no proposal" else "a gate failed: no proposal")
        }
        val notes = ArrayList<String>()
        if (core == null) notes.add("No candidate: ${HostCore.unavailable}. Every case is reported with nothing measured.")
        else notes.add("The candidate is ${core.name}; until the frame core (K7) registers passes it draws the clear colour.")
        if (!enforced) notes.add("Stage $stage on $platform (${t.role(platform)}): the gates are reported, not enforced" + (if (judgeStage != stage) "; judged by $judgeStage's gates." else "."))
        for (l in loose) notes.add("Loosened without the owner's reason: $l")
        val millis = (System.nanoTime() - t0) / 1_000_000
        val page = Report.write(File(report, "candidate"), Run(Likeness.tier, platform, judgeStage, enforced, candidate.name, set, results, aggregates, emptyList(), notes, ratchet, millis))
        File(report, "likeness.log").apply { parentFile.mkdirs(); writeText(log.joinToString("\n") + "\n") }
        for (l in log) println(l)
        println("likeness: ${results.size} cases and ${paths.size} paths on $platform at $stage (${if (enforced) "enforced" else "reported"}), ${failed.size} failing; report ${page.path}; %.1f s".format(Locale.ROOT, millis / 1000.0))
        assertTrue("thresholds loosened without the owner's reason: $loose", loose.isEmpty())
        if (enforced) assertTrue("likeness gates failed: $failed", failed.isEmpty())
    }

    /** `likeness <case>: MAD … within2 … p99 … block … flat … owners … labels … PASS|FAIL|REPORT`, as smoke.sh's lines are. */
    private fun line(r: CaseResult, enforced: Boolean): String {
        val m = r.measured
        fun f(k: String, fmt: String) = if (m[k].isNaN()) "n/a" else fmt.format(Locale.ROOT, m[k])
        val verdict = if (r.outcome == "NONE") "NONE" else if (enforced) r.outcome else "REPORT(${r.outcome})"
        return "likeness ${r.entry.name}: MAD ${f("mad", "%.3f")} within2 ${f("within2", "%.4f")} p99 ${f("p99", "%.0f")} block ${f("block", "%.2f")} flat ${f("flat", "%.6f")} " +
            "owners ${f("ownersIoU", "%.4f")} labels ${f("labelMissing", "%.0f")}/${f("labelPx", "%.2f")}px $verdict"
    }
}
