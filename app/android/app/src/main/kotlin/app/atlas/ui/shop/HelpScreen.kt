package app.atlas.ui.shop

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere

private val Ink = Color(0xFF2A2420)
private val Soft = Color(0xFF5A4E44)
private val Cream = Color(0xFFFBF6EE)
private val Sand = Color(0xFFF1E9DC)
private val Mint = Color(0xFFDDEFE7)
private val Green = Color(0xFF2F6B55)

private fun HelpKind.symbol() = when (this) {
    HelpKind.FRIDGE -> "place.fridge"
    HelpKind.PANTRY -> "place.food-bank"
    HelpKind.MEALS -> "place.soup-kitchen"
    HelpKind.WATER -> "need.water.2"
}

private fun HelpKind.label() = when (this) {
    HelpKind.FRIDGE -> R.string.shop_help_fridge
    HelpKind.PANTRY -> R.string.shop_help_pantry
    HelpKind.MEALS -> R.string.shop_help_meals
    HelpKind.WATER -> R.string.shop_help_water
}

private fun HelpKind.description() = when (this) {
    HelpKind.FRIDGE -> R.string.shop_help_fridge_d
    HelpKind.PANTRY -> R.string.shop_help_pantry_d
    HelpKind.MEALS -> R.string.shop_help_meals_d
    HelpKind.WATER -> R.string.shop_help_water_d
}

/**
 * Free food nearby, as on the Help board: a community fridge painted with flowers, four kinds (fridges, pantries, meals,
 * water) with counts, and the nearest places with their week (always open, open until, the days, the next opening). It
 * keeps nothing: no history, no recents, no saved places, and what it looked up is forgotten when you leave; the lock
 * says so. "Give or volunteer" opens the nearest place's own website; the pencil leads to the map quests, where a missing
 * place can be reported to mappers.
 */
@Composable
fun HelpScreen(nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    val context = LocalContext.current
    val area by produceState(ShopArea.empty(here), here) { repo.area("help", HelpKind.all, here, ShopArea.DEFAULT_RADIUS_M, nameless = true).collect { value = it } }
    // Nothing is kept: what this screen asked for is forgotten when it goes.
    DisposableEffect(Unit) { onDispose { repo.forget() } }
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = HelpKind.entries.firstOrNull { it.name == only }
    val view = remember(area, kind) { Helps.view(area, kind) }

    Box(Modifier.fillMaxSize().background(Cream)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 2.dp, bottom = 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundSymbolButton("ui.back", stringResource(R.string.shop_back_pantry), { nav.back() }, size = 44.dp, fill = Sand, tint = Ink, iconSize = 20.dp)
                Spacer(Modifier.weight(1f))
                val d = stringResource(R.string.shop_help_not_saved_d)
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Sand).padding(horizontal = 12.dp).semantics { contentDescription = d },
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("ui.lock.2", tint = Soft, size = 16.dp)
                    Text(stringResource(R.string.shop_help_not_saved), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), color = Soft)
                }
            }
            BoardArt(ShopBoards.FRIDGE, Modifier.padding(top = 0.dp))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.shop_help), style = TextStyle(fontFamily = SerifFamily, fontSize = 40.sp, lineHeight = 40.sp), color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                if (area.waiting || view.total == 0) stringResource(R.string.shop_help_sub_none) else stringResource(R.string.shop_help_sub, view.total),
                style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp), color = Soft,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in HelpKind.entries) {
                    val on = kind == k
                    val n = view.counts[k] ?: 0
                    Column(
                        Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(20.dp)).background(if (on) Mint else Sand)
                            .tap(stringResource(R.string.shop_refill_kind_count, stringResource(k.description()), n)) { only = if (on) null else k.name }.semantics { selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Symbol(k.symbol(), tint = if (on) Green else Ink, size = 24.dp)
                        Text("${stringResource(k.label())} ${if (area.waiting) "" else n}".trim(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), color = if (on) Green else Ink, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    area.waiting -> Text(stringResource(R.string.shop_loading), style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp), color = Soft)
                    area.places.isEmpty() && area.error != null -> StateMessage("ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C))
                    view.rows.isEmpty() -> StateMessage("place.fridge", stringResource(R.string.shop_help_none), text = stringResource(R.string.shop_help_none_text), tint = Soft)
                    else -> view.rows.forEach { r -> HelpCard(r, area, now) { services.remember(r.place.place); nav.go(Dest.Place.of(r.place.place)) } }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Cream.copy(alpha = 0f), Cream))))
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val nearest = view.rows.firstOrNull()?.place
            val site = nearest?.let { it.tags["website"] ?: it.tags["contact:website"] }?.let(::webAddress)
            BigPill(
                stringResource(R.string.shop_help_give),
                onClick = {
                    val opened = site != null && startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(site)))
                    if (!opened && nearest != null) { services.remember(nearest.place); nav.go(Dest.Place.of(nearest.place)) }
                },
                modifier = Modifier.weight(1f), fill = Ink, tint = Cream,
                icon = { Symbol("ui.heart.3", tint = Cream, size = 20.dp) },
            )
            RoundSymbolButton("ui.edit", stringResource(R.string.shop_help_osm), { nav.go(Dest.Quests(here.lat, here.lon)) }, size = 56.dp, fill = Sand, tint = Ink, iconSize = 20.dp)
        }
    }
}

@Composable
private fun HelpCard(r: HelpRow, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val p = r.place
    Row(
        Modifier.fillMaxWidth().height(84.dp)
            .cssShadow(RoundedCornerShape(22.dp), y = 2.dp, blur = 10.dp, colour = Color(0x12503C28))
            .clip(RoundedCornerShape(22.dp)).background(Color.White).tap(p.name, onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(if (r.kind == HelpKind.FRIDGE) Mint else Sand, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Symbol(r.kind.symbol(), tint = if (r.kind == HelpKind.FRIDGE) Green else Ink, size = 24.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.name, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val st = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val status = when {
                    p.alwaysOpen || (!p.hasHours && r.kind == HelpKind.WATER) -> stringResource(R.string.shop_help_always) to true
                    else -> when (p.openness(now, area.hours)) {
                        Open.OPEN, Open.CLOSING_SOON -> p.closesAt(now, area.hours)?.let { stringResource(R.string.shop_help_open_until, it.hm()) to true } ?: (stringResource(R.string.kit_open) to true)
                        Open.CLOSED -> Helps.next(p, area, now)?.let { stringResource(R.string.shop_help_next, it) to false }
                        Open.UNKNOWN -> null
                    }
                }
                status?.let { (t, open) -> Text(t, style = st.copy(fontWeight = if (open) FontWeight.Bold else FontWeight.Normal), color = if (open) Green else Soft, maxLines = 1) }
                val days = Helps.days(p, area)
                if (days.isNotEmpty()) Text(days.joinToString(" · "), Modifier.weight(1f, fill = false), style = st, color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else if (Helps.daily(p, area)) Text(stringResource(R.string.shop_help_daily), style = st, color = Soft, maxLines = 1)
                if (p.stepFree && days.isEmpty()) Text(stringResource(R.string.shop_help_stepfree), style = st, color = Soft, maxLines = 1)
            }
        }
        Text("${p.walkMinutes}′", style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, fontFeatureSettings = "tnum"), color = Ink)
    }
}
