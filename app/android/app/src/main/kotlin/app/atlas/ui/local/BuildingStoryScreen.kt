package app.atlas.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssShadow
import java.time.LocalDate
import java.util.Locale

private val Ink = Color(0xFF2A1E10)
private val Sepia = Color(0xFF7A6A50)

/** What is looked up about a building: the tags' facts, and what Wikidata adds. */
private class Story(val facts: BuildingFacts?, val looked: Boolean)

/** A building's story: the year it was built (the register's 1005 is "unknown"), its architect, heritage, former use. */
@Composable
fun BuildingStoryScreen(args: Dest.BuildingStory, nav: Navigator) {
    val services = LocalServices.current
    val at = remember(args) { LatLon(args.lat, args.lon) }
    val lang = remember { Locale.getDefault().language }
    val story by produceState<Story?>(null, args) {
        val place = buildingAt(services, at, args.osmId)
        if (place == null) { value = Story(null, true); return@produceState }
        var facts = BuildingStory.of(place, lang)
        value = Story(facts, false)
        val qid = facts.wikidata
        if (qid != null && services.local.online()) {
            val item = runCatching { services.local.etymology.building(qid, listOf(lang, "en")) }.getOrNull()
            if (item != null) facts = BuildingStory.of(place, lang, item)
        }
        value = Story(facts, true)
    }
    val today = remember { LocalDate.now(services.clock) }

    BoardPage(background = Color(0xFFF4EAD6)) {
        BoardTitleBar(stringResource(R.string.building_title), onBack = { nav.back() }, colour = Ink)
        Spacer(Modifier.height(14.dp))
        val facts = story?.facts
        when {
            story == null -> Text(stringResource(R.string.kit_loading), color = Sepia, fontSize = 14.sp)
            facts == null -> StateMessage("ui-landmark", stringResource(R.string.building_none), text = stringResource(R.string.building_none_text), tint = Sepia)
            else -> {
                Plaque(facts, today)
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    facts.architect?.let { Fact("ui.edit", R.string.building_architect, it) }
                    facts.style?.let { Fact("ui.layers", R.string.building_style, it.replaceFirstChar { c -> c.titlecase() }) }
                    facts.heritage?.let { h ->
                        Fact("ui.shield-check", R.string.building_heritage, listOfNotNull(h.designation, h.level?.let { stringResource(R.string.building_heritage_level, it) }, h.operator?.uppercase(), h.ref).joinToString(" · ").ifEmpty { stringResource(R.string.building_protected) }, accent = Color(0xFF8A5A00))
                    }
                    if (facts.former.isNotEmpty()) Fact("ui.return", R.string.building_former, facts.former.joinToString(", "))
                    facts.levels?.let { Fact("ui.layers", R.string.building_levels, stringResource(R.string.building_levels_value, it)) }
                    facts.description?.let { Fact("ui.note", R.string.building_description, it) }
                }
                facts.mappedYear?.let { Text(stringResource(R.string.building_mapped_year, it), color = Sepia, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp)) }
                if (facts.built == BuiltYear.Unknown) Text(stringResource(R.string.building_year_unknown_note), color = Sepia, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.building_source), color = Sepia, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The plaque: the year in large serif numerals on a brass-rimmed card, the name under it. */
@Composable
private fun Plaque(f: BuildingFacts, today: LocalDate) {
    val shape = RoundedCornerShape(22.dp)
    val rim = Brush.verticalGradient(0f to Color(0xFFF0D48A), 0.55f to Color(0xFFB38A36), 1f to Color(0xFFE2C06E))
    Column(Modifier.fillMaxWidth().cssShadow(shape, 10.dp, 24.dp, Color(0x40503C14)).clip(shape).background(rim).padding(4.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(Color(0xFFFFFAEC)).padding(horizontal = 20.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SmallCaps(stringResource(R.string.building_built), Sepia, size = 11)
            val (big, small) = when (val b = f.built) {
                is BuiltYear.Year -> (if (b.approx) "~" else "") + b.year to stringResource(R.string.building_age, today.year - b.year)
                is BuiltYear.Century -> centuryLabel(b.century) to null
                BuiltYear.Unknown -> "?" to stringResource(R.string.building_year_unknown)
            }
            Text(big, style = TextStyle(fontFamily = SerifFamily, fontSize = if (big.length > 6) 44.sp else 88.sp, lineHeight = if (big.length > 6) 48.sp else 92.sp, color = Ink), maxLines = 1)
            small?.let { Text(it, color = Sepia, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            Text(f.name ?: f.kind ?: stringResource(R.string.building_unnamed), style = TextStyle(fontFamily = SerifFamily, fontSize = 28.sp, lineHeight = 32.sp, color = Ink, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (f.name != null && f.kind != null) Text(f.kind, color = Sepia, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Fact(symbol: String, label: Int, value: String, accent: Color = Color(0xFF6B4A22)) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Symbol(symbol, tint = accent, size = 22.dp) }
        Column(Modifier.weight(1f)) {
            SmallCaps(stringResource(label), Sepia, size = 10)
            Text(value, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
