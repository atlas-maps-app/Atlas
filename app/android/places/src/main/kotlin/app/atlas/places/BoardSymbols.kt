package app.atlas.places

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One of the boards' own symbols: Atlas's real icon for its meaning, taken from the boards' inline SVG. [id] says what
 * the boards use it for ("ui.search", "cat.eat", "mode.walk"; "board.<n>" when it has no name yet), [n] is its number
 * on the catalogue's contact sheets and [hash] the catalogue's geometry key. A one-colour symbol takes the caller's
 * tint; [hue] (ARGB) is the colour the boards most often give it, or null when they draw it in the text colour.
 */
class BoardSymbol internal constructor(
    val id: String,
    val n: Int,
    val hash: String,
    val hue: Long?,
    private val raw: JsonObject,
) {
    /** The older ids it stands in for ("ui-search", "maki-cafe"). */
    val aliases: List<String> get() = BoardSymbols.aliasesOf(id)

    /** Its drawing: paths in their own colours and widths (a null colour takes the tint), and any texts. */
    val icon: Icon by lazy { BoardSymbols.parse(id, raw) }

    override fun toString() = id
}

/**
 * The boards' symbols, bundled as `board_symbols.json` (built by tools/board_symbols.py from the catalogue of every
 * inline SVG on the boards; drawings such as maps and charts are left out). Read on first use.
 */
object BoardSymbols {
    private class Data(
        val all: List<BoardSymbol>,
        val byId: Map<String, BoardSymbol>,
        val aliases: Map<String, String>,
        val reverse: Map<String, List<String>>,
    )

    private val data: Data by lazy { load() }

    private fun load(): Data {
        val root = Resources.jsonOrNull("board_symbols.json")?.obj ?: return Data(emptyList(), emptyMap(), emptyMap(), emptyMap())
        // board_art.json, when the classpath has it: the boards' drawings for drawing generated boards (local tests only)
        val art = Resources.jsonOrNull("board_art.json")?.obj?.get("symbols")?.arr.orEmpty()
        val all = (root["symbols"]?.arr.orEmpty() + art).mapNotNull { e ->
            val o = e.obj ?: return@mapNotNull null
            BoardSymbol(o["id"]?.str ?: return@mapNotNull null, o["n"]?.num?.toInt() ?: 0, o["h"]?.str ?: "", argb(o["k"]?.str), o)
        }
        val byId = all.associateBy { it.id }
        val aliases = root["aliases"].stringMap().filterValues { it in byId }
        val reverse = aliases.entries.groupBy({ it.value }, { it.key })
        return Data(all, byId, aliases, reverse)
    }

    /** Every board symbol, in the catalogue's order (most used first). */
    fun all(): List<BoardSymbol> = data.all

    /** Every board symbol id. */
    fun ids(): Set<String> = data.byId.keys

    /** Older id to board id: what makes "ui-search" and "maki-cafe" draw the boards' symbols. */
    fun aliases(): Map<String, String> = data.aliases

    /** The board symbol for [id], a board id or an alias, or null when the boards have none. */
    fun resolve(id: String): BoardSymbol? = data.byId[id] ?: data.aliases[id]?.let { data.byId[it] }

    fun has(id: String): Boolean = resolve(id) != null

    /** The board drawing for [id] (a board id or an alias), or null. */
    fun icon(id: String): Icon? = resolve(id)?.icon

    internal fun aliasesOf(id: String): List<String> = data.reverse[id].orEmpty()

    internal fun parse(id: String, o: JsonObject): Icon {
        val vb = ViewBox.parse(o["vb"]?.str ?: "0 0 24 24")
        val paths = o["p"]?.arr?.mapNotNull { e -> path(e.arr ?: return@mapNotNull null) } ?: emptyList()
        val texts = o["t"]?.arr?.mapNotNull { e -> text(e.obj ?: return@mapNotNull null) } ?: emptyList()
        val shadows = o["s"]?.arr?.mapNotNull { e ->
            val a = e.arr ?: return@mapNotNull null
            IconShadow(
                a.getOrNull(0)?.num?.toFloat() ?: 0f, a.getOrNull(1)?.num?.toFloat() ?: 0f,
                a.getOrNull(2)?.num?.toFloat() ?: 0f, argb(a.getOrNull(3)?.str) ?: return@mapNotNull null,
            )
        } ?: emptyList()
        val coloured = paths.any { it.colour != null || it.gradient != null } || texts.any { it.colour != null }
        return Icon(id, vb, paths, coloured, texts, shadows)
    }

    private fun path(a: JsonArray): IconPath? {
        val flags = a.getOrNull(0)?.str ?: return null
        val d = a.getOrNull(1)?.str ?: return null
        val s = a.getOrNull(2)?.obj
        val stroke = 's' in flags
        val g = s?.get("g")?.obj
        val stops = g?.get("s")?.arr?.mapNotNull { st ->
            val pair = st.arr ?: return@mapNotNull null
            val off = pair.getOrNull(0)?.num?.toFloat() ?: return@mapNotNull null
            val c = argb(pair.getOrNull(1)?.str) ?: return@mapNotNull null
            off to c
        }?.takeIf { it.size >= 2 }
        val gp = g?.get("p")?.arr?.mapNotNull { it.num?.toFloat() }?.takeIf { it.size == 4 }
        return IconPath(
            d = d,
            stroke = stroke,
            fill = 'f' in flags,
            evenOdd = 'e' in flags,
            colour = argb(s?.get("c")?.str),
            strokeWidth = if (stroke) s?.get("w")?.num?.toFloat() ?: 1f else null,
            opacity = s?.get("o")?.num?.toFloat() ?: 1f,
            cap = when (s?.get("cap")?.str) { "r" -> LineCap.ROUND; "s" -> LineCap.SQUARE; else -> LineCap.BUTT },
            join = when (s?.get("join")?.str) { "r" -> LineJoin.ROUND; "b" -> LineJoin.BEVEL; else -> LineJoin.MITER },
            gradient = if (gp != null) stops else null,
            gx1 = gp?.get(0) ?: 0f, gy1 = gp?.get(1) ?: 0f, gx2 = gp?.get(2) ?: 0f, gy2 = gp?.get(3) ?: 0f,
            dash = s?.get("dash")?.arr?.mapNotNull { it.num?.toFloat() }?.takeIf { it.size >= 2 && it.any { v -> v > 0f } },
            radial = g?.get("k")?.str == "r",
        )
    }

    private fun text(o: JsonObject): IconText? = IconText(
        text = o["s"]?.str ?: return null,
        x = o["x"]?.num?.toFloat() ?: 0f,
        y = o["y"]?.num?.toFloat() ?: 0f,
        size = o["z"]?.num?.toFloat() ?: 16f,
        weight = o["w"]?.num?.toInt() ?: 400,
        family = o["f"]?.str ?: "sans",
        anchor = when (o["a"]?.str) { "middle" -> TextAnchor.MIDDLE; "end" -> TextAnchor.END; else -> TextAnchor.START },
        colour = argb(o["c"]?.str),
        opacity = o["o"]?.num?.toFloat() ?: 1f,
        italic = o["i"] != null,
        letterSpacing = o["l"]?.num?.toFloat() ?: 0f,
        rotation = o["r"]?.num?.toFloat() ?: 0f,
    )

    /** "#AARRGGBB" to an ARGB Long. */
    private fun argb(s: String?): Long? = s?.takeIf { it.length == 9 && it[0] == '#' }?.substring(1)?.toLongOrNull(16)
}
