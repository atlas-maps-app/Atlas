package app.atlas.ui.local

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Localise
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.go.CompassHeadings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.tan
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.CssCorners
import androidx.compose.foundation.layout.Spacer

private val Ink = Color(0xFF14161B)
private val Soft = Color(0xFF676D7C)
private val Pale = Color(0xFFEEF0F4)
private val Blue = Color(0xFF2E5BFF)

/** The board's drawing area: 412 wide, the horizon at 330, the bottom sheet from 615. */
private const val FRAME_W = 412f
private const val HORIZON = 330f

/** What is around the camera: the buildings, the named places, the streets. */
private class Around(val buildings: List<Footprint>, val places: List<Place>, val streets: List<Place>)

private enum class LookTab { SCENE, PHOTO }

/** Look around: the street ahead of you as a 3D stand-in built from the map's buildings, the named places in view with their hours, and a street photo where Panoramax has one. */
@Composable
fun LookScreen(args: Dest.Look, nav: Navigator) {
    val services = LocalServices.current
    val start = args.at ?: rememberHere()
    var at by remember(start) { mutableStateOf(start) }
    val context = LocalContext.current
    val sensor by produceState<Double?>(null) { CompassHeadings.flow(context).collect { value = it } }
    var turned by remember(args.heading) { mutableStateOf(args.heading) }
    val heading = Geo.normalize(turned ?: sensor ?: 0.0)
    val lang = remember { Locale.getDefault().language }
    val clock = appClock()
    val now = remember(clock) { LocalDateTime.ofInstant(clock.instant(), clock.zone) }
    var tab by remember { mutableStateOf(LookTab.SCENE) }

    val around by produceState<Around?>(null, start) {
        val buildings = Footprints.around(services, start, 260.0, lang, limit = 700)
        val kinds = mapOf(
            "shop" to app.atlas.places.TagFilter.parse("shop and name"),
            "food" to app.atlas.places.TagFilter.parse("amenity~cafe|restaurant|bar|pub|bakery|fast_food|pharmacy|theatre|cinema|library and name"),
            "see" to app.atlas.places.TagFilter.parse("tourism~museum|gallery|attraction and name"),
        )
        val found = services.local.finder.findMany(kinds, start, 200.0, limit = 80)
        val streets = services.local.finder.find("highway and name", start, 150.0, limit = 40, out = LocalFinder.OUT_BOX).places.map { it.place }
        value = Around(buildings, found.values.flatMap { r -> r.places.map { it.place } }.distinctBy { it.key }, streets)
    }
    val photoState by produceState<PhotoState>(PhotoState.Looking, at) {
        value = PhotoState.Looking
        val img = services.local.photos.near(at)
        value = if (img == null) PhotoState.None else PhotoState.Found(img)
    }
    val photo = (photoState as? PhotoState.Found)?.image
    if (photo == null && tab == LookTab.PHOTO) tab = LookTab.SCENE

    val marks = remember(around, at, heading) {
        LookScene.marks(at, heading, around?.places.orEmpty(), { Localise.name(it.tags, lang) }).take(8)
    }
    val street = remember(around, at) { around?.let { LookScene.streetAt(at, it.streets) { p -> Localise.name(p.tags, lang) } } }

    Box(Modifier.fillMaxSize().background(Color(0xFFE6ECF2))) {
        val a = around
        when {
            a == null -> Column(Modifier.padding(top = boardShift()).padding(horizontal = 16.dp)) {
                BoardTitleBar(stringResource(R.string.look_title), onBack = { nav.back() }, colour = Ink, size = 32, backBackground = Color(0xF0FFFFFF), backTint = Ink)
                Spacer(Modifier.height(16.dp))
                BoardLine(stringResource(R.string.kit_loading), boardStyle(14f, 400, Soft.css(), 18f))
            }
            a.buildings.isEmpty() && photo == null -> Column(Modifier.padding(top = boardShift()).padding(horizontal = 16.dp)) {
                BoardTitleBar(stringResource(R.string.look_title), onBack = { nav.back() }, colour = Ink, size = 32, backBackground = Color(0xF0FFFFFF), backTint = Ink)
                StateMessage("ui-landmark", stringResource(R.string.look_empty), text = stringResource(R.string.look_empty_text), tint = Soft)
            }
            else -> {
                Scene(
                    a, at, heading, photo.takeIf { tab == LookTab.PHOTO },
                    onTurn = { deg -> turned = Geo.normalize((turned ?: sensor ?: 0.0) + deg) },
                )
                Marks(marks, now, tab == LookTab.SCENE)
                Header(heading, onBack = { nav.back() })
                Bottom(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    tab = tab, onTab = { tab = it }, photoState = photoState, street = street, heading = heading, marks = marks,
                    onStep = { metres -> at = LookScene.step(at, heading, metres) },
                )
            }
        }
    }
}

private sealed interface PhotoState {
    data object Looking : PhotoState
    data object None : PhotoState
    class Found(val image: StreetPhotoImage) : PhotoState
}

/** The board's header at 42: the back button and the compass pill ("N 354°") in white at 94 per cent, the serif title. */
@Composable
private fun Header(heading: Double, onBack: () -> Unit) {
    val name = compassName(heading)
    val label = stringResource(R.string.look_heading_label, name, heading.roundToInt() % 360)
    Row(
        Modifier.padding(top = boardShift()).padding(horizontal = 16.dp).padding(top = 42.dp).fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BackCircle(onBack, background = Color(0xF0FFFFFF), tint = Ink)
        BoardLine(stringResource(R.string.look_title), boardStyle(32f, 400, Ink.css(), 32f, SERIF, italic = true), Modifier.weight(1f))
        Row(
            Modifier.height(34.dp).boardBox(0xF0FFFFFF, corners(17f)).padding(horizontal = 12.dp).semantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("nav.north", tint = Color(0xFFC8322A), size = 14.dp)
            BoardLine("$name ${heading.roundToInt() % 360}°", boardStyle(12f, 800, Ink.css(), 15.5f, tnum = true))
        }
    }
}

/** The picture: the stand-in street, or the photograph; a drag turns it. */
@Composable
private fun Scene(a: Around, at: LatLon, heading: Double, photo: StreetPhotoImage?, onTurn: (Double) -> Unit) {
    val walls = remember(a, at, heading) { LookScene.walls(at, heading, a.buildings, FRAME_W, HORIZON) }
    val chev = remember { BoardPaths.parse("M-26 12L0-10 26 12") }
    // The cut-out of a panorama is 600 thousand pixels of trigonometry: off the main thread, and only when the heading moves a whole degree.
    val whole = if (photo?.photo?.panorama == true) heading.roundToInt() else 0
    val view by produceState<ImageBitmap?>(null, photo, whole) {
        value = photo?.let { withContext(Dispatchers.Default) { photoView(it, whole.toDouble()) } }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Dragging right turns the view left: the street follows the finger.
            detectDragGestures { change, drag -> change.consume(); onTurn(-drag.x / (size.width / LookScene.FOV)) }
        },
    ) {
        val shown = view
        if (shown != null) {
            Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        } else Canvas(Modifier.fillMaxSize()) {
            val s = size.width / FRAME_W
            scale(s, s, Offset.Zero) {
                drawRect(Brush.verticalGradient(0f to Color(0xFF9DC0E2), 1f to Color(0xFFDCE7F1), startY = 0f, endY = HORIZON), size = androidx.compose.ui.geometry.Size(FRAME_W, HORIZON))
                drawRect(Brush.verticalGradient(0f to Color(0xFFC9CBC6), 1f to Color(0xFF8E9490), startY = HORIZON, endY = size.height / s), Offset(0f, HORIZON), androidx.compose.ui.geometry.Size(FRAME_W, size.height / s - HORIZON))
                val fog = Color(0xFFD5E0EB)
                for (w in walls) {
                    val base = TimeMachine.era(w.footprint.year).colour
                    val lit = lerp(Color.Black, base, 0.55f + 0.45f * w.light)
                    val c = lerp(lit, fog, (w.depth / LookScene.FAR.toFloat() * 0.85f).coerceIn(0f, 0.85f))
                    val p = Path().apply { moveTo(w.xs[0], w.ys[0]); lineTo(w.xs[1], w.ys[1]); lineTo(w.xs[2], w.ys[2]); lineTo(w.xs[3], w.ys[3]); close() }
                    drawPath(p, c)
                    drawPath(p, Color.Black.copy(alpha = 0.12f), style = Stroke(0.6f))
                    // The storeys, every 3 m, where the wall is tall enough on the screen to show them.
                    if (w.ys[0] - w.ys[3] > 36f) {
                        var m = 3.0
                        while (m < w.heightM - 1.0) {
                            val t = (m / w.heightM).toFloat()
                            drawLine(Color.Black.copy(alpha = 0.10f), Offset(w.xs[0], w.ys[0] + (w.ys[3] - w.ys[0]) * t), Offset(w.xs[1], w.ys[1] + (w.ys[2] - w.ys[1]) * t), 0.8f)
                            m += 3.0
                        }
                    }
                }
                // The way ahead: the board's own chevrons.
                for ((x, y, sc, op) in CHEVRONS) {
                    translate(x, y) {
                        scale(sc, 0.5f, Offset.Zero) {
                            drawPath(chev, Blue.copy(alpha = op), style = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        }
    }
}

private data class Chevron(val x: Float, val y: Float, val scale: Float, val opacity: Float)

private val CHEVRONS = listOf(
    Chevron(206f, 560f, 1.00f, 0.95f), Chevron(206f, 508.2f, 0.87f, 0.83f), Chevron(206f, 462.3f, 0.73f, 0.71f),
    Chevron(206f, 422.8f, 0.60f, 0.59f), Chevron(206f, 390.6f, 0.47f, 0.47f), Chevron(206f, 366.9f, 0.33f, 0.35f),
)

/** A [photo] seen along [heading]: the cut-out of a panorama, else the picture itself. */
private fun photoView(img: StreetPhotoImage, heading: Double): ImageBitmap {
    val p = img.photo
    if (p.panorama) {
        val w = 640
        val h = 960
        val px = PanoView.render(img.pixels, img.width, img.height, heading, p.poseHeading ?: 0.0, LookScene.FOV, w, h)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    return Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The places in view as small white labels, pinned over where they stand, pushed up where two would overlap. */
@Composable
private fun Marks(marks: List<LookScene.Mark>, now: LocalDateTime, show: Boolean) {
    if (!show) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val f = LookScene.focal(w)
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val placed = ArrayList<Pair<Float, Float>>()
        for (m in marks.take(4)) {
            val cx = w / 2 + f * tan(Math.toRadians(m.relative)).toFloat()
            val left = (cx - 62f).coerceIn(16f, (w - 16f - 150f).coerceAtLeast(16f))
            var top = (HORIZON * (w / FRAME_W) - f * (6f - 1.6f) / maxOf(m.distanceM.toFloat(), 25f) - 48f).coerceIn(100f, 420f)
            // Keep clear of the labels already put down.
            while (placed.any { (l, t) -> abs(l - left) < 130f && abs(t - top) < 52f } && top < 460f) top += 54f
            placed += left to top
            val s = LookScene.status(m.hours, now)
            val (text, colour) = when {
                s == null -> null to Soft
                s.first && java.time.Duration.between(now, s.second).toMinutes() <= 60 -> stringResource(R.string.look_closes, time.format(s.second)) to Color(0xFF8A5A00)
                s.first -> stringResource(R.string.look_open_until, time.format(s.second)) to Color(0xFF12663A)
                else -> stringResource(R.string.look_opens, time.format(s.second)) to Soft
            }
            val desc = if (text != null) stringResource(R.string.look_mark_desc, m.name, text) else m.name
            // The board's label: 6 by 10 px in, radius 12, white at 94 per cent, its shadow; name and hours 1 px apart.
            Column(
                Modifier.offset(x = left.dp, y = top.dp).widthIn(max = 150.dp).boardBox(0xF0FFFFFF, corners(12f), shadow(0f, 6f, 16f, 0x2E000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp).semantics { contentDescription = desc },
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                BoardLine(m.name, boardStyle(13f, 800, Ink.css(), 17f))
                if (text != null) BoardLine(text, boardStyle(11f, 700, colour.css(), 14f))
            }
        }
    }
}

/**
 * The board's sheet: 300 px of white from 615 (radius 30, its shadow), 10 px down and 16 in, 12 apart: the grabber, the
 * view switch, the step buttons with where you look, the photo note and the sources.
 */
@Composable
private fun Bottom(
    modifier: Modifier, tab: LookTab, onTab: (LookTab) -> Unit, photoState: PhotoState, street: String?, heading: Double, marks: List<LookScene.Mark>, onStep: (Double) -> Unit,
) {
    Column(
        modifier.fillMaxWidth().boardBox(0xFFFFFFFF, CssCorners(30f, 30f, 0f, 0f), shadow(0f, -10f, 30f, 0x29000000), clip = false)
            .windowInsetsPadding(WindowInsets.navigationBars).height(300.dp).padding(start = 16.dp, end = 16.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).boardBox(0xFFD5D8DE, corners(3f)))
        val viewLabel = stringResource(R.string.look_tab_view)
        Row(Modifier.fillMaxWidth().height(40.dp).boardBox(Pale.css(), corners(20f)).padding(4.dp).semantics { contentDescription = viewLabel }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Segment(stringResource(R.string.look_tab_3d), tab == LookTab.SCENE, true, Modifier.weight(1f)) { onTab(LookTab.SCENE) }
            val photoText = when (photoState) {
                PhotoState.Looking -> stringResource(R.string.look_tab_photo_wait)
                PhotoState.None -> stringResource(R.string.look_tab_photo_none)
                is PhotoState.Found -> photoState.image.photo.year?.let { stringResource(R.string.look_tab_photo, it) } ?: stringResource(R.string.look_tab_photo_wait).substringBefore(' ')
            }
            Segment(photoText, tab == LookTab.PHOTO, photoState is PhotoState.Found, Modifier.weight(1f)) { onTab(LookTab.PHOTO) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StepButton("ui.arrow-down", Pale, Ink, stringResource(R.string.look_step_back)) { onStep(-15.0) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val dir = directionWord(heading)
                BoardLine(
                    if (street != null) stringResource(R.string.look_street, street, dir) else stringResource(R.string.look_looking, dir),
                    boardStyle(16f, 800, Ink.css(), 20.5f),
                )
                val ahead = LookScene.ahead(marks)
                BoardLine(
                    when {
                        ahead == null -> stringResource(R.string.look_nothing)
                        abs(ahead.relative) < 8 -> stringResource(R.string.look_ahead_straight, ahead.name, ahead.distanceM.roundToInt())
                        else -> stringResource(R.string.look_ahead, ahead.name, ahead.distanceM.roundToInt(), stringResource(if (ahead.relative < 0) R.string.look_side_left else R.string.look_side_right))
                    },
                    boardStyle(12f, 400, Soft.css(), 15.5f),
                )
            }
            StepButton("ui.arrow-up", Blue, Color.White, stringResource(R.string.look_step_forward)) { onStep(15.0) }
        }
        val found = (photoState as? PhotoState.Found)?.image?.photo
        Row(
            Modifier.fillMaxWidth().height(64.dp).boardBox(0xFFFFF1D6, corners(18f)).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("ui.camera.2", tint = Color(0xFF8A5A00), size = 26.dp)
            val services = LocalServices.current
            BoardPara(
                if (found != null) stringResource(R.string.look_photo_credit, found.author ?: "Panoramax", found.takenOn ?: "", found.license?.replace("-", " ") ?: "")
                else stringResource(if (!services.local.online()) R.string.look_offline_photo else R.string.look_nophoto),
                boardStyle(13f, 700, 0xFF6A4A00, 17f), Modifier.weight(1f), lines = 3,
            )
        }
        BoardPara(stringResource(R.string.look_footer), boardStyle(10f, 400, 0xFF8A8F98, 13f, align = TextAlign.Center), Modifier.fillMaxWidth())
    }
}

@Composable
private fun directionWord(heading: Double): String = when (app.atlas.ui.local.astro.Astro.compass8(heading)) {
    "N" -> stringResource(R.string.look_dir_n); "NE" -> stringResource(R.string.look_dir_ne)
    "E" -> stringResource(R.string.look_dir_e); "SE" -> stringResource(R.string.look_dir_se)
    "S" -> stringResource(R.string.look_dir_s); "SW" -> stringResource(R.string.look_dir_sw)
    "W" -> stringResource(R.string.look_dir_w); else -> stringResource(R.string.look_dir_nw)
}

/** One tab of the view switch: radius 16, ink when chosen, Geist 12 at 800. */
@Composable
private fun Segment(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxSize().boardBox(if (selected) Ink.css() else 0, corners(16f))
            .then(if (enabled) Modifier.clickable(role = Role.Tab, onClick = onClick) else Modifier).semantics { this.selected = selected; role = Role.Tab },
        contentAlignment = Alignment.Center,
    ) {
        BoardLine(text, boardStyle(12f, 800, if (selected) 0xFFFFFFFF else if (enabled) Ink.css() else 0xFFA0A4AE, 15.5f))
    }
}

/** A 52 px round step button with the board's 22 px arrow. */
@Composable
private fun StepButton(symbol: String, background: Color, ink: Color, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).boardBox(background.css(), corners(26f)).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = ink, size = 22.dp) }
}
