package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Matches a provider's record to an OSM place by name and distance, the way Mangrove matches subjects:
 * case, punctuation and accents do not count, and "Café 't Smalle" is "cafe t smalle".
 */
object NameMatch {
    private val marks = Regex("\\p{M}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** Lower case, no accents, punctuation as spaces, single spaces. */
    fun normalise(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(marks, "").lowercase().replace('ß', 's').replace(nonWord, " ").trim()

    /** 0..1: 1 for the same normalised name, high when one name contains the other or the spelling is close. */
    fun similarity(a: String, b: String): Double {
        val x = normalise(a)
        val y = normalise(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val compactX = x.replace(" ", "")
        val compactY = y.replace(" ", "")
        if (compactX == compactY) return 0.98
        val shorter = if (compactX.length <= compactY.length) compactX else compactY
        val longer = if (shorter === compactX) compactY else compactX
        val contains = if (shorter.length >= 4 && longer.contains(shorter)) 0.85 + 0.1 * shorter.length / longer.length else 0.0
        val tx = x.split(' ').toSet()
        val ty = y.split(' ').toSet()
        val jaccard = tx.intersect(ty).size.toDouble() / tx.union(ty).size
        val edit = 1.0 - levenshtein(compactX, compactY).toDouble() / max(compactX.length, compactY.length)
        return maxOf(contains, jaccard, edit)
    }

    /** Edit distance, two rows of ints. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /**
     * The candidate that best matches [name] at [at]: similarity at least [minSimilarity] and within [maxMetres];
     * nearer and closer-named wins. Candidates without a name match on distance alone only within 15 m.
     */
    fun <T> best(name: String?, at: LatLon, candidates: List<T>, maxMetres: Double = 120.0, minSimilarity: Double = 0.72, nameOf: (T) -> String?, where: (T) -> LatLon?): T? {
        var best: T? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in candidates) {
            val p = where(c) ?: continue
            val d = Geo.distance(at, p)
            if (d > maxMetres) continue
            val cn = nameOf(c)
            val sim = if (name == null || cn == null) (if (d <= 15.0) 0.75 else 0.0) else similarity(name, cn)
            if (sim < minSimilarity) continue
            val score = sim - 0.25 * d / maxMetres
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }
}
