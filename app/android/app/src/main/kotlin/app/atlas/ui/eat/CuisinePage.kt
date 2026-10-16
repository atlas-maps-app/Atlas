package app.atlas.ui.eat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Cuisine
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import java.util.Locale

/** One line of the carte opened: every place of a cuisine, nearest first, on the same printed menu paper. */
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 18.dp, bottom = bottomRoom(32.dp))) {
            item {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 76.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(familyName(family)).uppercase(), style = EatType.smallCaps.copy(fontFamily = SerifFamily, fontSize = 12.sp), color = Color(family.colour))
                    Text(title, Modifier.semantics { heading() }, style = Atlas.type.display.copy(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold, fontSize = 40.sp), color = MENU_INK)
                    if (worked.value != null) {
                        val open = places.count { it.openness(ctx.at, ctx.area.hours).isOpen }
                        Text(
                            stringResource(R.string.eat_page_count, places.size, open, ctx.at.format(HM)).uppercase(),
                            style = EatType.smallCaps.copy(fontFamily = SerifFamily, fontSize = 12.sp),
                            color = MENU_SOFT,
                        )
                    }
                }
            }
            items(places, key = { it.key }) { p ->
                MenuPlaceRow(p, ctx, onPad = p.key in onPad, onToggle = { ctx.repo.shortlist.toggle(p) }) { ctx.open(nav, p.place) }
            }
            if (ctx.area.waiting || !worked.fresh) item { Looking(MENU_SOFT) }
            item { sourceLine(ctx.area)?.let { HonestyFooter(it) } }
        }
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 22.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymbolButton("ui-chevron-left", stringResource(R.string.kit_back), { nav.back() }, size = 48.dp, background = Color(0xFFF1E9DC), tint = MENU_INK, elevation = 0.dp)
            Spacer(Modifier.weight(1f))
            OrderPadButton(pad.size, { nav.go(Dest.Eat("order")) }, background = BURGUNDY)
        }
    }
}

/** A place on the printed menu: name, leaders, walk; the open line, other cuisines and diets; and the pad's tick. */
@Composable
internal fun MenuPlaceRow(p: EatPlace, ctx: EatContext, onPad: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val open = openWords(p, ctx)
    val closed = p.openness(ctx.at, ctx.area.hours) == Openness.CLOSED
    val ink = if (closed) MENU_SOFT else MENU_INK
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LeaderLine(MENU_SOFT.copy(alpha = 0.5f), Modifier.fillMaxWidth(), start = {
                Text(p.name, style = EatType.menu.copy(fontWeight = FontWeight.Bold, fontSize = 21.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }) {
                Text("${p.walkMinutes}′", style = EatType.menu.copy(fontSize = 18.sp), color = ink)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                open?.let { (w, c) -> Text(w, style = Atlas.type.caption, color = c) }
                ctx.ratings.stars(p.key)?.let { s ->
                    Symbol("ui-star", tint = MENU_SOFT, size = 12.dp)
                    Text(String.format(Locale.ROOT, "%.1f", s), style = Atlas.type.caption, color = MENU_SOFT)
                }
                val others = p.cuisines.drop(1).take(2).joinToString(", ") { Cuisine.name(it, ctx.lang).lowercase() }
                if (others.isNotEmpty()) Text(others, style = EatType.menuItalic.copy(fontSize = 14.sp), color = MENU_SOFT, maxLines = 1)
                if (p.diet("vegan")) Symbol("ui-vegan", tint = Color(0xFF3E8E4E), size = 14.dp, contentDescription = stringResource(R.string.eat_diet_vegan))
                else if (p.diet("vegetarian")) Symbol("ui-leaf", tint = Color(0xFF3E8E4E), size = 14.dp, contentDescription = stringResource(R.string.eat_diet_vegetarian))
            }
        }
        Spacer(Modifier.width(10.dp))
        val label = stringResource(if (onPad) R.string.eat_pad_remove else R.string.eat_pad_add)
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (onPad) BURGUNDY else Color(0xFFF1E9DC))
                .toggleable(onPad, role = Role.Checkbox) { onToggle() }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Symbol(if (onPad) "ui-check" else "ui-plus", tint = if (onPad) Color.White else MENU_INK, size = 16.dp)
        }
    }
}
