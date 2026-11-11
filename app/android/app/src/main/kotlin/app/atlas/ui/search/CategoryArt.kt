package app.atlas.ui.search

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.NodeKind
import app.atlas.ui.kit.cssShadow

/*
 * Every shelf of the search as a card of its own (the user, 3 November: "Categories dont have to carry their icon they
 * can be fully colored (cards) too every single category carefully and astehtically designed, that it feels like
 * youre diving into the category when you open it"): its colour as light falls on it, and a scene of its own in full
 * colour ([scene]: a plate of pasta for eating, hills by a pond for nature, a lake at sundown for water). A kind shows
 * what it is ([artOf]) in its own colour, and opening one leads into a hero of the same colour and picture: deeper into
 * the same place.
 */

/** The shelves' motifs. */
internal enum class Motif { PLATES, PRODUCE, ARCADE, NIGHT, CURRENT, HILLS, BEAMS, WAVES, COLONNADE, PITCH, PULSE, GEARS, LINES, CONTOURS }

/** What a shelf, a category or a kind shows on its card and its band. */
internal sealed interface CardArt

/** One of the painted scenes. */
internal data class Scene(val motif: Motif) : CardArt

/** Its own symbol, large ([emblem]). */
internal data class Emblem(val symbol: String) : CardArt

/**
 * Where a scene is a picture of what is listed (the owner, 9 November: "And where it fits it can still show the shelfs
 * pictures?"): a category, and those of its kinds wearing [symbols], or all of them where there are none named. Anything
 * else would wear what it is not, a plate of pasta on a pub.
 */
private class Pictured(val motif: Motif, vararg symbols: String) {
    val symbols: Set<String> = symbols.toSet()
}

private val PICTURED = mapOf(
    // a bed under the moon is everywhere to sleep, the lines every stop, a shore every beach
    "hotels" to Pictured(Motif.NIGHT),
    "transit" to Pictured(Motif.LINES),
    "beach" to Pictured(Motif.WAVES),
    "nature_reserve" to Pictured(Motif.HILLS),
    "viewpoint" to Pictured(Motif.HILLS),
    "theme_park" to Pictured(Motif.BEAMS),
    "heritage" to Pictured(Motif.COLONNADE),
    "stadium" to Pictured(Motif.PITCH),
    // of the others only some kinds: the plain restaurants and the cuisines with no symbol of their own, not the sushi
    "restaurants" to Pictured(Motif.PLATES, "maki-restaurant"),
    // the scenes follow their kinds (kinds.tsv): ours again where the kind had one (the owner, 10 November), the
    // pharmacy's capsule kept, the garage on the repair shop's spanner again (the parent's verdict, 10 November, the evening);
    // the park, the garden, the marina and the farm shop on the boards' drawings (step 1b, the same verdict)
    "food_shops" to Pictured(Motif.PRODUCE, "maki-grocery", "ui-carrot", "market.farm"),
    "shopping" to Pictured(Motif.ARCADE, "maki-shop"),
    "fuel" to Pictured(Motif.CURRENT, "maki-fuel"),
    // the botanical garden's glasshouse (kinds.tsv, 10 November) keeps the hills it had as the garden's flower
    "parks" to Pictured(Motif.HILLS, "place.park", "nature.garden", "ui-glasshouse", "temaki-garden_bed", "temaki-pedestrian"),
    "nature_spots" to Pictured(Motif.HILLS, "maki-mountain"),
    "boating" to Pictured(Motif.WAVES, "place.marina", "place.kayak", "maki-slipway", "maki-harbor"),
    "museums" to Pictured(Motif.COLONNADE, "maki-museum"),
    "sights" to Pictured(Motif.COLONNADE, "maki-attraction"),
    "worship" to Pictured(Motif.COLONNADE, "maki-place-of-worship"),
    "sports" to Pictured(Motif.PITCH, "maki-pitch", "maki-soccer"),
    "health" to Pictured(Motif.PULSE, "ui-hospital", "place.first-aid", "maki-doctor", "temaki-pharmacy"),
    "car_care" to Pictured(Motif.GEARS, "place.repair"),
    CategoryTree.OSM to Pictured(Motif.CONTOURS, "ui-map-pin"),
)

/**
 * What [node] shows (the owner, 9 November: "Drinks and nightlife has pasta", "Each kind gets their own symbol instead
 * of the generic one from the category?"): a shelf its scene, a kind that scene where it is of what it is ([PICTURED]),
 * any other its own symbol, large. So what comes to be listed, or a symbol refined in kinds.tsv, is drawn with no drawing
 * to add.
 */
internal fun artOf(node: CategoryNode): CardArt {
    if (node.kind == NodeKind.SHELF) return Scene(motifOf(node))
    val p = PICTURED[node.category.id]
    return if (p != null && (node.kind == NodeKind.CATEGORY || p.symbols.isEmpty() || node.symbol in p.symbols)) Scene(p.motif) else Emblem(node.symbol)
}

/** The scene of [shelf]. */
private fun motifOf(shelf: CategoryNode): Motif {
    return when (shelf.category.id) {
        "eat" -> Motif.PLATES
        "groceries" -> Motif.PRODUCE
        "shops" -> Motif.ARCADE
        "stay" -> Motif.NIGHT
        "charge" -> Motif.CURRENT
        "nature" -> Motif.HILLS
        "fun" -> Motif.BEAMS
        "water" -> Motif.WAVES
        "culture" -> Motif.COLONNADE
        "sport" -> Motif.PITCH
        "health" -> Motif.PULSE
        "service" -> Motif.GEARS
        "transport" -> Motif.LINES
        else -> Motif.CONTOURS
    }
}

/** A card's colour as light falls on it: lit at the top left, deeper at the bottom right. */
internal fun cardBrush(base: Color): Brush = Brush.linearGradient(
    listOf(lerp(base, Color.White, 0.08f), base, lerp(base, Color.Black, 0.3f)),
    start = Offset.Zero, end = Offset.Infinite,
)

/** Words on a card's colour, with a shadow soft enough to keep them legible over the motif. */
internal fun TextStyle.onCard(): TextStyle = copy(color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.18f), Offset(0f, 1.5f), 8f))

/** The card's surface: [base] lit from the top left, its motif in white, on a shadow of its own colour. */
internal fun Modifier.cardSurface(base: Color, art: CardArt, shape: Shape, lift: Dp = 8.dp, faded: Boolean = false): Modifier =
    this
        .cssShadow(shape, y = lift, blur = lift * 2.4f, colour = base.copy(alpha = if (faded) 0.1f else 0.32f))
        .clip(shape)
        .background(cardBrush(base))
        .drawBehind {
            litArt(art, base, if (faded) 0.5f else 1f, 0f)
            // the card's edge, catching the light along its top and in shade along its foot
            drawOutline(
                shape.createOutline(size, layoutDirection, this),
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.1f))),
                style = Stroke(1.5.dp.toPx()),
            )
        }

/**
 * A band: the card's colour, light and art as the sheet's top, out to its edges, with no shape, edge, shadow or lip
 * of its own; the places start on the paper right under it. An emblem stands [clear] of its end, where the controls are.
 */
internal fun Modifier.bandSurface(base: Color, art: CardArt, clear: Dp = 0.dp): Modifier =
    this.background(cardBrush(base)).drawBehind { litArt(art, base, 1f, clear.toPx()) }

/** What the star and the way the places are shown take at the end of a kind's band. */
internal val BAND_CONTROLS = 92.dp

/** The grabber on a band: light, on its colour. */
internal val HERO_GRABBER = Color.White.copy(alpha = 0.5f)

private fun DrawScope.litArt(art: CardArt, base: Color, strength: Float, clear: Float) {
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.12f, 0f), radius = size.width * 0.9f))
    when (art) {
        is Scene -> scene(art.motif, base, strength)
        is Emblem -> emblem(art.symbol, base, strength, clear)
    }
}

/** The search's shared space while one view gives way to another: a kind's card grows into its hero. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal class Dive(val shared: SharedTransitionScope, val view: AnimatedVisibilityScope)

internal val LocalDive = compositionLocalOf<Dive?> { null }

/** The card and the hero of the same kind ([key]) are one thing moving: from the grid into the kind and back. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.dive(key: String?): Modifier {
    val d = LocalDive.current
    if (d == null || key == null) return this
    return with(d.shared) {
        this@dive.sharedBounds(
            rememberSharedContentState(key), d.view,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

/** The key a kind's card and hero share. */
internal fun diveKey(node: CategoryNode): String = "kind:" + node.id

/** Gives under a finger, as a card pressed into its place does, and springs back when let go. */
@Composable
internal fun Modifier.pressable(interaction: MutableInteractionSource, depth: Float = 0.035f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1f - depth else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * A shelf as a card: its name at the top, what it says now ([figure]) at the bottom; [faded] when
 * none of it is near. It gives under the finger; a hold explains it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CategoryCard(
    node: CategoryNode,
    name: String,
    figure: TellFigure?,
    faded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 128.dp,
) {
    val base = Color(node.colour).let { if (faded) lerp(it, Color(0xFF8E9096), 0.72f) else it }
    val art = remember(node.id) { artOf(node) }
    val shape = RoundedCornerShape(26.dp)
    val words = listOfNotNull(name, figure?.aloud).joinToString(", ")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(height)
            .dive(diveKey(node))
            .pressable(interaction)
            .cardSurface(base, art, shape, faded = faded)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
            .clearAndSetSemantics {
                text = AnnotatedString(words)
                role = Role.Button
                onClick { onClick(); true }
                onLongClick { onLongClick(); true }
            }
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Text(name, Modifier.align(Alignment.TopStart), style = indexTitle(17f).onCard(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        figure?.let { f ->
            Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val word = @Composable {
                    if (f.word.isNotEmpty()) Text(f.word.uppercase(java.util.Locale.getDefault()), Modifier.padding(bottom = 4.dp), style = INDEX_CAPS.onCard().copy(color = Color.White.copy(alpha = 0.8f)), maxLines = 1)
                }
                if (f.before) word()
                Text(f.figure, style = indexFigure(26f).onCard(), maxLines = 1)
                if (!f.before) word()
            }
        }
    }
}

/**
 * What a card or a tile says now: a figure, read first, and its word ("44 open"), [before] it where it reads first
 * ("till 01:00"); minutes are a figure alone ("4′"), [said] in words ("4 min").
 */
internal data class TellFigure(val figure: String, val word: String, val before: Boolean = false, val said: String? = null) {
    /** As written: the figure and its word in their order. */
    val shown: String get() = (if (before) listOf(word, figure) else listOf(figure, word)).filter { it.isNotEmpty() }.joinToString(" ")

    /** As said aloud. */
    val aloud: String get() = said ?: shown
}
