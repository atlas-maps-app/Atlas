package app.atlas.mapcorecheck.likeness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

/** A planted failure judged: what the stub drew with it, and whether the verdict is the one it must be. */
class PlantResult(val plant: PlantEntry, val result: CaseResult) {
    val ok: Boolean get() = result.outcome == plant.expect

    /** The gates that caught it. */
    val caughtBy: List<String> get() = result.judged.filter { it.outcome == "FAIL" }.map { "${it.gate}.${it.limit?.metric}" }
}

/** One run of the harness, as the report shows it. */
class Run(
    val tier: String,
    val platform: String,
    val stage: String,
    /** Whether the run's L3 gates fail the build (from A1, on a gating platform). */
    val enforced: Boolean,
    val candidate: String,
    val set: RefSetReader,
    val cases: List<CaseResult>,
    val aggregates: List<Judged>,
    val planted: List<PlantResult>,
    val notes: List<String>,
    val ratchet: List<String>,
    val millis: Long,
)

/**
 * The likeness report (D16): a page per run with each case's pair (reference and candidate) and heat map, its gates,
 * its 20 worst owners with their OSM ids, its labels, the planted failures and what caught them, D-M1's ladder
 * re-measured with Skia and where the candidate stands on it, and the ratchet's proposal. Well-formed XHTML with its
 * pictures beside it: an artifact of CI's run.
 */
object Report {
    /** Writes the report of [run] into [dir] (index.html and its pictures); returns the page. */
    fun write(dir: File, run: Run): File {
        dir.mkdirs()
        val img = File(dir, "img").also { it.mkdirs() }
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\"><head><meta charset=\"utf-8\"/>")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><title>Likeness report</title><style>").append(CSS).append("</style></head><body>")
        sb.append("<h1>Likeness, ${esc(run.tier)} tier</h1>")
        sb.append("<p class=\"meta\">${esc(run.candidate)} on <b>${esc(run.platform)}</b> at stage <b>${esc(run.stage)}</b> (")
        sb.append(if (run.enforced) "gates enforced" else "gates reported, not enforced").append("); references ${esc(run.set.key)}; ")
        sb.append("${run.cases.size} cases, ${run.planted.size} plants, %.1f s".format(Locale.ROOT, run.millis / 1000.0))
        Likeness.commit?.let { sb.append("; commit ${esc(it)}") }
        sb.append("</p>")
        for (n in run.notes) sb.append("<p class=\"note\">${esc(n)}</p>")
        // the summary
        sb.append("<h2 id=\"summary\">Cases</h2><table><tr><th>case</th><th>verdict</th><th>MAD</th><th>within 2</th><th>p99</th><th>block</th><th>flat</th><th>owners</th><th>labels</th><th>ms</th></tr>")
        for (c in run.cases) {
            val m = c.measured
            sb.append("<tr><td><a href=\"#${slug(c.entry.name)}\">${esc(c.entry.name)}</a></td><td class=\"${c.outcome.lowercase()}\">${c.outcome}</td>")
            for (k in listOf("mad", "within2", "p99", "block", "flat", "ownersIoU")) sb.append("<td>${num(m[k], k)}</td>")
            sb.append("<td>${if (m["labelMissing"].isNaN()) "–" else "${num(m["labelMissing"], "n")} missing, ${num(m["labelPx"], "px")} px"}</td><td>${c.candidate.millis}</td></tr>")
        }
        sb.append("</table>")
        if (run.aggregates.isNotEmpty()) {
            sb.append("<h2>Sweeps and paths</h2><ul>")
            for (j in run.aggregates) sb.append("<li class=\"${j.outcome.lowercase()}\">${esc(j.toString())} (${esc(j.note)})</li>")
            sb.append("</ul>")
        }
        if (run.planted.isNotEmpty()) {
            sb.append("<h2 id=\"planted\">Planted failures</h2><table><tr><th>plant</th><th>must</th><th>gave</th><th>caught by</th><th>what was planted</th></tr>")
            for (p in run.planted) {
                sb.append("<tr><td>${esc(p.plant.name)}</td><td>${p.plant.expect}</td><td class=\"${if (p.ok) "pass" else "fail"}\">${p.result.outcome}</td>")
                sb.append("<td>${esc(p.caughtBy.joinToString(", ").ifEmpty { "–" })}</td><td>${esc(p.plant.detail)}</td></tr>")
            }
            sb.append("</table>")
        }
        ladder(sb, run)
        if (run.ratchet.isNotEmpty()) {
            sb.append("<h2>Ratchet</h2><ul>")
            for (r in run.ratchet) sb.append("<li>${esc(r)}</li>")
            sb.append("</ul>")
        }
        // each case: the pair, the heat map, the gates, the worst owners, the labels
        // full-size heat maps of the six worst cases that differ at all
        val worst = run.cases.filter { it.frame != null && it.frame.mad > 0 }.sortedByDescending { it.frame!!.mad }.take(6).map { it.entry.name }.toSet()
        val sections = run.cases.map { Triple(it, slug(it.entry.name), it.entry.name) } +
            run.planted.map { Triple(it.result, slug(it.plant.base + " " + it.plant.name), "${it.plant.base}, ${it.plant.name}") }
        for ((c, s, title) in sections) {
            sb.append("<section id=\"$s\"><h3>${esc(title)} <span class=\"${c.outcome.lowercase()}\">${c.outcome}</span></h3>")
            sb.append("<p class=\"meta\">${esc(c.candidate.source)}: ${esc(c.candidate.note)}</p>")
            for (n in c.measured.notes) sb.append("<p class=\"note\">${esc(n)}</p>")
            val e = c.entry
            val k = maxOf(1, e.w / 270)
            val ref = e.ref
            writePng(File(img, "$s-ref.png"), Metrics.shrink(ref.px, ref.w, ref.h, k), ref.w / k, ref.h / k)
            sb.append("<div class=\"pair\"><figure><img src=\"img/$s-ref.png\" alt=\"reference\"/><figcaption>reference</figcaption></figure>")
            val px = c.candidate.px
            if (px != null) {
                writePng(File(img, "$s-cand.png"), Metrics.shrink(px, ref.w, ref.h, k), ref.w / k, ref.h / k)
                val heat = Metrics.heat(px, ref.px)
                writePng(File(img, "$s-heat.png"), Metrics.shrink(heat, ref.w, ref.h, k), ref.w / k, ref.h / k)
                sb.append("<figure><img src=\"img/$s-cand.png\" alt=\"candidate\"/><figcaption>candidate</figcaption></figure>")
                if (c.entry.name in worst && c.candidate.source == run.candidate) {
                    writePng(File(img, "$s-heat-full.png"), heat, ref.w, ref.h)
                    sb.append("<figure><a href=\"img/$s-heat-full.png\"><img src=\"img/$s-heat.png\" alt=\"heat map\"/></a><figcaption>heat map (full size linked)</figcaption></figure>")
                } else {
                    sb.append("<figure><img src=\"img/$s-heat.png\" alt=\"heat map\"/><figcaption>heat map</figcaption></figure>")
                }
            }
            sb.append("</div>")
            if (c.judged.isNotEmpty()) {
                sb.append("<ul class=\"gates\">")
                for (j in c.judged) sb.append("<li class=\"${j.outcome.lowercase()}\">${esc(j.toString())}</li>")
                sb.append("</ul>")
            }
            c.owners?.let { o ->
                sb.append("<details><summary>owners: ${o.results.size} measured; buildings and parts at IoU ≥ 0.85: ${num(o.builtShare, "ownersIoU")}; by class ")
                sb.append(esc(o.shareByClass.entries.joinToString { (kk, v) -> "$kk ${num(v, "ownersIoU")}" })).append("</summary>")
                sb.append("<table><tr><th>owner</th><th>class</th><th>kind</th><th>ref px</th><th>cand px</th><th>IoU</th><th>centroid px</th><th>colour off</th></tr>")
                for (r in o.worst) {
                    sb.append("<tr><td><a href=\"${osm(r.owner)}\">${esc(r.owner.tag)}</a></td><td>${esc(r.owner.klass)}</td><td>${esc(r.owner.kind)}</td><td>${r.refPixels}</td><td>${r.candPixels}</td>")
                    sb.append("<td>${num(r.iou, "iou")}</td><td>${num(r.centroidPx, "px")}</td><td>${num(r.colourDiff, "px")}</td></tr>")
                }
                sb.append("</table></details>")
            }
            c.labels?.let { l ->
                sb.append("<details><summary>labels: ${l.matches.count { it.cand != null }} matched, ${l.missing.size} missing, ${l.extra.size} extra</summary><table>")
                sb.append("<tr><th>text</th><th>layer</th><th>centre px</th><th>angle °</th><th>scale</th></tr>")
                for (m in l.matches) sb.append("<tr><td>${esc(m.ref.text)}</td><td>${esc(m.ref.layer)}</td><td>${if (m.cand == null) "missing" else num(m.centrePx, "px")}</td><td>${num(m.angleDeg, "px")}</td><td>${num(m.scale, "px")}</td></tr>")
                for (x in l.extra) sb.append("<tr><td>${esc(x.text)}</td><td>${esc(x.layer)}</td><td>extra</td><td></td><td></td></tr>")
                sb.append("</table></details>")
            }
            sb.append("</section>")
        }
        sb.append("</body></html>\n")
        val page = File(dir, "index.html")
        page.writeText(sb.toString())
        return page
    }

    /** D-M1's ladder, re-measured with Skia by `:likeness`, and the candidate's MAD placed on it. */
    private fun ladder(sb: StringBuilder, run: Run) {
        val l = run.set.ladder
        if (l.isEmpty()) return
        sb.append("<h2 id=\"ladder\">The feature ladder</h2><p class=\"meta\">Each step adds features back to the frame; its MAD is the weight of what is still missing, the list builders work down (delivery D-M1, re-measured with Skia; D-M1 with Java2D).</p>")
        for ((case, v) in l) {
            val o = v.jsonObject
            val steps = o["steps"]!!.jsonArray.map { it.jsonObject }
            val dm = o["dm1"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray?.map { it.jsonObject }
            val cand = run.cases.firstOrNull { it.entry.name == case }?.frame?.mad
            sb.append("<h3>${esc(case)}</h3><table><tr><th>step</th><th>the frame holds</th><th>MAD</th><th>over 2</th><th>D-M1 MAD</th><th>D-M1 over 2</th></tr>")
            var placed = false
            for ((i, s) in steps.withIndex()) {
                val mad = s.dbl("mad")
                val here = cand != null && !placed && cand >= mad
                if (here) placed = true
                sb.append("<tr${if (here) " class=\"here\"" else ""}><td>${s.str("step")}</td><td>${esc(s.str("holds"))}</td><td>${num(mad, "mad")}</td><td>${num(s.dbl("over2") * 100, "pct")} %</td>")
                val d = dm?.getOrNull(i)
                sb.append("<td>${d?.let { num(it.dbl("mad"), "mad") } ?: ""}</td><td>${d?.let { num(it.dbl("over2") * 100, "pct") + " %" } ?: ""}</td></tr>")
            }
            sb.append("</table>")
            if (cand != null) sb.append("<p class=\"meta\">The candidate's MAD here is ${num(cand, "mad")}: it holds about as much as the first highlighted step.</p>")
        }
    }

    private fun osm(o: Owner): String = "https://www.openstreetmap.org/" + when (o.type) { "n" -> "node"; "r" -> "relation"; else -> "way" } + "/" + o.id

    private fun num(v: Double, kind: String): String = when {
        v.isNaN() -> "–"
        kind == "within2" || kind == "ownersIoU" || kind == "iou" -> "%.2f %%".format(Locale.ROOT, v * 100)
        kind == "flat" -> "%.4f %%".format(Locale.ROOT, v * 100)
        kind == "p99" || kind == "n" -> "%.0f".format(Locale.ROOT, v)
        else -> "%.2f".format(Locale.ROOT, v)
    }

    fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun writePng(f: File, px: IntArray, w: Int, h: Int) {
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        bi.setRGB(0, 0, w, h, px, 0, w)
        ImageIO.write(bi, "png", f)
    }

    private const val CSS = "body{font:14px/1.45 system-ui,sans-serif;margin:16px;color:#1d1d1f;background:#fafaf8}h1{font-size:22px}h2{font-size:18px;margin-top:28px}" +
        "h3{font-size:15px}table{border-collapse:collapse;margin:8px 0}td,th{border:1px solid #ddd;padding:3px 7px;text-align:left;font-variant-numeric:tabular-nums}" +
        "th{background:#f0efe9}.pass{color:#0a7a2f}.fail{color:#b3261e;font-weight:600}.none{color:#888}.here{background:#fff3c4}.meta{color:#555}" +
        ".note{color:#8a5a00}.pair{display:flex;gap:8px;flex-wrap:wrap}figure{margin:0}figure img{width:270px;border:1px solid #ccc}" +
        "figcaption{font-size:12px;color:#555}section{border-top:1px solid #ddd;margin-top:18px}ul.gates{columns:2;font-size:13px}" +
        "@media (prefers-color-scheme: dark){body{background:#161616;color:#e8e8e6}th{background:#262624}td,th{border-color:#3a3a38}.meta{color:#aaa}.here{background:#4a3f10}}"
}

class ReportTest {
    /** K4's acceptance: the report renders (well-formed, every case and plant with its pictures, the ladder, the worst owners). */
    @Test
    fun `the report renders`() {
        val set = RefSetReader.open()
        val t = Thresholds.read()
        val judge = Judge(t, "stub")
        val names = listOf("home sunny S1", "board sunny S1").filter { it in set.entries }
        val cases = names.map { n ->
            val e = set.entries.getValue(n)
            Measure.case(e, Stub().draw(e)).also { it.judged = judge.case(n, e.klass, e.gates, it.measured) }
        }
        val plants = set.planted.values.filter { it.name in setOf("planted paint", "planted drops") }.map { p ->
            val e = set.entries.getValue(p.base)
            PlantResult(p, Measure.case(e, Stub(p).draw(e)).also { it.judged = judge.case(e.name, e.klass, e.gates, it.measured) })
        }
        val dir = File(Likeness.report, "report-test")
        dir.deleteRecursively()
        val page = Report.write(dir, Run("smoke", "stub", "A4", false, "stub", set, cases, emptyList(), plants, listOf("a report test"), listOf("no proposal"), 0))
        // well-formed: an XML parser reads it whole
        val doc = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }.newDocumentBuilder().parse(page)
        val sections = doc.getElementsByTagName("section")
        assertEquals(cases.size + plants.size, sections.length)
        val imgs = doc.getElementsByTagName("img")
        assertTrue(imgs.length >= 3 * (cases.size + plants.size))
        for (i in 0 until imgs.length) {
            val src = imgs.item(i).attributes.getNamedItem("src").nodeValue
            assertTrue("$src is there", File(dir, src).isFile)
        }
        val text = page.readText()
        assertTrue("the ladder", text.contains("id=\"ladder\""))
        assertTrue("OSM links of the worst owners", text.contains("https://www.openstreetmap.org/way/"))
        println("report: ${page.path} (${page.length() / 1024} KB, ${imgs.length} pictures)")
    }
}
