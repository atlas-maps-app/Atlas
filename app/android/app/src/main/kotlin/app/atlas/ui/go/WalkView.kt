package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.guide.Side
import app.atlas.mapview.Theme
import app.atlas.model.ManeuverType
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.softShadow

/** The WalkLiving board's colours (canvas/project/WalkLiving.dc.html). */
private object WalkColours {
    val ink = Color(0xFF14161B)
    val blue = Color(0xFF2E5BFF)
    val detail = Color(0xFFB9C0CF)
    val sub = Color(0xFF464C5A)
    val voiceBg = Color(0xFFEEF2FF)
    val listBg = Color(0xFFF1EEE8)
    val end = Color(0xFFE5484D)
    val note = Color(0xFF676D7C)
}

private val Figures = TextStyle(fontFamily = SansFamily, fontFeatureSettings = "tnum")

/**
 * Walk: the arrow and the distance over the living map, as on the WalkLiving board. A dark banner with the turn in a
 * blue tile, the metres, the instruction and what to look for (the landmark, the side of the door); the map following
 * you with the route to the door; the arrival time with what is left; voice, the turns and the end; "then …".
 */
@Composable
fun WalkView(show: NavShow, actions: NavActions) {
    AtlasTheme(dark = false) {
        val v = show.view
        val s = v.state
        var turns by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color(0xFFEFE5DA))) {
            GuidanceMap(
                v, Modifier.fillMaxSize(), theme = if (show.night) Theme.NIGHT else Theme.DAY, zoom = 17.8, pitch = 48.0,
                padding = PaddingValues(top = 170.dp, bottom = 190.dp), door = true,
            )
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 4.dp)) {
                WalkBanner(show)
                if (turns) {
                    Spacer(Modifier.height(10.dp))
                    TurnList(show, Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.weight(1f))
                WalkCard(show, actions, turnsOpen = turns, onTurns = { turns = !turns })
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** The dark banner of the WalkLiving board: the turn in a blue tile, the distance, and what to do there. */
@Composable
private fun WalkBanner(show: NavShow) {
    val v = show.view
    val s = v.state
    val off = s?.offRoute == true || v.rerouting
    Row(
        Modifier
            .fillMaxWidth()
            .softShadow(RoundedCornerShape(26.dp), Color(0x5914161B), 14.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(WalkColours.ink)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(if (off) NavColours.amber else WalkColours.blue), contentAlignment = Alignment.Center) {
            if (off) ArtIcon(BoardArt.uTurn, size = 36.dp, tint = Color.White)
            else TurnArrow(arrowOf(v), size = 36.dp, weight = 2.4f / 2.3f, contentDescription = v.instruction)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when {
                v.arrived -> Text(stringResource(R.string.go_notify_arrived), style = Figures.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = Color.White)
                s == null -> Text(stringResource(R.string.go_notify_starting), style = Figures.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
                else -> {
                    val d = GoFormat.distance(s.distanceToManeuver, show.units)
                    Text(d.toString(), style = Figures.copy(fontSize = 30.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold), color = Color.White,
                        modifier = Modifier.semantics { contentDescription = d.toString() })
                }
            }
            val line = when {
                v.rerouting -> stringResource(R.string.go_notify_rerouting)
                s?.offRoute == true -> stringResource(R.string.go_off_route_title)
                v.arrived -> null
                else -> v.instruction
            }
            line?.let { Text(it, style = Figures.copy(fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            detailOf(show)?.let { Text(it, style = Figures.copy(fontSize = 13.sp, lineHeight = 17.sp), color = WalkColours.detail, maxLines = 2) }
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

/** The white card at the bottom: the arrival, what is left, voice, the turns, the end; then "then …". */
@Composable
private fun WalkCard(show: NavShow, actions: NavActions, turnsOpen: Boolean, onTurns: () -> Unit) {
    val v = show.view
    val s = v.state
    Column(
        Modifier
            .fillMaxWidth()
            .softShadow(RoundedCornerShape(28.dp), Color(0x2E181C30), 14.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xF2FFFFFF))
            .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(s?.let { GoFormat.clock(it.eta, show.zone) } ?: "–", style = Figures.copy(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold), color = WalkColours.ink)
                val left = s?.let { "${GoFormat.minutes(it.remainingTime)} · ${GoFormat.distance(it.remaining, show.units)}" }
                    ?: GoFormat.distance(v.route.distance, show.units).toString()
                Text(left, style = Figures.copy(fontSize = 13.sp), color = WalkColours.sub)
            }
            CardButton(BoardArt.speaker, stringResource(R.string.go_status_voice), actions.mute, if (show.muted) WalkColours.listBg else WalkColours.voiceBg, if (show.muted) WalkColours.sub else WalkColours.blue)
            CardButton(BoardArt.turnList, stringResource(R.string.go_all_turns), onTurns, if (turnsOpen) WalkColours.ink else WalkColours.listBg, if (turnsOpen) Color.White else WalkColours.ink)
            Box(Modifier.size(48.dp).clip(CircleShape).background(WalkColours.end).button(stringResource(R.string.go_end_trip), actions.end), contentAlignment = Alignment.Center) {
                ArtIcon(BoardArt.close.tinted.weighted(1.2f), size = 20.dp, tint = Color.White)
            }
        }
        val then = v.then
        if (then != null && !v.arrived) {
            val gap = GoFormat.distance(s?.next?.distance ?: 0.0, show.units).toString()
            val text = if (then.maneuver.type == ManeuverType.ARRIVE) stringResource(R.string.go_then_door, gap)
            else stringResource(R.string.go_then_turn, gap)
            Text(text, style = Figures.copy(fontSize = 12.sp), color = WalkColours.sub,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(WalkColours.listBg).padding(horizontal = 9.dp, vertical = 4.dp))
        }
        Text(stringResource(R.string.go_walk_attribution), style = Figures.copy(fontSize = 10.sp), color = WalkColours.note)
    }
}

@Composable
private fun CardButton(art: Art, description: String, onClick: () -> Unit, background: Color, tint: Color) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(background).button(description, onClick), contentAlignment = Alignment.Center) {
        ArtIcon(art, size = 21.dp, tint = tint)
    }
}

/** The turns still ahead, each with its arrow, street and distance: the board's "All turns". */
@Composable
private fun TurnList(show: NavShow, modifier: Modifier = Modifier) {
    val v = show.view
    val s = v.state
    val from = (s?.stepIndex ?: 0) + 1
    val steps = v.route.steps.drop(from)
    Column(
        modifier.fillMaxWidth().heightIn(max = 360.dp).softShadow(RoundedCornerShape(24.dp), Color(0x2E181C30), 12.dp).clip(RoundedCornerShape(24.dp))
            .background(Color.White).verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
    ) {
        var along = s?.distanceToManeuver ?: 0.0
        for ((i, step) in steps.withIndex()) {
            if (i > 0) along += steps[i - 1].distance
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(WalkColours.voiceBg), contentAlignment = Alignment.Center) {
                    TurnArrow(ArrowShape(step.maneuver.type, step.maneuver.turn, step.maneuver.exit), size = 22.dp, colour = WalkColours.blue)
                }
                Text(step.street ?: step.ref ?: "", style = Figures.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = WalkColours.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(GoFormat.distance(along, show.units).toString(), style = Figures.copy(fontSize = 13.sp), color = WalkColours.sub, modifier = Modifier.width(64.dp))
            }
        }
    }
}

/** A round button for a light bottom bar (the Sail board's voice). */
@Composable
internal fun LightButton(art: Art, description: String, onClick: () -> Unit, on: Boolean = false) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (on) WalkColours.ink else WalkColours.listBg).button(description, onClick),
        contentAlignment = Alignment.Center,
    ) {
        ArtIcon(art, size = 20.dp, tint = if (on) Color.White else WalkColours.ink)
    }
}
