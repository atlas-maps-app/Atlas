package app.atlas.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.providers.Catalogue
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.SerifFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One line of the licence list: what it is, where it comes from, and the licence it is under. */
data class LicenceItem(val module: String, val what: String, val licence: String, val credit: String? = null)

/** The licences Atlas carries, read from `res/raw/mine_licences.json` (made from the modules' `LICENSES.md` files). */
object Licences {
    fun parse(text: String): List<LicenceItem> = Json.parseToJsonElement(text).jsonObject["items"]!!.jsonArray.map {
        val o = it.jsonObject
        LicenceItem(o["module"]!!.jsonPrimitive.content, o["what"]!!.jsonPrimitive.content, o["licence"]!!.jsonPrimitive.content, o["credit"]?.jsonPrimitive?.content)
    }

    /** The sections of the screen: the module a line comes from, grouped for a person. */
    fun section(item: LicenceItem): Int = when {
        item.module == "providers" -> 0
        item.module == "places" -> 1
        item.module == "libraries" -> 3
        else -> 2
    }
}

/**
 * About & licences: what Atlas is and its licence, the credit every source asks for (OpenStreetMap and its Open Database
 * Licence, Open-Meteo, Mangrove, Open Charge Map, …), and every bundled dataset, font, symbol set and library with its
 * licence, read from the modules' own licence files.
 */
@Composable
fun AboutScreen(args: Dest.About, nav: Navigator) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    val items = remember { runCatching { Licences.parse(context.resources.openRawResource(R.raw.mine_licences).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList()) }
    BoardPage(stringResource(R.string.mine_about), onBack = { nav.back() }) {
        // Atlas and its licence, in the voice card's type (16, 800 on 20.5 over 12 on 15.5).
        BoardCard(padding = PaddingValues(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).bbox(0xFFE0592A, cr(16f)), contentAlignment = Alignment.Center) {
                    BText("A", bt(38f, 400, 38f, SERIF), WHITE)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BText("Atlas $version", bt(16f, 800, 20.5f), INK)
                    BText(stringResource(R.string.mine_about_tag), bt(12f, 400, 15.5f), SOFT, lines = 3)
                }
            }
        }
        BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
            CardLabel(stringResource(R.string.mine_about_map_data))
            BText(stringResource(R.string.mine_about_osm), bt(14f, 800, 18f), INK, Modifier.padding(top = 8.dp), lines = 2)
            BText(stringResource(R.string.mine_about_osm_text), bt(12f, 400, 15.5f), SOFT, Modifier.padding(top = 3.dp), lines = 12)
        }
        val credits = remember { (SourcesModel.ALL + Catalogue.BUNDLED).distinctBy { it.id } }
        Section(stringResource(R.string.mine_about_credits)) {
            for (p in credits) LicenceRow(p.name, p.attribution, p.licence)
        }
        val titles = listOf(R.string.mine_about_s_providers, R.string.mine_about_s_places, R.string.mine_about_s_code, R.string.mine_about_s_libs)
        for ((i, t) in titles.withIndex()) {
            val group = items.filter { Licences.section(it) == i }
            if (group.isEmpty()) continue
            Section(stringResource(t)) {
                for (it in group) LicenceRow(it.what, it.credit, it.licence)
            }
        }
        BText(stringResource(R.string.mine_about_mit), bt(11f, 400, 14f, align = TextAlign.Center), 0xFF7A7F8C, Modifier.fillMaxWidth().padding(top = 12.dp), lines = 4)
    }
}

/** A card of credits: the small caps label 12 px in, then the rows. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp)) {
        CardLabel(title, Modifier.padding(bottom = 8.dp))
        content()
    }
}

/** A credit, as the Data sources board's rows: the hairline, the name (13.5, 700 on 17.5), its credit line and the licence tag. */
@Composable
private fun LicenceRow(name: String, credit: String?, licence: String) {
    Row(
        Modifier.fillMaxWidth().bbox(border = hairTop()).padding(top = 11.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BText(name, bt(13.5f, 700, 17.5f), INK, lines = 2)
            if (!credit.isNullOrBlank()) BText(credit, bt(11.5f, 400, 15f), SOFT, lines = 4)
        }
        Box(Modifier.widthIn(max = 130.dp).bbox(HAIR, cr(8f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
            BText(licence.take(60), bt(10f, 700, 13f), LABEL, lines = 3)
        }
    }
}
