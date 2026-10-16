package app.atlas.ui.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The waste calendar the person imported (an `.ics` file from their municipality), kept as the file on this phone. National
 * calendar services are not built in: there is no address lookup, only the file you bring.
 */
class WasteStore(private val file: File, private val zone: () -> ZoneId) {
    private val state = MutableStateFlow(read())
    val calendar: StateFlow<WasteCalendar?> = state.asStateFlow()

    /** The name the calendar was imported under (the file's name), or null. */
    var source: String?
        get() = File(file.path + ".name").takeIf { it.isFile }?.readText()?.ifBlank { null }
        private set(v) { File(file.path + ".name").let { if (v == null) it.delete() else it.writeText(v) } }

    /** Reads [text] as an iCalendar file and keeps it; false (and nothing kept) when it has no events. */
    fun import(text: String, name: String?): Boolean {
        val parsed = WasteCalendar.parse(text, zone()) ?: return false
        file.parentFile?.mkdirs()
        file.writeText(text)
        source = name
        state.value = parsed
        return true
    }

    fun clear() {
        file.delete()
        source = null
        state.value = null
    }

    /** The next collection on or after [day], or null when no calendar is kept or it has no more. */
    fun next(day: LocalDate): Pickup? = state.value?.next(day)

    private fun read(): WasteCalendar? = if (file.isFile) runCatching { WasteCalendar.parse(file.readText(), zone()) }.getOrNull() else null
}
