package app.atlas.ui.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
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
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere


/** The symbol on a kind's tile, as the board's. */
private fun HelpKind.symbol() = when (this) {
    HelpKind.FRIDGE -> "place.fridge"
    HelpKind.PANTRY -> "place.food-bank"
    HelpKind.MEALS -> "food.hot"
    HelpKind.WATER -> "need.water.2"
}

/** The symbol on a place's card, as the board's. */
private fun HelpKind.cardSymbol() = when (this) {
    HelpKind.FRIDGE -> "place.fridge"
    HelpKind.PANTRY -> "place.food-bank.2"
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

    Box(Modifier.fillMaxSize().background(argb(CREAM))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop(), bottom = 110.dp)) {
            Box(Modifier.fillMaxWidth().height(410.dp)) {
                Box(
                    Modifier.at(16f, 42f, 44f, 44f).boardBox(SAND, round(22f)).tap(stringResource(R.string.shop_back_pantry)) { nav.back() },
                    contentAlignment = Alignment.Center,
                ) { Sym("ui.back", 20f, 20f, INK_H) }
                val d = stringResource(R.string.shop_help_not_saved_d)
                Row(
                    Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 42.dp).height(44.dp).boardBox(SAND, round(22f)).padding(horizontal = 12.dp).semantics { contentDescription = d },
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym("ui.lock.2", 16f, 16f, SOFT)
                    Line(stringResource(R.string.shop_help_not_saved), bt(12f, 600, 15.5f), SOFT)
                }
                BoardArt(ShopBoards.FRIDGE, Modifier.offset(116.dp, 96.dp))
                Column(Modifier.offset(0.dp, 338.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Block(stringResource(R.string.shop_help), bt(40f, 400, 40f, SERIF), INK_H, Modifier.semantics { heading() })
                    Line(
                        if (area.waiting || view.total == 0) stringResource(R.string.shop_help_sub_none) else stringResource(R.string.shop_help_sub, view.total),
                        bt(14f, 400, 18f), SOFT,
                    )
                }
            }
            // The four kinds: a grid of 84 high tiles, 8 apart; mint when picked.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in HelpKind.entries) {
                    val on = kind == k
                    val n = view.counts[k] ?: 0
                    val fg = if (on) GREEN else INK_H
                    Column(
                        Modifier.weight(1f).height(84.dp).boardBox(if (on) MINT else SAND, round(20f))
                            .tap(stringResource(R.string.shop_refill_kind_count, stringResource(k.description()), n)) { only = if (on) null else k.name }.semantics { selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Sym(k.symbol(), 24f, 24f, fg)
                        Line("${stringResource(k.label())} ${if (area.waiting) "" else n}".trim(), bt(12f, 600, 15.5f), fg)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    area.waiting -> Line(stringResource(R.string.shop_loading), bt(14f, 400, 18f), SOFT)
                    area.places.isEmpty() && area.error != null -> StateMessage("ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C))
                    view.rows.isEmpty() -> StateMessage("place.fridge", stringResource(R.string.shop_help_none), text = stringResource(R.string.shop_help_none_text), tint = argb(SOFT))
                    else -> view.rows.forEach { r -> HelpCard(r, area, now) { services.remember(r.place.place); nav.go(Dest.Place.of(r.place.place)) } }
                }
            }
        }
        // The board's bottom row, 28 px above the bottom: "Give or volunteer" and the pencil, 8 apart.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp + boardLift()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val nearest = view.rows.firstOrNull()?.place
            val site = nearest?.let { it.tags["website"] ?: it.tags["contact:website"] }?.let(::webAddress)
            Row(
                Modifier.weight(1f).height(56.dp).boardBox(INK_H, round(28f)).clickable(role = Role.Button) {
                    val opened = site != null && startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(site)))
                    if (!opened && nearest != null) { services.remember(nearest.place); nav.go(Dest.Place.of(nearest.place)) }
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("ui.heart.3", 20f, 20f, CREAM)
                Line(stringResource(R.string.shop_help_give), bt(15f, 600, 19.5f), CREAM)
            }
            Box(
                Modifier.size(56.dp).boardBox(SAND, round(28f)).tap(stringResource(R.string.shop_help_osm)) { nav.go(Dest.Quests(here.lat, here.lon)) },
                contentAlignment = Alignment.Center,
            ) { Sym("ui.edit", 20f, 20f, INK_H) }
        }
    }
}

private const val INK_H: Long = 0xFF2A2420
private const val SOFT: Long = 0xFF5A4E44
private const val CREAM: Long = 0xFFFBF6EE
private const val SAND: Long = 0xFFF1E9DC
private const val MINT: Long = 0xFFDDEFE7
private const val GREEN: Long = 0xFF2F6B55

/** A place: 84 high, 22 px corners, a 48 px tile with its symbol, the name, its week, and the walk. */
@Composable
private fun HelpCard(r: HelpRow, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val p = r.place
    Row(
        Modifier.fillMaxWidth().height(84.dp)
            .boardBox(0xFFFFFFFF, round(22f), listOf(CssShadow(0f, 2f, 10f, 0f, 0x12503C28))).tap(p.name, onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val fridge = r.kind == HelpKind.FRIDGE
        Box(Modifier.size(48.dp).boardBox(if (fridge) MINT else SAND, round(16f)), contentAlignment = Alignment.Center) {
            Sym(r.kind.cardSymbol(), 24f, 24f, if (fridge) GREEN else INK_H)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Line(p.name, bt(17f, 700, 22f), INK_H)
            val st = bt(13f, 400, 17f, tnum = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val status = when {
                    p.alwaysOpen || (!p.hasHours && r.kind == HelpKind.WATER) -> stringResource(R.string.shop_help_always) to true
                    else -> when (p.openness(now, area.hours)) {
                        Open.OPEN, Open.CLOSING_SOON -> p.closesAt(now, area.hours)?.let { stringResource(R.string.shop_help_open_until, it.hm()) to true } ?: (stringResource(R.string.kit_open) to true)
                        Open.CLOSED -> Helps.next(p, area, now)?.let { stringResource(R.string.shop_help_next, it) to false }
                        Open.UNKNOWN -> null
                    }
                }
                status?.let { (t, open) -> Line(t, if (open) bt(13f, 700, 17f, tnum = true) else st, if (open) GREEN else SOFT) }
                val days = Helps.days(p, area)
                if (days.isNotEmpty()) Line(days.joinToString(" · "), st, SOFT, Modifier.weight(1f, fill = false))
                else if (Helps.daily(p, area)) Line(stringResource(R.string.shop_help_daily), st, SOFT)
                if (p.stepFree && days.isEmpty()) Line(stringResource(R.string.shop_help_stepfree), st, SOFT)
            }
        }
        Line("${p.walkMinutes}′", bt(22f, 800, 28.5f, tnum = true), INK_H)
    }
}
