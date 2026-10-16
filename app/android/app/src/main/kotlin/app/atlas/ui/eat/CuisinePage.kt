package app.atlas.ui.eat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Cuisine
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.Symbol
import java.util.Locale

/* The Carte board's type, which a cuisine's page keeps (the page has no board of its own). */
private val PAGE_FAMILY = cssText("Cormorant Garamond", 15f, 700, lineHeight = 18.5f, spacing = 3f, smallCaps = true)
private val PAGE_TITLE = cssText("Cormorant Garamond", 46f, 600, italic = true, colour = 0xFF2A1D14, lineHeight = 46f)
private val PAGE_COUNT = cssText("Cormorant Garamond", 15f, 600, colour = 0xFF6B5A45, lineHeight = 18.5f, spacing = 1.8f, tnum = true, smallCaps = true)
private val PLACE_NAME = cssText("Cormorant Garamond", 21f, 700, lineHeight = 25.5f)
private val PLACE_WALK = cssText("Geist", 13f, 500, lineHeight = 17f, tnum = true)
private val PLACE_META = cssText("Geist", 12f, 500, lineHeight = 15.5f, tnum = true)
private val PLACE_OTHERS = cssText("Cormorant Garamond", 16f, 500, italic = true, colour = 0xFF7A6A55, lineHeight = 19.5f)

/**
 * One line of the carte opened: every place of a cuisine, nearest first, on the same printed menu paper, under the
 * Carte board's top row, its chapter word, the cuisine as the carte's title and the count in small caps.
 */
@Composable
fun CuisinePage(cuisine: String, nav: Navigator) {
    val ctx = rememberEatContext()
    val worked = rememberWorked(ctx.area, cuisine) { CarteModel.placesOf(ctx.area, cuisine) }
    val places = worked.value.orEmpty()
    val pad by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val onPad = remember(pad) { pad.mapTo(HashSet()) { it.key } }
    val title = when (cuisine) {
        CarteModel.UNMAPPED_RESTAURANT -> stringResource(R.string.eat_unmapped_restaurant)
        CarteModel.UNMAPPED_FAST_FOOD -> stringResource(R.string.eat_unmapped_fast_food)
        else -> Cuisine.name(cuisine, ctx.lang)
    }
    val family = CarteModel.familyOf(cuisine, ctx.area.country)
    Box(Modifier.fillMaxSize().drawBehind { drawMenuPaper() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 30.dp, end = 30.dp, bottom = bottomRoom(32.dp))) {
            item { CarteTopRow(pad.size, onBack = { nav.back() }, onSurprise = null, onPad = { nav.go(Dest.Eat("order")) }) }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CssLine(stringResource(familyName(family)).lowercase(), PAGE_FAMILY, color = Color(family.colour))
                    CssLine(title, PAGE_TITLE, Modifier.semantics { heading() })
                    if (worked.value != null) {
                        val open = places.count { it.openness(ctx.at, ctx.area.hours).isOpen }
                        CssLine(stringResource(R.string.eat_page_count, places.size, open, ctx.at.format(HM)).lowercase(Locale.forLanguageTag(ctx.lang)), PAGE_COUNT)
                    }
                }
            }
            items(places, key = { it.key }) { p ->
                MenuPlaceRow(p, ctx, onPad = p.key in onPad, onToggle = { ctx.repo.shortlist.toggle(p) }) { ctx.open(nav, p.place) }
            }
            if (ctx.area.waiting || !worked.fresh) item { Looking(MENU_SOFT) }
            item { sourceLine(ctx.area)?.let { HonestyFooter(it) } }
        }
    }
}

/**
 * A place on the printed menu as the carte sets its lines: the name (21 px), the dotted leader and the walk; under it
 * the open line, its stars, other cuisines in italics and its diet; and the pad's tick (34 px).
 */
@Composable
internal fun MenuPlaceRow(p: EatPlace, ctx: EatContext, onPad: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val open = openWords(p, ctx)
    val closed = p.openness(ctx.at, ctx.area.hours) == Openness.CLOSED
    val ink = if (closed) MENU_SOFT else MENU_INK
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LeaderLine(Color(0xFFC9B89B), Modifier.fillMaxWidth(), gap = 4.dp, dot = 2.dp, height = 25.5.dp, endTop = 6.5.dp, dotsTop = 18.dp, start = {
                CssLine(p.name, PLACE_NAME, color = ink)
            }) {
                CssLine("${p.walkMinutes}′", PLACE_WALK, color = MENU_SOFT)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                open?.let { (w, c) -> CssLine(w, PLACE_META, color = c) }
                ctx.ratings.stars(p.key)?.let { s ->
                    Symbol("ui.star", tint = MENU_SOFT, size = 12.dp)
                    CssLine(String.format(Locale.ROOT, "%.1f", s), PLACE_META, color = MENU_SOFT)
                }
                val others = p.cuisines.drop(1).take(2).joinToString(", ") { Cuisine.name(it, ctx.lang).lowercase() }
                if (others.isNotEmpty()) CssLine(others, PLACE_OTHERS)
                if (p.diet("vegan")) Symbol("fact.vegan", tint = Color(0xFF3E8E4E), size = 14.dp, contentDescription = stringResource(R.string.eat_diet_vegan))
                else if (p.diet("vegetarian")) Symbol("fact.vegan", tint = Color(0xFF3E8E4E), size = 14.dp, contentDescription = stringResource(R.string.eat_diet_vegetarian))
            }
        }
        Spacer(Modifier.width(10.dp))
        val label = stringResource(if (onPad) R.string.eat_pad_remove else R.string.eat_pad_add)
        Box(
            Modifier
                .size(34.dp)
                .cssBox(bg = if (onPad) 0xFF7A2A28 else 0x147A2A28, r = round(34f))
                .clip(CircleShape)
                .toggleable(onPad, role = Role.Checkbox) { onToggle() }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Symbol(if (onPad) "ui-check" else "ui.plus", tint = if (onPad) Color.White else BURGUNDY, size = 16.dp)
        }
    }
}
