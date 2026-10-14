package app.atlas.ui.eat

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/** A place jotted on the order pad: enough to show it and open it again, wherever the map is. */
data class Jotted(val key: String, val osmId: String?, val at: LatLon, val name: String)

/**
 * The order pad's shortlist: places picked from the carte, the bar or the ticket machine, newest first. Kept on the
 * phone in a small tab-separated file, so it survives a restart; never sent anywhere.
 */
class Shortlist(private val file: File, private val capacity: Int = 24) {
    private val state = MutableStateFlow(read())
    val items: StateFlow<List<Jotted>> = state.asStateFlow()

    operator fun contains(key: String): Boolean = state.value.any { it.key == key }

    /** Adds [place] at the top, or takes it off when it is already there. Returns true when it is now on the pad. */
    fun toggle(place: EatPlace): Boolean = synchronized(this) {
        val now = state.value
        val on = now.none { it.key == place.key }
        state.value = if (on) {
            (listOf(Jotted(place.key, place.place.id?.toString(), place.location, place.name)) + now).take(capacity)
        } else {
            now.filter { it.key != place.key }
        }
        write(state.value)
        on
    }

    fun clear() = synchronized(this) {
        state.value = emptyList()
        write(emptyList())
    }

    private fun read(): List<Jotted> = try {
        if (!file.isFile) emptyList() else file.readLines().mapNotNull(::parse)
    } catch (e: IOException) {
        emptyList()
    }

    private fun write(items: List<Jotted>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(items.joinToString("") { "${clean(it.key)}\t${it.osmId.orEmpty()}\t${it.at.lat}\t${it.at.lon}\t${clean(it.name)}\n" })
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: IOException) {
            // The pad still works for this session; it is only not kept.
        }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun parse(line: String): Jotted? {
        val f = line.split('\t')
        if (f.size < 5) return null
        val lat = f[2].toDoubleOrNull() ?: return null
        val lon = f[3].toDoubleOrNull() ?: return null
        val id = f[1].takeIf { it.isNotEmpty() && OsmId.parse(it) != null }
        return Jotted(f[0], id, LatLon(lat, lon), f[4])
    }
}
