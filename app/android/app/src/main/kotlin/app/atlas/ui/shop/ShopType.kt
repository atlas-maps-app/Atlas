package app.atlas.ui.shop

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.atlas.R
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.SerifFamily

/**
 * The faces the shop boards set their objects in: Geist (the app's Inter) for the interface, Instrument Serif for
 * titles, Overpass for road-sign lettering (the aisle sign, the street sign), Caveat for the handwritten list, Courier
 * Prime for receipts and Special Elite for the typewritten card catalogue. Caveat, Courier Prime, Special Elite and
 * Tilt Neon are the fonts the eat package bundles; Overpass is this package's (licence in LICENSES.md).
 */
internal object ShopType {
    private fun variable(res: Int, weight: FontWeight) =
        Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

    /** Overpass, 700 to 900: the highway-sign face of the aisle and street signs. */
    val overpass: FontFamily = FontFamily(listOf(FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black).map { variable(R.font.shop_overpass, it) })

    /** Caveat: the shopping list's blue handwriting. */
    val hand: FontFamily = FontFamily(listOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold).map { variable(R.font.eat_caveat, it) })

    /** Courier Prime: till receipts. */
    val receipt: FontFamily = FontFamily(Font(R.font.eat_courier_prime), Font(R.font.eat_courier_prime_bold, FontWeight.Bold))

    /** Special Elite: the catalogue's typed tabs and the rubber stamp. */
    val typewriter: FontFamily = FontFamily(Font(R.font.eat_special_elite))

    /** Tilt Neon: a shopfront's neon sign. */
    val neon: FontFamily = FontFamily(Font(R.font.eat_tilt_neon))

    /** The family for a CSS `font-family` list as the boards write it. */
    fun familyFor(css: String): FontFamily {
        val f = css.lowercase()
        return when {
            "instrument serif" in f || f.startsWith("serif") -> SerifFamily
            "overpass" in f -> overpass
            "caveat" in f || "cursive" in f -> hand
            "special elite" in f -> typewriter
            "courier" in f || "mono" in f -> receipt
            "tilt neon" in f -> neon
            else -> InterFamily
        }
    }
}
