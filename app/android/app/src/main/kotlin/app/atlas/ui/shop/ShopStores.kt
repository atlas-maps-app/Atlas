package app.atlas.ui.shop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * A small list of rows kept on the phone as a tab-separated file: read once, rewritten whole on every change (through a
 * temporary file, so a crash never leaves half a list). A file that cannot be read or written leaves the list working
 * for this session only.
 */
internal abstract class TsvStore<T>(private val file: File, private val capacity: Int) {
    private val state = MutableStateFlow(read())
    val items: StateFlow<List<T>> = state.asStateFlow()

    protected abstract fun encode(item: T): List<String>
    protected abstract fun decode(fields: List<String>): T?

    protected fun update(change: (List<T>) -> List<T>) = synchronized(this) {
        state.value = change(state.value).takeLast(capacity)
        write(state.value)
    }

    private fun read(): List<T> = try {
        if (!file.isFile) emptyList() else file.readLines().mapNotNull { line -> decode(line.split('\t').map(::unclean)) }
    } catch (e: IOException) {
        emptyList()
    }

    private fun write(rows: List<T>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(rows.joinToString("") { r -> encode(r).joinToString("\t", transform = ::clean) + "\n" })
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: IOException) {
            // Still kept for this session.
        }
    }

    private fun clean(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unclean(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }
}

/** One line of the shopping list: what is wanted, and whether it is already at home (struck through, not bought). */
internal data class ListEntry(val text: String, val atHome: Boolean = false) {
    /** What the curated table knows it as, or null. */
    val item: ShopItem? by lazy { ShopItems.classify(text) }

    /** The kinds of shop that sell it, best first; empty when nothing knows it. */
    val sellers: List<Seller> by lazy { ShopItems.sellersOf(text) }

    /** The shop kind most likely to have it, for its glyph. */
    val kind: ShopKind? get() = sellers.firstOrNull()?.kind
}

/** The shopping list: the notepad on the fridge. */
internal class ShoppingList(file: File) : TsvStore<ListEntry>(file, capacity = 200) {
    override fun encode(item: ListEntry) = listOf(item.text, if (item.atHome) "1" else "0")
    override fun decode(fields: List<String>) = fields.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { ListEntry(it, fields.getOrNull(1) == "1") }

    /** Adds [text] at the end; the same thing twice is one line. Returns false when it was blank or already there. */
    fun add(text: String): Boolean {
        val t = text.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
        if (t.isEmpty()) return false
        if (items.value.any { it.text.equals(t, ignoreCase = true) }) return false
        update { it + ListEntry(t) }
        return true
    }

    fun remove(entry: ListEntry) = update { rows -> rows.filterNot { it == entry } }

    private companion object {
        /** A line of a shopping list is a few words: a pasted page is cut. */
        const val MAX_LENGTH = 80
    }

    /** Strikes [entry] through as "at home", or brings it back. */
    fun toggleHome(entry: ListEntry) = update { rows -> rows.map { if (it == entry) it.copy(atHome = !it.atHome) else it } }

    fun clear() = update { emptyList() }

    /** What still has to be bought. */
    val toBuy: List<ListEntry> get() = items.value.filterNot { it.atHome }
}

/** A container you refill: what it is, its [kind] ("jar", "bottle", "box") and its weight empty in grams (the tare). */
internal data class Container(val name: String, val kind: String, val tareGrams: Int)

/** "Your containers · tare saved": kept so a shop can weigh the filled one and subtract. */
internal class ContainerBook(file: File) : TsvStore<Container>(file, capacity = 60) {
    override fun encode(item: Container) = listOf(item.name, item.kind, item.tareGrams.toString())
    override fun decode(fields: List<String>): Container? {
        val tare = fields.getOrNull(2)?.toIntOrNull() ?: return null
        return fields.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { Container(it, fields.getOrNull(1) ?: "jar", tare) }
    }

    fun add(c: Container) = update { it + c }
    fun remove(c: Container) = update { rows -> rows.filterNot { it == c } }
}

/** A price you noted at a shop: [what] was [price] at [shopKey] (at [lat], [lon]) on [day] (an ISO date). */
internal data class PriceNote(val what: String, val shopKey: String, val shopName: String, val price: Double, val day: String, val lat: Double, val lon: Double)

/** Prices written down by the person, next to what Open Prices has. */
internal class PriceNotes(file: File) : TsvStore<PriceNote>(file, capacity = 500) {
    override fun encode(item: PriceNote) = listOf(item.what, item.shopKey, item.shopName, item.price.toString(), item.day, item.lat.toString(), item.lon.toString())
    override fun decode(fields: List<String>): PriceNote? {
        val price = fields.getOrNull(3)?.toDoubleOrNull() ?: return null
        if (fields.size < 7) return null
        return PriceNote(fields[0], fields[1], fields[2], price, fields[4], fields[5].toDoubleOrNull() ?: return null, fields[6].toDoubleOrNull() ?: return null)
    }

    fun add(n: PriceNote) = update { rows -> rows.filterNot { it.what == n.what && it.shopKey == n.shopKey } + n }
}
