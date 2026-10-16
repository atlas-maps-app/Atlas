package app.atlas.ui.go

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.Theme
import app.atlas.nav.LocalServices
import app.atlas.services.Units
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.Compass
import app.atlas.ui.kit.softShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Sail: a boat guided by ear, on the nautical chart as on the Sail board. The brass compass turned to the course, the
 * speed in knots and the boat's draft; the next bridge as a drawing of the bridge with your mast under it, its clearance
 * against your air draft and the margin; the chart with the course dashed in magenta; the last line the voice said; and
 * the arrival with the distance in nautical miles.
 */
@Composable
fun SailView(show: NavShow, actions: NavActions) {
    AtlasTheme(dark = false) {
        val services = LocalServices.current
        val v = show.view
        val s = v.state
        val bridges by produceState(emptyList<RouteBridge>(), v.route) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val crossings = services.tiles.value?.let { TileBridges.crossings(v.route, it) } ?: DoubleArray(0)
                    RouteBridges.find(v.route, services.placeStores, crossings)
                }.getOrDefault(emptyList())
            }
        }
        val next = RouteBridges.next(bridges, v.travelled)
        // Only the bridges whose clearance is known go on the chart, as their height; the others are many (a city's canals).
        val markers = bridges.mapIndexedNotNull { i, b ->
            val c = b.clearance ?: return@mapIndexedNotNull null
            val low = show.vehicle.airDraftM?.let { it > c } == true
            Marker("bridge$i", b.location, "maki-bridge", if (low) 0xFFD2232A else 0xFF3B4A5C, MarkerStyle.CHIP, value = GoFormat.size(c, show.units), priority = 50)
        }
        Box(Modifier.fillMaxSize().background(Color(0xFFE6F0F5))) {
            GuidanceMap(
                v, Modifier.fillMaxSize(), theme = Theme.CHART, routeColour = 0xFFC2185B, dashed = true, zoom = 16.4, pitch = 40.0,
                padding = PaddingValues(top = 260.dp, bottom = 200.dp), markers = markers,
            )
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val course = s?.routeBearing ?: v.route.line.bearingAt(0.0)
                    Compass(course.toFloat(), size = 104.dp, contentDescription = stringResource(R.string.go_course, course.toInt()))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Pill(stringResource(R.string.go_speed_knots_said, GoFormat.knots(s?.speed ?: 0.0)), height = 44.dp) {
                            Text(GoFormat.knots(s?.speed ?: 0.0), style = SailFigures.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = SailColours.ink)
                            Text(" kn", style = SailFigures.copy(fontSize = 13.sp), color = SailColours.sub)
                        }
                        show.vehicle.draftM?.let { d ->
                            Pill(stringResource(R.string.go_draft_said, GoFormat.size(d, show.units)), height = 36.dp) {
                                ArtIcon(BoardArt.drift, size = 16.dp, tint = SailColours.deep)
                                Text(" ${GoFormat.size(d, show.units)}", style = SailFigures.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = SailColours.deep)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val ahead = bridges.count { it.along >= v.travelled - 10 }
                    if (ahead > 0) {
                        val said = pluralStringResource(R.plurals.go_bridges_ahead, ahead, ahead)
                        Row(
                            Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(SailColours.ink).padding(start = 12.dp, end = 14.dp)
                                .semantics(mergeDescendants = true) { contentDescription = said },
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GoSymbol("go-bridge", tint = Color.White, size = 20.dp)
                            Text("$ahead", style = SailFigures.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                next?.let { (b, d) -> BridgeCard(b, d, show.vehicle.airDraftM, show.units) }
                Spacer(Modifier.weight(1f))
                VoiceBubble(show.lastSaid, dark = false)
                Spacer(Modifier.height(12.dp))
                SailFooter(show, actions)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** The Sail board's colours (canvas/project/Sail.dc.html). */
private object SailColours {
    val ink = Color(0xFF14161B)
    val sub = Color(0xFF464C5A)
    val deep = Color(0xFF1F5F99)
    val blue = Color(0xFF2E5BFF)
    val green = Color(0xFF178A4E)
    val footer = Color(0xFFFBFAF7)
    val marina = Color(0xFFE6F1FB)
}

private val SailFigures = androidx.compose.ui.text.TextStyle(fontFamily = app.atlas.ui.kit.SansFamily, fontFeatureSettings = "tnum")

@Composable
private fun Pill(description: String, height: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Row(
        Modifier
            .height(height)
            .softShadow(RoundedCornerShape(height / 2), Color(0x1F181C30), 8.dp)
            .clip(RoundedCornerShape(height / 2))
            .background(Color(0xE0FFFFFF))
            .padding(horizontal = if (height > 40.dp) 14.dp else 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The bridge card of the Sail board: the board's drawing of the bridge with a boat under it and the clearance marked,
 * then the clearance in blue, your air draft, and the margin (green with a check, or red), with the distance.
 */
@Composable
internal fun BridgeCard(bridge: RouteBridge, distance: Double, airDraft: Double?, units: Units, modifier: Modifier = Modifier) {
    val clearance = bridge.clearance
    val margin = if (clearance != null && airDraft != null) clearance - airDraft else null
    val fits = margin == null || margin >= 0
    val said = stringResource(
        R.string.go_bridge_said, bridge.name ?: stringResource(R.string.go_bridge), GoFormat.distance(distance, units).toString(),
        clearance?.let { GoFormat.size(it, units) } ?: stringResource(R.string.go_clearance_unknown),
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(118.dp)
            .softShadow(RoundedCornerShape(24.dp), Color(0x24181C30), 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xEBFFFFFF))
            .padding(start = 10.dp, end = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = said },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtBox(if (fits) BoardArt.bridge else TOO_LOW, Modifier.size(width = 170.dp, height = 98.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (clearance != null) {
                val c = GoFormat.size(clearance, units).split(' ')
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(c.first(), style = SailFigures.copy(fontSize = 26.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold), color = SailColours.blue)
                    if (c.size > 1) Text(" " + c[1], style = SailFigures.copy(fontSize = 13.sp), color = SailColours.blue)
                }
            } else {
                Text(stringResource(R.string.go_clearance_unknown), style = SailFigures.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = SailColours.sub)
            }
            airDraft?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ArtIcon(BoardArt.mast, size = 14.dp, tint = SailColours.sub)
                    Text(GoFormat.size(it, units), style = SailFigures.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = SailColours.sub)
                }
            }
            if (margin != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val colour = if (fits) SailColours.green else NavColours.end
                    ArtIcon(if (fits) BoardArt.check.tinted.weighted(1.4f) else BoardArt.close.tinted.weighted(1.2f), size = 16.dp, tint = colour)
                    Text((if (margin >= 0) "+" else "−") + GoFormat.size(abs(margin), units), style = SailFigures.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = colour)
                }
            }
            Text(
                listOfNotNull(bridge.name, GoFormat.distance(distance, units).toString()).joinToString(" · "),
                style = SailFigures.copy(fontSize = 12.sp), color = SailColours.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The board's bridge with a mast that reaches the deck: no green gap, a red mast top. */
private val TOO_LOW: Art by lazy {
    val b = BoardArt.bridge
    Art(b.x, b.y, b.w, b.h, b.parts.dropLast(1) + ArtPart("M108 50V25.5", stroke = 0xFFD93A3A, width = 2.2f, cap = StrokeCap.Round))
}

/** The Sail board's footer: the marina, the arrival in green, the distance in nautical miles, voice, and the end. */
@Composable
private fun SailFooter(show: NavShow, actions: NavActions) {
    val s = show.view.state
    val remaining = s?.remaining ?: show.view.route.distance
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .softShadow(RoundedCornerShape(30.dp), Color(0x2E181C30), 16.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(SailColours.footer)
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(SailColours.marina), contentAlignment = Alignment.Center) {
            ArtIcon(BoardArt.marina, size = 22.dp, tint = SailColours.deep)
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s?.let { GoFormat.clock(it.eta, show.zone) } ?: "–", style = SailFigures.copy(fontSize = 26.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold), color = SailColours.green)
            Text(GoFormat.distance(remaining, Units.NAUTICAL).toString(), style = SailFigures.copy(fontSize = 13.sp), color = SailColours.sub)
        }
        LightButton(BoardArt.speaker, stringResource(R.string.go_status_voice), actions.mute, on = !show.muted)
        EndButton(actions.end, size = 58.dp, background = NavColours.end, colour = Color.White)
    }
}
