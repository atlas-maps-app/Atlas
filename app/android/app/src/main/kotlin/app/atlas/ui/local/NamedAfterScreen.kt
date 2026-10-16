package app.atlas.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import java.util.Locale

private val Ink = Color(0xFF2A1E10)
private val Sepia = Color(0xFF7A6A50)
private val Cream = Color(0xFFF4EAD6)

/** The century as a reader writes it: "17th century", "17e eeuw", "17. Jahrhundert". */
@Composable
internal fun centuryLabel(century: Int): String = stringResource(R.string.named_century, ordinal(century))

internal fun ordinal(n: Int): String = when (Locale.getDefault().language) {
    "en" -> n.toString() + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
    "nl" -> "${n}e"
    "de" -> "$n."
    "fr" -> if (n == 1) "1er" else "${n}e"
    else -> n.toString()
}

/** Who or what the street you stand in is named after: the street's sign, the namesake, their era and gender. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NamedAfterScreen(args: Dest.NamedAfter, nav: Navigator) {
    val services = LocalServices.current
    val at = remember(args) { LatLon(args.lat, args.lon) }
    val result by produceState<NamedAfter?>(null, args) {
        val online = services.local.online()
        val streets = services.local.finder.find("highway and name", at, 80.0, limit = 20, online = online).places.map { it.place }
        value = try {
            services.local.etymology.lookup(at, streets, args.name, listOf(Locale.getDefault().language, "en"), online)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            NamedAfter(args.name, null, emptyList(), null, NamedAfter.State.ERROR)
        }
    }
    val place = rememberPlaceName(at)

    BoardPage(background = Cream) {
        BoardTitleBar(stringResource(R.string.named_title), onBack = { nav.back() }, colour = Ink, backBackground = Color.White)
        Spacer(Modifier.height(14.dp))
        val r = result
        val name = r?.streetName ?: args.name
        // The street's enamel sign: white on blue, with a white rim, as street names are signed in the Netherlands.
        Box(
            Modifier.fillMaxWidth().height(96.dp).cssShadow(RoundedCornerShape(10.dp), 6.dp, 14.dp, Color(0x33000000)).clip(RoundedCornerShape(10.dp))
                .background(Color.White).padding(5.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF0B4A98)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name ?: stringResource(R.string.named_here), color = Color.White, fontFamily = InterFamily, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = (-0.01).sp, modifier = Modifier.padding(horizontal = 12.dp))
                if (place != null) Text(place, color = Color(0xCCFFFFFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Spacer(Modifier.height(18.dp))
        when {
            r == null -> Text(stringResource(R.string.kit_loading), color = Sepia, fontSize = 14.sp)
            r.state == NamedAfter.State.NO_STREET -> StateMessage("ui-signpost", stringResource(R.string.named_no_street), text = stringResource(R.string.named_no_street_text), tint = Sepia)
            r.namesakes.isNotEmpty() -> {
                SmallCaps(stringResource(R.string.named_after), Sepia)
                Spacer(Modifier.height(8.dp))
                for (n in r.namesakes) NamesakeCard(n)
            }
            r.note != null -> {
                SmallCaps(stringResource(R.string.named_after), Sepia)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().cssShadow(RoundedCornerShape(18.dp), 4.dp, 12.dp, Color(0x1A503C14)).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(16.dp)) {
                    Text(r.note, style = TextStyle(fontFamily = SerifFamily, fontSize = 26.sp, lineHeight = 30.sp, color = Ink))
                    Text(stringResource(R.string.named_note_source), color = Sepia, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            r.state == NamedAfter.State.OFFLINE -> StateMessage("ui-cloud-off", stringResource(R.string.named_offline), text = stringResource(R.string.named_offline_text), tint = Sepia)
            r.state == NamedAfter.State.ERROR -> StateMessage("ui-alert-triangle", stringResource(R.string.named_error), tint = Color(0xFFC8322A))
            else -> StateMessage("ui-signpost", stringResource(R.string.named_not_recorded, name ?: ""), text = stringResource(R.string.named_not_recorded_text), tint = Sepia)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.named_source), color = Sepia, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NamesakeCard(n: Namesake) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).cssShadow(shape, 6.dp, 16.dp, Color(0x1A503C14)).clip(shape).background(Color.White).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(n.label, style = TextStyle(fontFamily = SerifFamily, fontSize = 34.sp, lineHeight = 36.sp, color = Ink))
        n.description?.let { Text(it, color = Color(0xFF5A4A34), fontSize = 14.sp, lineHeight = 19.sp) }
        if (n.born != null) {
            val years = if (n.died != null) "${n.born} – ${n.died}" else stringResource(R.string.named_born, n.born)
            Text(years, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            n.century?.let { Chip("ui.clock", centuryLabel(it)) }
            when (n.kind) {
                NamesakeKind.PERSON -> {
                    Chip("ui.you", stringResource(R.string.named_person))
                    n.gender?.let { g -> Chip(if (g == Gender.OTHER) "ui.add-person" else "ui.you", stringResource(when (g) { Gender.MALE -> R.string.named_man; Gender.FEMALE -> R.string.named_woman; Gender.OTHER -> R.string.named_other }), tint = when (g) { Gender.MALE -> Color(0xFF2E5BFF); Gender.FEMALE -> Color(0xFFD94A8C); Gender.OTHER -> Color(0xFF7B4FD6) }) }
                }
                NamesakeKind.PLACE -> Chip("ui.pin", stringResource(R.string.named_place))
                NamesakeKind.OTHER -> Unit
            }
        }
    }
}

@Composable
private fun Chip(symbol: String, text: String, tint: Color = Color(0xFF6B4A22)) {
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(Color(0xFFF4EAD6)).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = tint, size = 16.dp)
        Text(text, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
