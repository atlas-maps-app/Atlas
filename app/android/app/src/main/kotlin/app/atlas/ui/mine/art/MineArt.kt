package app.atlas.ui.mine.art

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.atlas.R
import app.atlas.places.Icon
import app.atlas.places.IconPath
import app.atlas.places.IconText
import app.atlas.places.LineCap
import app.atlas.places.LineJoin
import app.atlas.places.TextAnchor
import app.atlas.places.ViewBox
import app.atlas.ui.kit.drawIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * The objects of the mine package's boards, taken from the boards' own SVG by `mine_art.py` (`res/raw/mine_art.json`):
 * the car's connector faces, the passport's globe, the privacy shield. Ids are `plug.ccs2.face`, `mine.globe`, ….
 */
object MineArt {
    private var all: Map<String, Icon>? = null

    /** The drawing [id], or null when the file has none by that name. */
    fun icon(context: Context, id: String): Icon? = load(context)[id]

    @Synchronized
    private fun load(context: Context): Map<String, Icon> = all ?: run {
        val text = context.resources.openRawResource(R.raw.mine_art).bufferedReader().use { it.readText() }
        parse(text).also { all = it }
    }

    /** Parses the file (the symbol catalogue's resource format: `{"art": [{id, vb, p: [[flags, d, style]], t}]}`). */
    fun parse(text: String): Map<String, Icon> {
        val root = Json.parseToJsonElement(text) as JsonObject
        val out = LinkedHashMap<String, Icon>()
        for (e in root["art"] as JsonArray) {
            val o = e as JsonObject
            val id = (o["id"] as JsonPrimitive).content
            val vb = ViewBox.parse((o["vb"] as JsonPrimitive).content)
            val paths = (o["p"] as JsonArray).map { path(it as JsonArray) }
            val texts = (o["t"] as? JsonArray)?.map { text(it as JsonObject) }.orEmpty()
            out[id] = Icon(id, vb, paths, texts = texts)
        }
        return out
    }

    private fun path(a: JsonArray): IconPath {
        val flags = (a[0] as JsonPrimitive).content
        val d = (a[1] as JsonPrimitive).content
        val s = a.getOrNull(2) as? JsonObject
        val stroke = 's' in flags
        val g = s?.get("g") as? JsonObject
        val stops = (g?.get("s") as? JsonArray)?.mapNotNull { st ->
            val pair = st as? JsonArray ?: return@mapNotNull null
            val off = (pair[0] as JsonPrimitive).floatOrNull ?: return@mapNotNull null
            val c = argb((pair[1] as JsonPrimitive).content) ?: return@mapNotNull null
            off to c
        }?.takeIf { it.size >= 2 }
        val gp = (g?.get("p") as? JsonArray)?.mapNotNull { (it as JsonPrimitive).floatOrNull }?.takeIf { it.size == 4 }
        fun num(key: String) = (s?.get(key) as? JsonPrimitive)?.floatOrNull
        return IconPath(
            d = d,
            stroke = stroke,
            fill = 'f' in flags,
            evenOdd = 'e' in flags,
            colour = argb((s?.get("c") as? JsonPrimitive)?.content),
            strokeWidth = if (stroke) num("w") ?: 1f else null,
            opacity = num("o") ?: 1f,
            cap = when ((s?.get("cap") as? JsonPrimitive)?.content) { "r" -> LineCap.ROUND; "s" -> LineCap.SQUARE; else -> LineCap.BUTT },
            join = when ((s?.get("join") as? JsonPrimitive)?.content) { "r" -> LineJoin.ROUND; "b" -> LineJoin.BEVEL; else -> LineJoin.MITER },
            gradient = if (gp != null) stops else null,
            gx1 = gp?.get(0) ?: 0f, gy1 = gp?.get(1) ?: 0f, gx2 = gp?.get(2) ?: 0f, gy2 = gp?.get(3) ?: 0f,
            dash = (s?.get("dash") as? JsonArray)?.mapNotNull { (it as JsonPrimitive).floatOrNull }?.takeIf { it.size >= 2 && it.any { v -> v > 0f } },
            radial = ((g?.get("k") as? JsonPrimitive)?.content) == "r",
        )
    }

    private fun text(o: JsonObject): IconText {
        fun num(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull?.toFloat()
        return IconText(
            text = (o["s"] as JsonPrimitive).content,
            x = num("x") ?: 0f, y = num("y") ?: 0f, size = num("z") ?: 16f,
            weight = num("w")?.toInt() ?: 400,
            family = (o["f"] as? JsonPrimitive)?.content ?: "sans",
            anchor = when ((o["a"] as? JsonPrimitive)?.content) { "middle" -> TextAnchor.MIDDLE; "end" -> TextAnchor.END; else -> TextAnchor.START },
            colour = argb((o["c"] as? JsonPrimitive)?.content),
        )
    }

    /** "#AARRGGBB" to an ARGB Long. */
    private fun argb(s: String?): Long? = s?.takeIf { it.length == 9 && it[0] == '#' }?.substring(1)?.toLongOrNull(16)
}

/** A board object by [id] in a [size] square (its aspect kept); paths without a colour of their own take [tint]. */
@Composable
fun MineArtImage(id: String, size: Dp, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, contentDescription: String? = null) {
    val context = LocalContext.current
    val icon = remember(id) { MineArt.icon(context, id) }
    val m = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier
    if (icon == null) return
    Canvas(m.size(size)) { drawIcon(icon, Offset.Zero, this.size.minDimension, tint) }
}
