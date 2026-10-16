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
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    Box(
        Modifier.fillMaxSize().background(Color(0xFF101320)).drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2440), Color(0xFF0C0F1A))))
            drawCircle(Brush.radialGradient(listOf(Color(0x59E8922A), Color(0x00E8922A)), center = Offset(size.width * 0.85f, size.height * 0.2f), radius = size.width * 0.7f), size.width * 0.7f, Offset(size.width * 0.85f, size.height * 0.2f))
            drawCircle(Brush.radialGradient(listOf(Color(0x4D36A6A0), Color(0x0036A6A0)), center = Offset(size.width * 0.1f, size.height * 0.55f), radius = size.width * 0.8f), size.width * 0.8f, Offset(size.width * 0.1f, size.height * 0.55f))
            drawCircle(Brush.radialGradient(listOf(Color(0x552E5BFF), Color(0x002E5BFF)), center = Offset(size.width * 0.7f, size.height * 0.85f), radius = size.width * 0.8f), size.width * 0.8f, Offset(size.width * 0.7f, size.height * 0.85f))
        },
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val back = stringResource(R.string.kit_back)
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x24FFFFFF)).clickable(role = androidx.compose.ui.semantics.Role.Button) { nav.back() }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) { Symbol("ui.back", tint = Color.White, size = 20.dp) }
            }
            val date = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(now.atZone(zone))
            Text(date, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                DateTimeFormatter.ofPattern("H:mm").format(now.atZone(zone)), color = Color.White, fontFamily = InterFamily, fontSize = 92.sp, lineHeight = 96.sp,
                fontWeight = FontWeight.Light, letterSpacing = (-0.03).sp,
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (snap == null) {
                    Text(stringResource(R.string.kit_loading), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                } else if (picked.isEmpty()) {
                    Text(stringResource(R.string.moments_none), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                for (m in picked) {
                    val v = momentView(m, now, zone, here)
                    val shape = RoundedCornerShape(24.dp)
                    Column(
                        Modifier.fillMaxWidth().clip(shape).background(Color(0x29FFFFFF)).border(1.dp, Color(0x2EFFFFFF), shape)
                            .clickable(enabled = v.dest != null) { v.dest?.let(nav::go) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(v.colour.toInt())), contentAlignment = Alignment.Center) { Symbol(v.symbol, tint = Color.White, size = 20.dp) }
                            Column(Modifier.weight(1f)) {
                                Text(v.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(v.line, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(v.because, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol("ui.lock", tint = Color.White.copy(alpha = 0.75f), size = 14.dp)
                    Text(stringResource(R.string.moments_footer, picked.size), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier.height(48.dp).clip(CircleShape).background(Color(0x2EFFFFFF)).clickable { nav.go(Dest.Ask()) }.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("ui.mic", tint = Color.White, size = 18.dp)
                    Text(stringResource(R.string.moments_ask), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}
