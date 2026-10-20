package app.atlas.ui.go

import androidx.compose.foundation.background
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.Argb
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.guide.Side
import app.atlas.mapview.Theme
import app.atlas.model.ManeuverType
import app.atlas.ui.kit.AtlasTheme

/** The WalkLiving board's colours (canvas/project/WalkLiving.dc.html). */
private object WalkColours {
    const val INK: Argb = 0xFF14161B
    const val BLUE: Argb = 0xFF2E5BFF
    const val DETAIL: Argb = 0xFFB9C0CF
    const val SUB: Argb = 0xFF464C5A
    const val VOICE: Argb = 0xFFEEF2FF
    const val LIST: Argb = 0xFFF1EEE8
    const val END: Argb = 0xFFE5484D
    const val NOTE: Argb = 0xFF676D7C
}

private const val GEIST = "Geist, system-ui, sans-serif"

/**
 * Walk: the arrow and the distance over the living map, as on the WalkLiving board, each element where the board has
 * it. A dark banner with the turn in a blue tile, the metres, the instruction and what to look for (the landmark, the
 * side of the door); the map following you with the route to the door; the arrival time with what is left; voice, the
 * turns and the end; "then …".
 */
@Composable
fun WalkView(show: NavShow, actions: NavActions) {
    AtlasTheme(dark = false) {
        val v = show.view
        var turns by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            GuidanceMap(
                v, Modifier.fillMaxSize(), background = Color(0xFFEFE5DA), theme = if (show.night) Theme.NIGHT else Theme.DAY, zoom = 17.8, pitch = 48.0,
                padding = PaddingValues(top = 170.dp, bottom = 190.dp), door = true,
            )
            BoardPlane(dy = statusShift(44f)) {
                Column(Modifier.offset(12.dp, 44.dp).requiredWidth(388.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WalkBanner(show)
                    if (turns) TurnList(show)
                }
            }
            BoardPlane(bottom = true, dy = -navShift(899f)) {
                // The card's bottom edge 16 px above the board's, however tall it grows.
                Box(Modifier.offset(12.dp, 0.dp).requiredSize(388.dp, 899.dp), contentAlignment = Alignment.BottomStart) {
                    WalkCard(show, actions, turnsOpen = turns, onTurns = { turns = !turns })
                }
            }
        }
    }
}

/** The dark banner of the WalkLiving board: the turn in a 64 px blue tile, the distance, and what to do there. */
@Composable
private fun WalkBanner(show: NavShow) {
    val v = show.view
    val s = v.state
    val off = s?.offRoute == true || v.rerouting
    Row(
        Modifier
            .fillMaxWidth()
            .cssBox(bg = WalkColours.INK, r = CssCorners(26f, 26f, 26f, 26f), shadows = listOf(CssShadow(0f, 10f, 30f, 0f, 0x5914161B)))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).cssBox(bg = if (off) 0xFFFFB547 else WalkColours.BLUE, r = CssCorners(18f, 18f, 18f, 18f)), contentAlignment = Alignment.Center) {
            val shape = arrowOf(v)
            when {
                off -> Symbol("nav.turn-back", Modifier.size(36.dp), tint = Color.White, size = 36.dp, weight = 2.4f / 2.2f)
                // The board's own right turn; the other turns in the same hand.
                shape.turn == app.atlas.model.Turn.RIGHT && shape.type != ManeuverType.ARRIVE && shape.type != ManeuverType.ROUNDABOUT ->
                    Symbol("nav.turn-right.2", Modifier.size(36.dp), tint = Color.White, size = 36.dp, contentDescription = v.instruction)
                else -> TurnArrow(shape, size = 36.dp, weight = 2.4f / 2.3f, contentDescription = v.instruction)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when {
                v.arrived -> BoardText(stringResource(R.string.go_notify_arrived), boardType(GEIST, 24f, 700, lineHeight = 30f), 0xFFFFFFFF)
                s == null -> BoardText(stringResource(R.string.go_notify_starting), boardType(GEIST, 17f, 600, lineHeight = 30f), 0xFFFFFFFF)
                else -> {
                    val d = GoFormat.distance(s.distanceToManeuver, show.units)
                    BoardText(d.toString(), boardType("Geist, sans-serif", 30f, 700, lineHeight = 30f, tnum = true), 0xFFFFFFFF,
                        Modifier.semantics { contentDescription = d.toString() })
                }
            }
            val line = when {
                v.rerouting -> stringResource(R.string.go_notify_rerouting)
                s?.offRoute == true -> stringResource(R.string.go_off_route_title)
                v.arrived -> null
                else -> v.instruction
            }
            line?.let { BoardText(it, boardType(GEIST, 17f, 600, lineHeight = 22f), 0xFFFFFFFF, lines = 2) }
            detailOf(show)?.let { BoardText(it, boardType(GEIST, 13f, lineHeight = 17f), WalkColours.DETAIL, lines = 2) }
        }
    }
}

/** What to look for at the next turn: the landmark there, and on arrival the side the door is on. */
@Composable
private fun detailOf(show: NavShow): String? {
    val s = show.view.state ?: return null
    val next = s.next ?: return null
    val parts = ArrayList<String>()
    next.landmarks.firstOrNull { it.name != null }?.let { parts += stringResource(R.string.go_at_landmark, it.name!!) }
    if (next.maneuver.type == ManeuverType.ARRIVE) when (s.arrivalSide) {
        Side.LEFT -> parts += stringResource(R.string.go_door_left)
        Side.RIGHT -> parts += stringResource(R.string.go_door_right)
        else -> Unit
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/**
 * The white card at the bottom (white at 95 % over a 24 px blur, 28 px corners): the arrival and what is left, voice,
 * the turns and the end (48 px discs); "then …"; the attribution.
 */
@Composable
private fun WalkCard(show: NavShow, actions: NavActions, turnsOpen: Boolean, onTurns: () -> Unit) {
    val v = show.view
    val s = v.state
    Column(
        Modifier
            .fillMaxWidth()
            .cssBox(bg = 0xF2FFFFFF, r = CssCorners(28f, 28f, 28f, 28f), shadows = listOf(CssShadow(0f, 10f, 30f, 0f, 0x2E181C30)), blur = 24f)
            .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                BoardText(s?.let { GoFormat.clock(it.eta, show.zone) } ?: "–", boardType("Geist, sans-serif", 24f, 700, lineHeight = 28.8f, tnum = true), WalkColours.INK)
                val left = s?.let { "${GoFormat.minutes(it.remainingTime)} · ${GoFormat.distance(it.remaining, show.units)}" }
                    ?: GoFormat.distance(v.route.distance, show.units).toString()
                BoardText(left, boardType(GEIST, 13f, lineHeight = 15.6f, tnum = true), WalkColours.SUB)
            }
            CardButton("ui.volume.4", 21f, stringResource(R.string.go_status_voice), actions.mute, if (show.muted) WalkColours.LIST else WalkColours.VOICE, if (show.muted) WalkColours.SUB else WalkColours.BLUE)
            CardButton("ui.list.3", 20f, stringResource(R.string.go_all_turns), onTurns, if (turnsOpen) WalkColours.INK else WalkColours.LIST, if (turnsOpen) 0xFFFFFFFF else WalkColours.INK)
            Box(Modifier.size(48.dp).cssBox(bg = WalkColours.END, r = CssCorners(24f, 24f, 24f, 24f)).button(stringResource(R.string.go_end_trip), actions.end), contentAlignment = Alignment.Center) {
                Symbol("ui.close", Modifier.size(20.dp), tint = Color.White, size = 20.dp, weight = 2.4f / 3f)
            }
        }
        val then = v.then
        if (then != null && !v.arrived) {
            val gap = GoFormat.distance(s?.next?.distance ?: 0.0, show.units).toString()
            val text = if (then.maneuver.type == ManeuverType.ARRIVE) stringResource(R.string.go_then_door, gap)
            else stringResource(R.string.go_then_turn, gap)
            Box(Modifier.cssBox(bg = WalkColours.LIST, r = CssCorners(10f, 10f, 10f, 10f)).padding(horizontal = 9.dp, vertical = 4.dp)) {
                BoardText(text, boardType(GEIST, 12f, lineHeight = 15.5f), WalkColours.SUB)
            }
        }
        BoardText(stringResource(R.string.go_walk_attribution), boardType(GEIST, 10f, lineHeight = 13f), WalkColours.NOTE)
    }
}

@Composable
private fun CardButton(symbol: String, size: Float, description: String, onClick: () -> Unit, background: Argb, tint: Argb) {
    Box(Modifier.size(48.dp).cssBox(bg = background, r = CssCorners(24f, 24f, 24f, 24f)).button(description, onClick), contentAlignment = Alignment.Center) {
        Symbol(symbol, Modifier.size(size.dp), tint = argb(tint), size = size.dp)
    }
}

/** The turns still ahead, each with its arrow, street and distance: the board's "All turns", on the card's white. */
@Composable
private fun TurnList(show: NavShow) {
    val v = show.view
    val s = v.state
    val from = (s?.stepIndex ?: 0) + 1
    val steps = v.route.steps.drop(from)
    Column(
        Modifier.fillMaxWidth().heightIn(max = 360.dp)
            .cssBox(bg = 0xF2FFFFFF, r = CssCorners(24f, 24f, 24f, 24f), shadows = listOf(CssShadow(0f, 10f, 30f, 0f, 0x2E181C30)), clip = true)
            .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
    ) {
        var along = s?.distanceToManeuver ?: 0.0
        for ((i, step) in steps.withIndex()) {
            if (i > 0) along += steps[i - 1].distance
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).cssBox(bg = WalkColours.VOICE, r = CssCorners(11f, 11f, 11f, 11f)), contentAlignment = Alignment.Center) {
                    TurnArrow(ArrowShape(step.maneuver.type, step.maneuver.turn, step.maneuver.exit), size = 22.dp, colour = argb(WalkColours.BLUE))
                }
                BoardText(step.street ?: step.ref ?: "", boardType(GEIST, 15f, 600, lineHeight = 19.5f), WalkColours.INK, Modifier.weight(1f))
                BoardText(GoFormat.distance(along, show.units).toString(), boardType(GEIST, 13f, lineHeight = 17f, tnum = true), WalkColours.SUB, Modifier.width(64.dp))
            }
        }
    }
}

/** A round button for a light bottom bar (the Sail board's voice): sand, or ink with the symbol white when on. */
@Composable
internal fun LightButton(symbol: String, description: String, onClick: () -> Unit, on: Boolean = false) {
    Box(
        Modifier.size(44.dp).cssBox(bg = if (on) WalkColours.INK else WalkColours.LIST, r = CssCorners(22f, 22f, 22f, 22f)).button(description, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, Modifier.size(20.dp), tint = argb(if (on) 0xFFFFFFFF else WalkColours.INK), size = 20.dp)
    }
}
