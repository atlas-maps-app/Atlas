package app.atlas.render.data

import app.atlas.render.py.PyJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the engine reads an extract's elements, `json.load(open(raw.json))['elements']`: from a [Source] registered for that
 * path, or else from the file itself through [PyJson], as it always has.
 *
 * A source stands in for the file without it being written: it must give the elements exactly as [PyJson.load] reads them
 * (the same order, the same boxed types: Int, Long or BigInteger for ints, Double for floats, ordered str-keyed maps). The
 * loads read one path more than once (Amsterdam.load, then Regions.load) and each treats what it gets as its own, so every
 * call must return elements that no earlier caller has changed.
 */
object RawSource {
    /** The elements of one extract. */
    fun interface Source {
        fun elements(): List<Map<String, Any?>>
    }

    private val sources = ConcurrentHashMap<String, Source>()

    private fun keyOf(path: File): String = path.absoluteFile.normalize().path

    /** Reads [path]'s elements from [source] from now on (the file need not exist). */
    fun register(path: File, source: Source) {
        sources[keyOf(path)] = source
    }

    /** Reads [path] from its file again. */
    fun unregister(path: File) {
        sources.remove(keyOf(path))
    }

    /** True when a source stands for [path]. */
    fun registered(path: File): Boolean = sources.containsKey(keyOf(path))

    /** `json.load(open(path))['elements']`, from the registered source when there is one. */
    fun elements(path: File): List<Map<String, Any?>> {
        if (sources.isEmpty()) return fromFile(path)
        val source = sources[keyOf(path)] ?: return fromFile(path)
        return source.elements()
    }

    @Suppress("UNCHECKED_CAST")
    private fun fromFile(path: File): List<Map<String, Any?>> = (PyJson.load(path) as Map<String, Any?>)["elements"] as List<Map<String, Any?>>
}
