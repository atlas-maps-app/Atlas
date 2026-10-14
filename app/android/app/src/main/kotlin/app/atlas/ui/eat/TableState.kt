package app.atlas.ui.eat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime

/** The meal a time of day is for: the table's title. */
enum class Meal { BREAKFAST, LUNCH, AFTERNOON, DINNER, LATE;

    companion object {
        fun at(t: LocalTime): Meal = when (t.hour * 60 + t.minute) {
            in 300 until 630 -> BREAKFAST
            in 630 until 870 -> LUNCH
            in 870 until 1020 -> AFTERNOON
            in 1020 until 1320 -> DINNER
            else -> LATE
        }
    }
}

/** When and for how many people: [time] null means now. */
data class Table(val time: LocalTime? = null, val party: Int = 2) {
    /**
     * The moment the screens reason about, by [clock]: now, or [time] today, or tomorrow when that time passed more
     * than half an hour ago (choosing 8:00 late in the evening means breakfast tomorrow).
     */
    fun at(clock: Clock): LocalDateTime {
        val now = LocalDateTime.now(clock).withSecond(0).withNano(0)
        val t = time ?: return now
        val today = now.toLocalDate().atTime(t)
        return if (today.isBefore(now.minusMinutes(30))) today.plusDays(1) else today
    }

    companion object {
        const val MAX_PARTY = 20
    }
}

/** The table's time and party, shared by every eat & drink screen of the app. */
class TableState(private val clock: Clock) {
    private val state = MutableStateFlow(Table())
    val table: StateFlow<Table> = state.asStateFlow()

    /** The moment the screens reason about now. */
    fun at(): LocalDateTime = state.value.at(clock)

    /** Moves the time by [minutes] (from now when no time is set), on the quarter hour. */
    fun shift(minutes: Int) = state.update { t ->
        val from = t.time ?: LocalTime.now(clock)
        val moved = from.plusMinutes(minutes.toLong())
        val q = (moved.minute / 15) * 15
        t.copy(time = moved.withMinute(q).withSecond(0).withNano(0))
    }

    fun setTime(time: LocalTime?) = state.update { it.copy(time = time) }

    fun setParty(n: Int) = state.update { it.copy(party = n.coerceIn(1, Table.MAX_PARTY)) }
}
