package app.atlas.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.atlas.ui.kit.backdropSource
import app.atlas.ui.kit.LocalBackdrop

/** The moments worth a glance now: at most four, by how soon and how much they matter. */
internal fun pickMoments(all: List<Moment>, now: java.time.Instant, limit: Int = 4): List<Moment> =
    all.sortedByDescending { it.relevance - Duration.between(now, it.at).toMinutes().coerceAtLeast(0) / 20.0 }.take(limit).sortedBy { it.at }

/**
 * Moments: a few things picked on this phone for the next hours, each with the reason it is shown (the forecast, a saved
 * place, a market's hours, the station's orbit). The lock-screen board: the time large over a dark, blurred backdrop.
 */
@Composable
fun MomentsScreen(args: Dest.Moments, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val clock = appClock()
    val zone = clock.zone
    val now = remember(clock) { clock.instant() }
    val snap by produceState<SoonSnapshot?>(null, here, now) { value = loadSoon(services, here, now, zone) }
    val picked = remember(snap) { pickMoments(snap?.moments.orEmpty(), now) }

    val backdrop = LocalBackdrop.current
    Box(Modifier.fillMaxSize().background(Color(0xFF101320))) {
        // The board's lock screen lies over a blurred, darkened photograph the app does not have: a dark sky with soft
        // glows stands in, and the glass cards blur it as the board's blur the photograph.
        Box(
            Modifier.fillMaxSize().backdropSource(backdrop).drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2440), Color(0xFF0C0F1A))))
                drawCircle(Brush.radialGradient(listOf(Color(0x59E8922A), Color(0x00E8922A)), center = Offset(size.width * 0.85f, size.height * 0.2f), radius = size.width * 0.7f), size.width * 0.7f, Offset(size.width * 0.85f, size.height * 0.2f))
                drawCircle(Brush.radialGradient(listOf(Color(0x4D36A6A0), Color(0x0036A6A0)), center = Offset(size.width * 0.1f, size.height * 0.55f), radius = size.width * 0.8f), size.width * 0.8f, Offset(size.width * 0.1f, size.height * 0.55f))
                drawCircle(Brush.radialGradient(listOf(Color(0x552E5BFF), Color(0x002E5BFF)), center = Offset(size.width * 0.7f, size.height * 0.85f), radius = size.width * 0.8f), size.width * 0.8f, Offset(size.width * 0.7f, size.height * 0.85f))
            },
        )
        Column(
            Modifier.fillMaxSize().padding(top = boardShift()).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The date at 70, the time's line box of 92 under it, the cards from 250.
            Spacer(Modifier.height(70.dp))
            val date = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(now.atZone(zone))
            BoardLine(date, boardStyle(15f, 700, 0xD9FFFFFF, 19.5f))
            BoardLine(DateTimeFormatter.ofPattern("H:mm").format(now.atZone(zone)), boardStyle(92f, 300, 0xFFFFFFFF, 92f, spacing = -2.76f, tnum = true), overflow = TextOverflow.Visible)
            Spacer(Modifier.height(68.5.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (snap == null) {
                    BoardLine(stringResource(R.string.kit_loading), boardStyle(13f, 400, 0xB3FFFFFF, 17f))
                } else if (picked.isEmpty()) {
                    BoardLine(stringResource(R.string.moments_none), boardStyle(15f, 700, 0xD9FFFFFF, 19.5f))
                }
                for (m in picked) {
                    val v = momentView(m, now, zone, here)
                    // The board's glass card: white at 16 per cent over a 24 px blur, a 1 px inner ring, 12 by 14 px in.
                    Column(
                        Modifier.fillMaxWidth().boardBox(0x29FFFFFF, corners(24f), shadow(0f, 0f, 0f, 0x2EFFFFFF, spread = 1f, inset = true), blur = 24f)
                            .clickable(enabled = v.dest != null) { v.dest?.let(nav::go) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).boardBox(v.colour, corners(12f)), contentAlignment = Alignment.Center) { Symbol(v.symbol, tint = Color.White, size = 20.dp) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                BoardLine(v.title, boardStyle(15f, 800, 0xFFFFFFFF, 19.5f))
                                BoardLine(v.line, boardStyle(12f, 400, 0xCCFFFFFF, 15.5f, tnum = true))
                            }
                        }
                        BoardPara(v.because, boardStyle(11f, 400, 0x99FFFFFF, 14f))
                    }
                }
            }
            Spacer(Modifier.height(140.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        // The foot, 30 px up: why these few, and the way to ask for more.
        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("ui.lock", tint = Color(0xBFFFFFFF), size = 14.dp)
                BoardLine(stringResource(R.string.moments_footer, picked.size), boardStyle(12f, 700, 0xBFFFFFFF, 15.5f))
            }
            Row(
                Modifier.height(48.dp).boardBox(0x2EFFFFFF, corners(24f)).clickable { nav.go(Dest.Ask()) }.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("ui.mic", tint = Color.White, size = 18.dp)
                BoardLine(stringResource(R.string.moments_ask), boardStyle(14f, 800, 0xFFFFFFFF, 18f))
            }
        }
        // The board is a lock screen and has no back; the app's sits where the other boards keep theirs, in the same glass.
        BackCircle(
            { nav.back() }, Modifier.padding(start = 16.dp, top = 42.dp + boardShift()), background = Color(0x29FFFFFF), tint = Color.White,
            shadows = shadow(0f, 0f, 0f, 0x2EFFFFFF, spread = 1f, inset = true), blur = 24f,
        )
    }
}
