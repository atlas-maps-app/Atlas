package app.atlas.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.providers.KeyNeed
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.CssShadow
import kotlinx.coroutines.launch

/* The Data sources board's warm paper (`canvas/project/DataSources.dc.html`). */
private const val PAPER: Argb = 0xFFF1EDE6
private const val CARD: Argb = 0xFFFBFAF7
private const val CARD_RING: Argb = 0xFFECE7DD
private const val RULE: Argb = 0xFFEEE9E0
private const val BODY: Argb = 0xFF464C5A
private const val HEAD: Argb = 0xFF3A3F4B
private const val TAG: Argb = 0xFFF1EEE8
private const val ON: Argb = 0xFF178A4E

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
    BoardPage(title = "", onBack = null, header = false, background = Color(PAPER), gap = 12.dp, bottomPadding = 28.dp) {
        // The header: the frosted back button at y 44 and the crumb beside it, then the serif question (its 78 px box
        // 9 px under the button, 5 px of half-leading at each end) and the lead 1.5 px under it.
        Column {
            Row(Modifier.padding(top = 44.dp + shift()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton({ nav.back() }, shadows = listOf(CssShadow(0f, 6f, 20f, 0f, 0x24181C30)), blur = 20f)
                BText(stringResource(R.string.mine_data_crumb), bt(12f, 700, 15.5f), LABEL)
            }
            // the board's line is 317 px: its title breaks after "number"
            BText(stringResource(R.string.mine_data_title), bt(34f, 400, 34f, SERIF), INK, Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp).widthIn(max = 318.dp).semantics { heading() }, lines = 3)
            BText(stringResource(R.string.mine_data_text, on), bt(13f, 400, 18.2f), BODY, Modifier.padding(start = 2.dp, end = 2.dp), lines = 6)
        }
        val open = rows.filter { !it.needsKey }
        val keyed = rows.filter { it.needsKey }
        SourceCard(stringResource(R.string.mine_data_open), Modifier.padding(top = 2.7.dp)) {
            for (r in open) OpenRow(r, editing == r.info.id, onToggle = { setOn(r.info.id, it) }, onKey = { editing = if (editing == r.info.id) null else r.info.id }, onSave = { k -> scope.launch { services.keys.set(r.info.id, k) }; editing = null })
        }
        SourceCard(stringResource(R.string.mine_data_keyed)) {
            for (r in keyed) KeyedRow(r, editing == r.info.id, onToggle = { setOn(r.info.id, it) }, onKey = { editing = if (editing == r.info.id) null else r.info.id }, onSave = { k -> scope.launch { services.keys.set(r.info.id, k) }; editing = null })
        }
        SourceCard(stringResource(R.string.mine_data_outside)) {
            Column(Modifier.fillMaxWidth().bbox(border = hairTop(RULE)).padding(top = 11.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BText(stringResource(R.string.mine_data_google), bt(13.5f, 700, 17.5f), INK)
                BText(stringResource(R.string.mine_data_google_text), bt(11.5f, 400, 15f), BODY, lines = 6)
            }
        }
        // The way to what each source is sent: the lock and the blue line (12.5, 600 on 16), 8 px apart.
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).clickable(role = Role.Button) { nav.go(Dest.Privacy) }.padding(start = 2.dp, top = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MSy("ui.lock", 16f, 0xFF2E5BFF)
            BText(stringResource(R.string.mine_data_sent_link) + " →", bt(12.5f, 600, 16f), 0xFF2E5BFF, lines = 2)
        }
        BText(stringResource(R.string.mine_data_footer, keysSet), bt(10f, 400, 14.5f), LABEL, Modifier.padding(top = 2.5.dp), lines = 4)
    }
}

/** One card of sources: the board's paper card (radius 20, a 1 px inner ring), its small caps heading, then the rows. */
@Composable
private fun SourceCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().bbox(CARD, cr(20f), listOf(CssShadow(0f, 0f, 0f, 1f, CARD_RING, inset = true))).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
    ) {
        CardLabel(title, Modifier.padding(bottom = 8.dp), HEAD, spacing = 1.1f)
        content()
    }
}

/** A row's frame: the hairline on top, 10 px above and below the name (13.5, 700 on 17.5) and what it feeds (11.5 on 15). */
@Composable
private fun SourceRow(r: SourcesModel.Row, lines: Int, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().bbox(border = hairTop(RULE)).padding(top = 11.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BText(r.info.name, bt(13.5f, 700, 17.5f), INK, lines = 2)
            BText(feeds(r.info.id), bt(11.5f, 400, 15f), BODY, lines = lines)
        }
        trailing()
    }
}

/** A licence tag: 10 px 700 on 13, grey, on a pale pill 19 high (radius 8, 7 px at the sides). */
@Composable
private fun LicenceTag(text: String) {
    Box(Modifier.height(19.dp).bbox(TAG, cr(8f)).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
        BText(text, bt(10f, 700, 13f), LABEL)
    }
}

@Composable
private fun OpenRow(r: SourcesModel.Row, editing: Boolean, onToggle: (Boolean) -> Unit, onKey: () -> Unit, onSave: (String?) -> Unit) {
    Column {
        SourceRow(r, 2) {
            LicenceTag(SourcesModel.licenceLabel(r.info.licence))
            if (r.canTakeKey) KeyButton(r.keySet, onKey)
            // a source asked only when you act (a note, your edits) has no switch: nothing goes out unless you ask
            if (r.info.switchable) MineSwitch(r.on, onToggle, r.info.name, onColour = ON, knobShadow = 0x40000000)
        }
        if (editing) KeyField(r, onSave)
    }
}

@Composable
private fun KeyedRow(r: SourcesModel.Row, editing: Boolean, onToggle: (Boolean) -> Unit, onKey: () -> Unit, onSave: (String?) -> Unit) {
    Column {
        SourceRow(r, 3) {
            LicenceTag(keyKind(r.info.keyNeed))
            KeyButton(r.keySet, onKey)
            if (r.keySet) MineSwitch(r.on, onToggle, r.info.name, onColour = ON, knobShadow = 0x40000000)
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
        "osm-edits" -> R.string.mine_feeds_edits
        "commons" -> R.string.mine_feeds_images
        "atlas-packs" -> R.string.mine_feeds_packs
        "brouter" -> R.string.mine_feeds_routing
        "panoramax" -> R.string.mine_feeds_photos
        "open-prices" -> R.string.mine_feeds_prices
        "iss-orbit" -> R.string.mine_feeds_orbit
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

/** "Add key" in amber (30 high, radius 15, the 14 px key 11 px in, 12 px 700 on 15.5), or "key set" in green once there is one. */
@Composable
private fun KeyButton(keySet: Boolean, onClick: () -> Unit) {
    TextPill(
        stringResource(if (keySet) R.string.mine_key_set else R.string.mine_data_add_key), bt(12f, 700, 15.5f),
        if (keySet) 0xFFE7F4EC else 0xFFFFF1D6, if (keySet) 0xFF12663A else 0xFF8A5A00,
        h = 30f, padH = 11f, symbol = if (keySet) "ui.check" else "ui.key", symbolSize = 14f, gap = 5f, onClick = onClick,
    )
}

/** The field a key is pasted into: hidden as it is typed, saved to this phone only, removable. */
@Composable
private fun KeyField(r: SourcesModel.Row, onSave: (String?) -> Unit) {
    var text by remember(r.info.id) { mutableStateOf("") }
    var show by remember(r.info.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().bbox(TAG, cr(12f)).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                val hint = stringResource(if (r.keySet) R.string.mine_data_key_replace else R.string.mine_data_key_hint, r.info.name)
                if (text.isEmpty()) BText(hint, bt(13f, 400, 17f), FAINT)
                BasicTextField(
                    text, { text = it.trim() }, singleLine = true, textStyle = bt(13f, 500, 17f).copy(color = Color(INK)), cursorBrush = SolidColor(Color(INK)),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
            }
            val eye = stringResource(if (show) R.string.mine_data_key_hide else R.string.mine_data_key_show)
            Box(Modifier.size(36.dp).bbox(r = cr(18f)).semantics { contentDescription = eye }.clickable(role = Role.Button) { show = !show }, contentAlignment = Alignment.Center) {
                MSy(if (show) "ui.eye-off" else "ui.eye", 18f, SOFT)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniButton(stringResource(R.string.mine_data_save), { if (text.isNotBlank()) onSave(text) })
            if (r.keySet) MiniButton(stringResource(R.string.mine_data_remove), { onSave(null) }, primary = false)
            BText(stringResource(R.string.mine_data_key_note), bt(10.5f, 400, 14f), FAINT, Modifier.weight(1f), lines = 2)
        }
    }
}
