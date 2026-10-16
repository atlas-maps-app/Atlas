package app.atlas.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.providers.KeyNeed
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import kotlinx.coroutines.launch

/**
 * Data sources & keys, the board "Where every number comes from": the open sources that are on by default with their
 * licence and a switch, the ones that need a key or a yes (Foursquare, Yelp, Tripadvisor, Tankerkönig, the Chargemap
 * partner slot) with a place to paste it, and a plain note on Google. Keys are kept on this phone only and never logged.
 */
@Composable
fun DataSourcesScreen(args: Dest.DataSources, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val off by services.settings.flow(SettingsKeys.PROVIDERS_OFF, emptySet()).collectAsStateWithLifecycle(emptySet())
    val keys by services.keys.keys.collectAsStateWithLifecycle()
    val rows = SourcesModel.rows(off, keys.orEmpty())
    val (on, keysSet) = SourcesModel.summary(rows)
    var editing by remember { mutableStateOf(args.provider) }
    LaunchedEffect(args.provider) { if (args.provider != null) editing = args.provider }
    fun setOn(id: String, value: Boolean) = scope.launch { services.settings.set(SettingsKeys.PROVIDERS_OFF, (if (value) off - id else off + id).takeIf { it.isNotEmpty() }) }
    BoardPage(
        title = "", onBack = null, header = false, background = MineTone.of(0xFFF1EDE6, 0xFF101114), gap = 12.dp, bottomPadding = 28.dp,
    ) {
        // The header: the back button and the small label above the serif question.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.kit_back)
            Box(Modifier.size(44.dp).clip(CircleShape).background(Atlas.colors.card).clickable { nav.back() }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
                Symbol("ui.back", size = 20.dp)
            }
            Text(stringResource(R.string.mine_data_crumb), style = mt(12f, 700), color = Atlas.colors.inkSoft)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(2.dp)) {
            Text(stringResource(R.string.mine_data_title), style = androidx.compose.ui.text.TextStyle(fontFamily = SerifFamily, fontSize = 34.sp, lineHeight = 34.sp), color = Atlas.colors.ink)
            Text(stringResource(R.string.mine_data_text, on), style = mt(13f, 400, line = 18.2f), color = Atlas.colors.inkSoft)
        }
        val open = rows.filter { !it.needsKey }
        val keyed = rows.filter { it.needsKey }
        SourceCard(stringResource(R.string.mine_data_open)) {
            for ((i, r) in open.withIndex()) OpenRow(r, i == 0, editing == r.info.id, onToggle = { setOn(r.info.id, it) }, onKey = { editing = if (editing == r.info.id) null else r.info.id }, onSave = { k -> scope.launch { services.keys.set(r.info.id, k) }; editing = null })
        }
        SourceCard(stringResource(R.string.mine_data_keyed)) {
            for ((i, r) in keyed.withIndex()) KeyedRow(r, i == 0, editing == r.info.id, onToggle = { setOn(r.info.id, it) }, onKey = { editing = if (editing == r.info.id) null else r.info.id }, onSave = { k -> scope.launch { services.keys.set(r.info.id, k) }; editing = null })
        }
        SourceCard(stringResource(R.string.mine_data_outside)) {
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.mine_data_google), style = mt(13.5f, 700), color = Atlas.colors.ink)
                Text(stringResource(R.string.mine_data_google_text), style = mt(11.5f, 400, line = 16f), color = Atlas.colors.inkSoft)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { nav.go(Dest.Privacy) }.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.mine_data_sent_link), style = mt(13f, 700), color = MineTone.blue)
            Symbol("ui.arrow-right", tint = MineTone.blue, size = 14.dp)
        }
        Text(stringResource(R.string.mine_data_footer, keysSet), style = mt(11f, 400, line = 15f), color = Atlas.colors.inkFaint, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** One card of sources: a small caps heading in ink, then the rows with hairlines. */
@Composable
private fun SourceCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MineTone.of(0xFFFBFAF7, 0xFF1B1D22)).border(1.dp, MineTone.of(0xFFECE7DD, 0xFF2C2F36), RoundedCornerShape(20.dp)).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(title.uppercase(), style = mt(11f, 800, spacing = 1.1f), color = Atlas.colors.ink)
        Column(Modifier.padding(top = 8.dp)) { content() }
    }
}

@Composable
private fun RowShell(first: Boolean, content: @Composable () -> Unit) {
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(MineTone.of(0xFFEEE9E0, 0xFF2C2F36)))
        content()
    }
}

/** A licence tag: 10 sp, 700, on a soft pill. */
@Composable
private fun LicenceTag(text: String) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MineTone.of(0xFFF1EEE8, 0xFF2A2D34)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, style = mt(10f, 700), color = Atlas.colors.inkSoft, maxLines = 1)
    }
}

@Composable
private fun OpenRow(r: SourcesModel.Row, first: Boolean, editing: Boolean, onToggle: (Boolean) -> Unit, onKey: () -> Unit, onSave: (String?) -> Unit) {
    RowShell(first) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.info.name, style = mt(13.5f, 700, line = 17f), color = Atlas.colors.ink)
                Text(feeds(r.info.id), style = mt(11.5f, 400, line = 15f), color = Atlas.colors.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LicenceTag(SourcesModel.licenceLabel(r.info.licence))
            if (r.canTakeKey) KeyButton(r.keySet, onKey)
            BoardSwitch(r.on, onToggle, r.info.name, onColour = MineTone.greenDeep)
        }
        if (editing) KeyField(r, onSave)
    }
}

@Composable
private fun KeyedRow(r: SourcesModel.Row, first: Boolean, editing: Boolean, onToggle: (Boolean) -> Unit, onKey: () -> Unit, onSave: (String?) -> Unit) {
    RowShell(first) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.info.name, style = mt(13.5f, 700, line = 17f), color = Atlas.colors.ink)
                Text(feeds(r.info.id), style = mt(11.5f, 400, line = 15f), color = Atlas.colors.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            LicenceTag(keyKind(r.info.keyNeed))
            KeyButton(r.keySet, onKey)
            if (r.keySet) BoardSwitch(r.on, onToggle, r.info.name, onColour = MineTone.greenDeep)
        }
        if (editing) KeyField(r, onSave)
    }
}

/** What a source feeds, in a few plain words (the board's second line). */
@Composable
private fun feeds(id: String): String = stringResource(
    when (id) {
        "overpass" -> R.string.mine_feeds_overpass
        "nominatim" -> R.string.mine_feeds_nominatim
        "photon" -> R.string.mine_feeds_photon
        "mangrove" -> R.string.mine_feeds_mangrove
        "ocm" -> R.string.mine_feeds_ocm
        "osm-chargers" -> R.string.mine_feeds_osm_chargers
        "ndw" -> R.string.mine_feeds_ndw
        "bnetza" -> R.string.mine_feeds_bnetza
        "afdc" -> R.string.mine_feeds_afdc
        "open-meteo" -> R.string.mine_feeds_weather
        "wikidata" -> R.string.mine_feeds_wikidata
        "osm-notes" -> R.string.mine_feeds_notes
        "transitous" -> R.string.mine_feeds_transit
        "foursquare" -> R.string.mine_feeds_foursquare
        "yelp", "tripadvisor" -> R.string.mine_feeds_ratings
        "chargemap" -> R.string.mine_feeds_chargemap
        "tankerkoenig" -> R.string.mine_feeds_fuel
        else -> R.string.mine_feeds_other
    },
)

@Composable
private fun keyKind(need: KeyNeed): String = stringResource(if (need == KeyNeed.PARTNER) R.string.mine_data_partner else R.string.mine_data_key)

/** "Add key" in amber, or "key set" in green once there is one. */
@Composable
private fun KeyButton(keySet: Boolean, onClick: () -> Unit) {
    val bg = if (keySet) Color(0xFFE7F4EC) else Color(0xFFFFF1D6)
    val fg = if (keySet) Color(0xFF12663A) else Color(0xFF8A5A00)
    Row(
        Modifier.height(34.dp).clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(if (keySet) "ui.check" else "ui.key", tint = fg, size = 14.dp)
        Text(stringResource(if (keySet) R.string.mine_key_set else R.string.mine_data_add_key), style = mt(12f, 800), color = fg, maxLines = 1)
    }
}

/** The field a key is pasted into: hidden as it is typed, saved to this phone only, removable. */
@Composable
private fun KeyField(r: SourcesModel.Row, onSave: (String?) -> Unit) {
    var text by remember(r.info.id) { mutableStateOf("") }
    var show by remember(r.info.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Atlas.colors.cardMuted).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                val hint = stringResource(if (r.keySet) R.string.mine_data_key_replace else R.string.mine_data_key_hint, r.info.name)
                if (text.isEmpty()) Text(hint, style = mt(13f, 400), color = Atlas.colors.inkFaint)
                BasicTextField(
                    text, { text = it.trim() }, singleLine = true, textStyle = mt(13f, 500).copy(color = Atlas.colors.ink), cursorBrush = SolidColor(Atlas.colors.ink),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
            }
            val eye = stringResource(if (show) R.string.mine_data_key_hide else R.string.mine_data_key_show)
            Box(Modifier.size(36.dp).clip(CircleShape).semantics { contentDescription = eye }.clickable(role = androidx.compose.ui.semantics.Role.Button) { show = !show }, contentAlignment = Alignment.Center) {
                Symbol(if (show) "ui.eye-off" else "ui.eye", tint = Atlas.colors.inkSoft, size = 18.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniButton(stringResource(R.string.mine_data_save), { if (text.isNotBlank()) onSave(text) })
            if (r.keySet) MiniButton(stringResource(R.string.mine_data_remove), { onSave(null) }, primary = false)
            Text(stringResource(R.string.mine_data_key_note), style = mt(10.5f, 400, line = 14f), color = Atlas.colors.inkFaint, modifier = Modifier.weight(1f))
        }
    }
}
