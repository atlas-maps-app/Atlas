package app.atlas.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
        BoardCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFE0592A)), contentAlignment = Alignment.Center) {
                    Text("A", style = TextStyle(fontFamily = SerifFamily, fontSize = 38.sp, lineHeight = 40.sp), color = Color.White)
                }
                Column {
                    Text("Atlas $version", style = mt(18f, 800), color = Atlas.colors.ink)
                    Text(stringResource(R.string.mine_about_tag), style = mt(12f, 400, line = 16f), color = Atlas.colors.inkSoft)
                }
            }
        }
        BoardCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            SectionLabel(stringResource(R.string.mine_about_map_data), colour = MineTone.steel)
            Text(stringResource(R.string.mine_about_osm), style = mt(14f, 700, line = 19f), color = Atlas.colors.ink, modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(R.string.mine_about_osm_text), style = mt(12f, 400, line = 16.5f), color = Atlas.colors.inkSoft, modifier = Modifier.padding(top = 4.dp))
        }
        val credits = remember { (Catalogue.ALL + Catalogue.BUNDLED + SourcesModel.TANKERKOENIG).distinctBy { it.id } }
        Section(stringResource(R.string.mine_about_credits)) {
            for ((i, p) in credits.withIndex()) LicenceRow(p.name, p.attribution, p.licence, i == 0)
        }
        val titles = listOf(R.string.mine_about_s_providers, R.string.mine_about_s_places, R.string.mine_about_s_code, R.string.mine_about_s_libs)
        for ((i, t) in titles.withIndex()) {
            val group = items.filter { Licences.section(it) == i }
            if (group.isEmpty()) continue
            Section(stringResource(t)) {
                for ((j, it) in group.withIndex()) LicenceRow(it.what, it.credit, it.licence, j == 0)
            }
        }
        Text(stringResource(R.string.mine_about_mit), style = mt(11f, 400, line = 15f), color = Atlas.colors.inkFaint, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    BoardCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp)) {
        SectionLabel(title, Modifier.padding(bottom = 4.dp), MineTone.steel)
        content()
    }
}

@Composable
private fun LicenceRow(name: String, credit: String?, licence: String, first: Boolean) {
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Atlas.colors.line))
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = mt(13f, 700, line = 17f), color = Atlas.colors.ink)
                if (!credit.isNullOrBlank()) Text(credit, style = mt(11.5f, 400, line = 15f), color = Atlas.colors.inkSoft)
            }
            Box(Modifier.widthLimit().clip(RoundedCornerShape(8.dp)).background(MineTone.of(0xFFF1EEE8, 0xFF2A2D34)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                Text(licence.take(60), style = mt(10f, 700, line = 13f), color = Atlas.colors.inkSoft)
            }
        }
    }
}

private fun Modifier.widthLimit(): Modifier = this.widthIn(max = 130.dp)
