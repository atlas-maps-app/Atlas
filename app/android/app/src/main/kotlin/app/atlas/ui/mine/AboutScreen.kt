package app.atlas.ui.mine

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.providers.Catalogue
import app.atlas.providers.ProviderInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One line of the licence list: what it is, where it comes from, the licence it is under, and, where its licence asks
 * for a notice its credit is not, its own [notice]; [texts] are the texts it opens where they are not those its
 * [licence]'s name opens.
 */
data class LicenceItem(
    val module: String,
    val what: String,
    val licence: String,
    val credit: String? = null,
    val notice: String? = null,
    val texts: List<String>? = null,
)

/** The list: its lines, which text each licence's name opens, and Atlas's own licence. */
data class LicenceList(val items: List<LicenceItem>, val names: Map<String, String>, val atlas: LicenceLine)

/** A licence's text whole, as written, under the [title] it is known by. */
data class LicenceText(val title: String, val text: String)

/** One paragraph of a licence's text as a phone shows it: [indent] spaces in, after a blank line when [gap]. */
data class LicencePara(val text: String, val indent: Int = 0, val gap: Boolean = true)

/** What a tap on a line opens: its name, its notice (or its credit), its licence's name and the texts, by id. */
data class LicenceLine(val what: String, val notice: String?, val licence: String, val texts: List<String>)

/**
 * The licences Atlas carries, read from `res/raw/mine_licences.json` (made from the modules' `LICENSES.md` files), and
 * their texts, from `res/raw/mine_licence_texts.json`, read when one is opened.
 */
object Licences {
    /** The name the top of About opens Atlas's own licence by. */
    const val ATLAS = "Atlas"

    fun parse(text: String): List<LicenceItem> = read(text).items

    fun read(text: String): LicenceList {
        val root = Json.parseToJsonElement(text).jsonObject
        val items = root["items"]!!.jsonArray.map {
            val o = it.jsonObject
            LicenceItem(
                o["module"]!!.jsonPrimitive.content, o["what"]!!.jsonPrimitive.content, o["licence"]!!.jsonPrimitive.content,
                o["credit"]?.jsonPrimitive?.content, o["notice"]?.jsonPrimitive?.content,
                o["texts"]?.jsonArray?.map { t -> t.jsonPrimitive.content },
            )
        }
        val names = root["names"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val atlas = root["atlas"]?.jsonObject
        val own = LicenceLine(ATLAS, atlas?.get("notice")?.jsonPrimitive?.content, "MIT License", atlas?.get("texts")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
        return LicenceList(items, names, own)
    }

    /** The texts by id. */
    fun texts(text: String): Map<String, LicenceText> = Json.parseToJsonElement(text).jsonObject["texts"]!!.jsonObject.mapValues {
        val o = it.value.jsonObject
        LicenceText(o["title"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content)
    }

    fun load(context: Context): LicenceList =
        runCatching { read(raw(context, R.raw.mine_licences)) }.getOrDefault(LicenceList(emptyList(), emptyMap(), LicenceLine(ATLAS, null, "MIT License", emptyList())))

    fun loadTexts(context: Context): Map<String, LicenceText> = runCatching { texts(raw(context, R.raw.mine_licence_texts)) }.getOrDefault(emptyMap())

    private fun raw(context: Context, id: Int) = context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    /** The credits card's sources: every source the app asks and the data the providers bundle, each once. */
    fun credits(): List<ProviderInfo> = (SourcesModel.ALL + Catalogue.BUNDLED).distinctBy { it.id }

    /**
     * The sections of the screen: the module a line comes from, grouped for a person. mine_licences.py's section()
     * mirrors it, to keep a thing two modules list in the first section that shows it.
     */
    fun section(item: LicenceItem): Int = when {
        item.module == "providers" -> 0
        item.module == "places" -> 1
        item.module == "libraries" -> 3
        else -> 2
    }

    /**
     * The texts a licence's name opens: those of each licence it names ("MIT License and Apache License 2.0", "CC BY 4.0
     * (EEA); Public Domain (zwemwater.nl)"), an aside in brackets left out, that has one; a name with "and" in it
     * ("W3C Software and Document License") is first looked up whole. None for a name that has no text (the public
     * domain, a service's terms).
     */
    fun textsOf(licence: String, names: Map<String, String>): List<String> =
        licence.split("; ").map { it.replace(ASIDE, "").trim() }.flatMap { part ->
            names[part]?.let { listOf(it) } ?: part.split(" and ", " or ").mapNotNull { names[it.trim()] }
        }.distinct()

    private val ASIDE = Regex("""\s*\([^)]*\)$""")

    /**
     * What [what] under [licence] opens: a line of the list (its notice, else its credit), Atlas itself, or a source of
     * the credits card (its credit line); null when it has no text to open.
     */
    fun line(list: LicenceList, what: String, licence: String): LicenceLine? {
        val item = list.items.firstOrNull { it.what == what && it.licence == licence }
        val line = when {
            item != null -> LicenceLine(what, item.notice ?: item.credit, licence, item.texts ?: textsOf(licence, list.names))
            what == ATLAS && licence == list.atlas.licence -> list.atlas
            else -> LicenceLine(what, credits().firstOrNull { it.name == what && it.licence == licence }?.attribution, licence, textsOf(licence, list.names))
        }
        return line.takeIf { it.texts.isNotEmpty() }
    }

    /**
     * A licence's text in paragraphs a phone can wrap: the lines of a paragraph joined where the text only wrapped them
     * at its width. A line well short of the text's widest ends where it is, and so does one before a list's item, a
     * heading or a rule; every word stays, in its order.
     */
    fun paragraphs(text: String): List<LicencePara> {
        val lines = text.split('\n').map { it.trimEnd() }
        val wide = lines.maxOfOrNull { it.length } ?: 0
        val out = mutableListOf<LicencePara>()
        var cur: StringBuilder? = null
        var indent = 0
        var gap = true
        var prev = ""
        fun end() {
            cur?.let { out += LicencePara(it.toString(), indent, gap) }
            cur = null
        }
        for (line in lines) {
            if (line.isBlank()) {
                end()
                gap = true
                prev = ""
                continue
            }
            val body = line.trimStart()
            val open = cur
            if (open != null && wraps(prev, body, wide)) {
                open.append(' ').append(body)
            } else {
                if (open != null) {
                    end()
                    gap = false
                }
                cur = StringBuilder(body)
                indent = line.length - body.length
            }
            prev = line
        }
        end()
        return out
    }

    // a line that ends a sentence runs on only when it is nearly the text's width (a heading after it starts anew)
    private fun wraps(prev: String, next: String, wide: Int): Boolean =
        prev.length >= wide * (if (prev.last() in ".:;") 0.85 else 0.6) && prev.any { it.isLetter() } && next.any { it.isLetter() } && !ITEM.containsMatchIn(next)

    /** A line that starts an item, a footnote or a heading: "- ", "1. ", "2.1. ", "(a) ", "a. ", "ii. ", "1) ", "¹ ", "# ". */
    private val ITEM = Regex("""^([-*•]|\d+(\.\d+)*[.)]|\([a-zA-Z0-9]{1,4}\)|[a-zA-Z][.)]|[ivxlc]+\.|[¹²³⁴⁵⁶⁷⁸⁹]+|#+)\s""")
}

/**
 * About & licences: what Atlas is and its licence, the credit every source asks for (OpenStreetMap and its Open Database
 * Licence, Open-Meteo, Mangrove, Open Charge Map, …), and every bundled dataset, font, symbol set and library with its
 * licence, read from the modules' own licence files. A line whose licence has a text opens it ([LicenceScreen]).
 */
@Composable
fun AboutScreen(args: Dest.About, nav: Navigator) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    val list = remember { Licences.load(context) }
    val read = stringResource(R.string.mine_about_read)
    val open = { what: String, licence: String ->
        if (Licences.line(list, what, licence) != null) ({ nav.go(Dest.Licence(what, licence)) }) else null
    }
    BoardPage(stringResource(R.string.mine_about), onBack = { nav.back() }) {
        // Atlas and its licence, in the voice card's type (16, 800 on 20.5 over 12 on 15.5).
        val atlas = open(Licences.ATLAS, list.atlas.licence)
        BoardCard(padding = PaddingValues(16.dp), onClick = atlas) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).bbox(0xFFE0592A, cr(16f)), contentAlignment = Alignment.Center) {
                    BText("A", bt(38f, 400, 38f, SERIF), WHITE)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BText("Atlas $version", bt(16f, 800, 20.5f), INK)
                    BText(stringResource(R.string.mine_about_tag), bt(12f, 400, 15.5f), SOFT, lines = 3)
                }
                if (atlas != null) MSy("ui.chevron-right", 16f, FAINT)
            }
        }
        val osm = stringResource(R.string.mine_about_osm)
        val odbl = open(osm, "ODbL")
        BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), onClick = odbl) {
            CardLabel(stringResource(R.string.mine_about_map_data))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BText(osm, bt(14f, 800, 18f), INK, Modifier.weight(1f), lines = 2)
                if (odbl != null) MSy("ui.chevron-right", 16f, FAINT)
            }
            BText(stringResource(R.string.mine_about_osm_text), bt(12f, 400, 15.5f), SOFT, Modifier.padding(top = 3.dp), lines = 12)
        }
        val credits = remember { Licences.credits() }
        Section(stringResource(R.string.mine_about_credits)) {
            for (p in credits) LicenceRow(p.name, p.attribution, p.licence, read, open(p.name, p.licence))
        }
        val titles = listOf(R.string.mine_about_s_providers, R.string.mine_about_s_places, R.string.mine_about_s_code, R.string.mine_about_s_libs)
        for ((i, t) in titles.withIndex()) {
            val group = list.items.filter { Licences.section(it) == i }
            if (group.isEmpty()) continue
            Section(stringResource(t)) {
                for (it in group) LicenceRow(it.what, it.credit, it.licence, read, open(it.what, it.licence))
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

/**
 * A credit, as the Data sources board's rows: the hairline, the name (13.5, 700 on 17.5), its credit line and the licence
 * tag, then the kit's chevron when a tap opens its licence's text ([onClick], [read] to a screen reader).
 */
@Composable
internal fun LicenceRow(name: String, credit: String?, licence: String, read: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().bbox(border = hairTop())
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = read, onClick = onClick) else Modifier)
            .padding(top = 11.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // the name and its credit whole, however long: a credit is often a font's copyright notice (Orbitron's runs
            // to five lines, fdlibm's is its whole licence)
            BText(name, bt(13.5f, 700, 17.5f), INK, lines = Int.MAX_VALUE)
            if (!credit.isNullOrBlank()) BText(credit, bt(11.5f, 400, 15f), SOFT, lines = Int.MAX_VALUE)
        }
        // a licence's name; one longer than the tag's three lines ends in "…"
        Box(Modifier.widthIn(max = 130.dp).bbox(HAIR, cr(8f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
            BText(licence, bt(10f, 700, 13f), LABEL, lines = 3)
        }
        if (onClick != null) MSy("ui.chevron-right", 16f, FAINT)
    }
}
