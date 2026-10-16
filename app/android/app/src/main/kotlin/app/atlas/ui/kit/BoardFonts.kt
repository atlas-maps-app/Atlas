package app.atlas.ui.kit

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.atlas.R

/**
 * Every typeface the boards set, by its CSS name (`font-family: 'Geist Mono'`), so a screen takes its type from its
 * board's spec: `BoardFonts.of("Geist Mono")`. Variable fonts give every weight the boards use; static ones the files
 * the boards need. The faces the Mac draws from the system have metric-compatible open twins: Arial is Arimo, Georgia is
 * Gelasio. CJK, Ethiopic and Devanagari display faces are cut to the characters the boards and screens set (other
 * characters fall back to Android's own fonts). Licences: `app/LICENSES.md`.
 */
object BoardFonts {
    private val WEIGHTS = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900).map { FontWeight(it) }

    /** A variable font with a weight axis: one file for every weight in [range], upright or italic. */
    private fun variable(res: Int, range: IntRange = 100..900, style: FontStyle = FontStyle.Normal): List<Font> =
        WEIGHTS.filter { it.weight in range }.map { w ->
            Font(res, w, style, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
        }

    private fun static(res: Int, weight: Int = 400, style: FontStyle = FontStyle.Normal) = Font(res, FontWeight(weight), style)

    val Geist: FontFamily get() = SansFamily
    val InstrumentSerif: FontFamily get() = SerifFamily
    val GeistMono = FontFamily(variable(R.font.geist_mono))
    val CormorantGaramond = FontFamily(variable(R.font.eat_cormorant, 300..700) + variable(R.font.eat_cormorant_italic, 300..700, FontStyle.Italic))
    val CourierPrime = FontFamily(static(R.font.eat_courier_prime), static(R.font.eat_courier_prime_bold, 700))
    val Caveat = FontFamily(variable(R.font.eat_caveat, 400..700))
    val Overpass = FontFamily(variable(R.font.shop_overpass))
    val VT323 = FontFamily(static(R.font.mine_vt323))
    val Fredoka = FontFamily(variable(R.font.eat_fredoka, 300..700))
    val SpecialElite = FontFamily(static(R.font.eat_special_elite))
    val TiltNeon = FontFamily(static(R.font.eat_tilt_neon))
    val PoiretOne = FontFamily(static(R.font.eat_poiret_one))
    val Bricolage = FontFamily(variable(R.font.mine_bricolage, 200..800))
    val Arimo = FontFamily(variable(R.font.arimo, 400..700))
    val Gelasio = FontFamily(variable(R.font.gelasio, 400..700) + variable(R.font.gelasio_italic, 400..700, FontStyle.Italic))
    val LibreCaslonText = FontFamily(variable(R.font.libre_caslon_text, 400..700))
    val EBGaramond = FontFamily(variable(R.font.eb_garamond, 400..800) + variable(R.font.eb_garamond_italic, 400..800, FontStyle.Italic))
    val StardosStencil = FontFamily(static(R.font.stardos_stencil), static(R.font.stardos_stencil_bold, 700))
    val ShareTechMono = FontFamily(static(R.font.share_tech_mono))
    val ShipporiMincho = FontFamily(static(R.font.shippori_mincho_bold, 700), static(R.font.shippori_mincho_extrabold, 800))
    val BebasNeue = FontFamily(static(R.font.bebas_neue))
    val BodoniModa = FontFamily(variable(R.font.bodoni_moda, 400..900) + variable(R.font.bodoni_moda_italic, 400..900, FontStyle.Italic))
    val Fraunces = FontFamily(variable(R.font.fraunces) + variable(R.font.fraunces_italic, 100..900, FontStyle.Italic))
    val SpaceGrotesk = FontFamily(variable(R.font.space_grotesk, 300..700))
    val BigShouldersStencilDisplay = FontFamily(variable(R.font.big_shoulders_stencil_display))
    val Amiri = FontFamily(static(R.font.amiri), static(R.font.amiri_bold, 700))
    val PermanentMarker = FontFamily(static(R.font.permanent_marker))
    val Orbitron = FontFamily(variable(R.font.orbitron, 400..900))
    val DMSerifDisplay = FontFamily(static(R.font.dm_serif_display))
    val YatraOne = FontFamily(static(R.font.yatra_one))
    val ZcoolXiaoWei = FontFamily(static(R.font.zcool_xiaowei))
    val GowunBatang = FontFamily(static(R.font.gowun_batang_bold, 700))
    val Chonburi = FontFamily(static(R.font.chonburi))
    val BeVietnamPro = FontFamily(static(R.font.be_vietnam_pro_semibold, 600))
    val NotoSerifEthiopic = FontFamily(variable(R.font.noto_serif_ethiopic))
    val Italiana = FontFamily(static(R.font.italiana))
    val Righteous = FontFamily(static(R.font.righteous))
    val IMFellEnglish = FontFamily(static(R.font.im_fell_english), static(R.font.im_fell_english_italic, style = FontStyle.Italic))
    val NotoSerifJP = FontFamily(variable(R.font.noto_serif_jp, 200..900))
    val NotoSerifKR = FontFamily(variable(R.font.noto_serif_kr, 200..900))
    val NotoSerifDevanagari = FontFamily(variable(R.font.noto_serif_devanagari))

    private val byName: Map<String, FontFamily> by lazy {
        mapOf(
            "geist" to Geist, "instrument serif" to InstrumentSerif, "geist mono" to GeistMono,
            "cormorant garamond" to CormorantGaramond, "courier prime" to CourierPrime, "caveat" to Caveat,
            "overpass" to Overpass, "vt323" to VT323, "fredoka" to Fredoka, "special elite" to SpecialElite,
            "tilt neon" to TiltNeon, "poiret one" to PoiretOne, "bricolage grotesque" to Bricolage, "arial" to Arimo,
            "arimo" to Arimo, "georgia" to Gelasio, "gelasio" to Gelasio, "libre caslon text" to LibreCaslonText,
            "eb garamond" to EBGaramond, "stardos stencil" to StardosStencil, "share tech mono" to ShareTechMono,
            "shippori mincho" to ShipporiMincho, "bebas neue" to BebasNeue, "bodoni moda" to BodoniModa,
            "fraunces" to Fraunces, "space grotesk" to SpaceGrotesk,
            "big shoulders stencil display" to BigShouldersStencilDisplay, "amiri" to Amiri,
            "permanent marker" to PermanentMarker, "orbitron" to Orbitron, "dm serif display" to DMSerifDisplay,
            "yatra one" to YatraOne, "zcool xiaowei" to ZcoolXiaoWei, "gowun batang" to GowunBatang,
            "chonburi" to Chonburi, "be vietnam pro" to BeVietnamPro, "noto serif ethiopic" to NotoSerifEthiopic,
            "italiana" to Italiana, "righteous" to Righteous, "im fell english" to IMFellEnglish,
            "noto serif jp" to NotoSerifJP, "noto serif kr" to NotoSerifKR, "noto serif devanagari" to NotoSerifDevanagari,
            "monospace" to FontFamily.Monospace, "serif" to FontFamily.Serif, "sans-serif" to SansFamily, "system-ui" to SansFamily,
        )
    }

    /** The family for a CSS `font-family` list: its first face the boards name, else Geist. */
    fun of(css: String): FontFamily {
        for (name in css.split(',')) {
            byName[name.trim().trim('"', '\'').lowercase()]?.let { return it }
        }
        return SansFamily
    }
}
