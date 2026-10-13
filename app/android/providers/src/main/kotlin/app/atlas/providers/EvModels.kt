package app.atlas.providers

import app.atlas.model.EvSpec
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * One electric vehicle from the open datasets: battery, consumption, charge ports in OSM's socket names, the most
 * power it takes on AC and DC, and its DC charging curve when one was measured.
 */
data class EvModel(
    val id: String,
    /** "kw" (Open EV Data, KilowattApp) or "oed" (OpenEV Data dataset). */
    val source: String,
    val make: String,
    val model: String,
    val variant: String,
    val year: Int?,
    /** car, van, motorbike or microcar. */
    val kind: String,
    /** Usable battery in kWh. */
    val batteryKwh: Double,
    /** kWh per 100 km, mixed driving. */
    val consumption: Double,
    /** True when [consumption] was estimated from battery and rated range rather than published. */
    val consumptionEstimated: Boolean,
    val acPorts: Set<String>,
    val acPhases: Int?,
    val maxAcKw: Double?,
    val dcPorts: Set<String>,
    val maxDcKw: Double?,
    /** Battery system voltage class: 400 or 800 (48 for some microcars). */
    val voltage: Int?,
    /** DC charging curve as (state of charge 0..1, kW), ascending; empty when unknown. */
    val curve: List<Pair<Double, Double>>,
    /** True when the dataset filled [curve] with its generic shape rather than a measured one. */
    val curveIsDefault: Boolean,
    val massKg: Double? = null,
    val dragCd: Double? = null,
    val rangeKm: Double? = null,
    /** The test cycle of [rangeKm]: wltp, epa, cltc, nedc. */
    val rangeCycle: String? = null,
) {
    /** "Tesla Model 3 Long Range (2021)". */
    val title: String get() = buildString {
        append(make).append(' ').append(model)
        if (variant.isNotBlank()) append(' ').append(variant)
        year?.let { append(" (").append(it).append(')') }
    }

    /** Every socket this vehicle can plug into, AC and DC, in OSM's names. */
    val connectors: Set<String> get() = acPorts + dcPorts

    /**
     * The vehicle as the router's [EvSpec]. Consumption is the dataset's mixed figure scaled by [highwayFactor] to
     * approximate EvSpec's 100 km/h on the flat; [socNow] and [socMin] are the driver's.
     */
    fun toEvSpec(socNow: Double = 0.8, socMin: Double = 0.1, highwayFactor: Double = HIGHWAY_FACTOR): EvSpec = EvSpec(
        batteryKwh = batteryKwh,
        consumption = consumption * highwayFactor,
        connectors = connectors.ifEmpty { setOf("type2_combo", "type2") },
        maxDcKw = maxDcKw ?: 0.0,
        maxAcKw = maxAcKw ?: 0.0,
        socNow = socNow,
        socMin = socMin,
        curve = curve,
    )

    companion object {
        /** Highway driving at 100 km/h uses about this much more than the mixed figures the datasets publish. */
        const val HIGHWAY_FACTOR = 1.1
    }
}

/**
 * The open EV vehicle catalogue bundled with Atlas: 2,341 cars, vans and motorbikes merged from Open EV Data
 * (KilowattApp, MIT with attribution; consumption and 876 measured charging curves) and the OpenEV Data dataset
 * (CDLA-Permissive-2.0; newer models, weights and drag). Built by `providers/tools/build_ev_models.py`.
 */
class EvModels(val all: List<EvModel>) {
    private val byId: Map<String, EvModel> = all.associateBy { it.id }

    // Search keys per model, normalised once.
    private val keys: List<List<String>> = all.map { m -> tokens("${m.make} ${m.model} ${m.variant}") }

    fun byId(id: String): EvModel? = byId[id]

    /** Every make, alphabetically. */
    fun makes(): List<String> = all.map { it.make }.distinct().sortedBy { NameMatch.normalise(it) }

    /** The models of [make], newest first. */
    fun ofMake(make: String): List<EvModel> {
        val m = NameMatch.normalise(make)
        return all.filter { NameMatch.normalise(it.make) == m }.sortedWith(compareByDescending<EvModel> { it.year ?: 0 }.thenBy { it.title })
    }

    /**
     * Models matching [query], best first: every word must start a word of the make, model or variant ("tesla m3"
     * finds Model 3 via the digit), a four-digit word may name the year, and newer and measured-curve models rank higher.
     */
    fun search(query: String, limit: Int = 20): List<EvModel> {
        val words = tokens(query)
        if (words.isEmpty()) return emptyList()
        val year = words.firstOrNull { it.length == 4 && it.all(Char::isDigit) && it.toInt() in 1990..2100 }?.toInt()
        val terms = if (year == null) words else words.filter { it.toIntOrNull() != year }
        val scored = ArrayList<Pair<Double, EvModel>>()
        for ((i, m) in all.withIndex()) {
            if (year != null && m.year != null && m.year != year) continue
            val key = keys[i]
            var score = 0.0
            var ok = true
            for (t in terms) {
                val s = termScore(t, key)
                if (s <= 0) {
                    ok = false
                    break
                }
                score += s
            }
            if (!ok) continue
            // Words the query did not ask for push a model down: "tesla model 3" puts the plain Model 3 first.
            score -= 0.02 * (key.size - terms.size).coerceAtLeast(0)
            score += (m.year ?: 2000) / 10_000.0
            if (!m.curveIsDefault && m.curve.isNotEmpty()) score += 0.05
            if (m.source == "kw") score += 0.02
            scored.add(score to m)
        }
        return scored.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    private fun termScore(term: String, key: List<String>): Double {
        var best = 0.0
        for (w in key) {
            val s = when {
                w == term -> 1.0
                w.startsWith(term) -> 0.6 + 0.4 * term.length / w.length
                // "m3" for "model 3": a letter then digits, matching a word's initial plus the number.
                term.length >= 2 && term[0].isLetter() && term.drop(1).all(Char::isDigit) && key.contains(term.drop(1)) && w.startsWith(term[0]) -> 0.5
                else -> 0.0
            }
            if (s > best) best = s
        }
        return best
    }

    companion object {
        /** The credit line the licence requires, for the About screen. */
        const val ATTRIBUTION = "Vehicle data: Open EV Data (https://github.com/KilowattApp/open-ev-data) and the OpenEV Data dataset (CDLA-Permissive-2.0)"

        const val RESOURCE = "/app/atlas/providers/ev-models.tsv.gz"

        /** The full licence texts that must travel with the data. */
        const val LICENCES_RESOURCE = "/app/atlas/providers/ev-models-LICENCES.txt"

        private val bundled: EvModels by lazy {
            val stream = EvModels::class.java.getResourceAsStream(RESOURCE) ?: throw IllegalStateException("$RESOURCE is missing from the build")
            stream.use(::read)
        }

        /** The bundled catalogue, read once. */
        fun bundled(): EvModels = bundled

        private fun tokens(s: String): List<String> = NameMatch.normalise(s).split(' ').filter { it.isNotEmpty() }

        /** Reads the gzipped TSV the build script writes. */
        fun read(input: InputStream): EvModels {
            val lines = GZIPInputStream(input).bufferedReader(Charsets.UTF_8).readLines()
            require(lines.isNotEmpty()) { "Empty EV model file" }
            val header = lines[0].split('\t')
            val col = header.withIndex().associate { (i, h) -> h to i }
            fun need(name: String) = col[name] ?: throw IllegalStateException("EV model file lacks column $name")
            val ix = listOf(
                "id", "source", "make", "model", "variant", "year", "kind", "battery_kwh", "consumption", "consumption_estimated",
                "ac_ports", "ac_phases", "ac_kw", "dc_ports", "dc_kw", "voltage", "curve_default", "curve", "mass_kg", "cd", "range_km", "range_cycle",
            ).associateWith(::need)
            val out = ArrayList<EvModel>(lines.size)
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val f = line.split('\t')
                fun s(name: String) = f.getOrNull(ix.getValue(name)).orEmpty()
                fun d(name: String) = s(name).toDoubleOrNull()
                val battery = d("battery_kwh") ?: continue
                val consumption = d("consumption") ?: continue
                out += EvModel(
                    id = s("id"), source = s("source"), make = s("make"), model = s("model"), variant = s("variant"),
                    year = s("year").toIntOrNull(), kind = s("kind").ifEmpty { "car" }, batteryKwh = battery, consumption = consumption,
                    consumptionEstimated = s("consumption_estimated") == "1",
                    acPorts = s("ac_ports").split(',').filter { it.isNotEmpty() }.toSet(), acPhases = s("ac_phases").toIntOrNull(), maxAcKw = d("ac_kw"),
                    dcPorts = s("dc_ports").split(',').filter { it.isNotEmpty() }.toSet(), maxDcKw = d("dc_kw"), voltage = s("voltage").toIntOrNull(),
                    curve = parseCurve(s("curve")), curveIsDefault = s("curve_default") == "1",
                    massKg = d("mass_kg"), dragCd = d("cd"), rangeKm = d("range_km"), rangeCycle = s("range_cycle").ifEmpty { null },
                )
            }
            return EvModels(out)
        }

        /** "0:80.75,50:85,80:42.5" as (0.0 to 80.75, 0.5 to 85.0, 0.8 to 42.5). */
        internal fun parseCurve(s: String): List<Pair<Double, Double>> =
            if (s.isBlank()) emptyList()
            else s.split(',').mapNotNull { p ->
                val soc = p.substringBefore(':').toDoubleOrNull() ?: return@mapNotNull null
                val kw = p.substringAfter(':', "").toDoubleOrNull() ?: return@mapNotNull null
                soc / 100.0 to kw
            }.sortedBy { it.first }
    }
}
