package app.atlas.ui.eat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.time.LocalTime

/**
 * The guest check being written, shared while the app runs, and the one order the user bookmarked, kept in a small
 * text file on the phone ([file]).
 */
class OrderBook(private val file: File) {
    private val current = MutableStateFlow(OrderPad())
    val pad: StateFlow<OrderPad> = current.asStateFlow()

    private val savedState = MutableStateFlow(read())
    val saved: StateFlow<OrderPad?> = savedState.asStateFlow()

    fun set(pad: OrderPad) {
        current.value = pad
    }

    fun update(change: (OrderPad) -> OrderPad) {
        current.value = change(current.value)
    }

    /** Keeps the current order as the bookmarked one. */
    fun bookmark() {
        val p = current.value
        savedState.value = p
        try {
            file.parentFile?.mkdirs()
            file.writeText(write(p))
        } catch (e: IOException) {
            // Kept for this session only.
        }
    }

    private fun read(): OrderPad? = try {
        if (file.isFile) parse(file.readText()) else null
    } catch (e: IOException) {
        null
    }

    companion object {
        /** One `key=value` line per field; lists are comma-separated. */
        fun write(p: OrderPad): String = buildString {
            p.time?.let { append("time=").append(it).append('\n') }
            append("party=").append(p.party).append('\n')
            append("minutes=").append(p.minutes).append('\n')
            append("cuisines=").append(p.cuisines.joinToString(",")).append('\n')
            append("not=").append(p.not.joinToString(",")).append('\n')
            append("outside=").append(p.outside).append('\n')
            append("vegan=").append(p.vegan).append('\n')
            append("vegetarian=").append(p.vegetarian).append('\n')
            append("stepfree=").append(p.stepFree).append('\n')
            append("wishes=").append(p.wishes.joinToString(",") { it.name }).append('\n')
            p.maxPrice?.let { append("price=").append(it).append('\n') }
            append("card=").append(p.card).append('\n')
            p.maxWalk?.let { append("walk=").append(it).append('\n') }
            append("rank=").append(p.rank.joinToString(",") { it.name }).append('\n')
        }

        /** Reads what [write] wrote; unknown or broken fields keep their defaults. */
        fun parse(text: String): OrderPad {
            val m = text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
            fun list(k: String) = m[k]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val d = OrderPad()
            return OrderPad(
                time = m["time"]?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                party = m["party"]?.toIntOrNull()?.coerceIn(1, Table.MAX_PARTY) ?: d.party,
                minutes = m["minutes"]?.toIntOrNull()?.coerceIn(0, 600) ?: d.minutes,
                cuisines = list("cuisines"),
                not = list("not"),
                outside = m["outside"] == "true",
                vegan = m["vegan"] == "true",
                vegetarian = m["vegetarian"] == "true",
                stepFree = m["stepfree"] == "true",
                wishes = list("wishes").mapNotNull { n -> Wish.entries.firstOrNull { it.name == n } }.toSet(),
                maxPrice = m["price"]?.toIntOrNull()?.coerceIn(1, 4),
                card = m["card"] == "true",
                maxWalk = m["walk"]?.toIntOrNull()?.coerceIn(1, 60),
                rank = list("rank").mapNotNull { n -> RankKey.entries.firstOrNull { it.name == n } }.ifEmpty { d.rank },
            )
        }
    }
}
