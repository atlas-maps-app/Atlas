package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import app.atlas.places.Brand
import app.atlas.places.Palette

/**
 * How Atlas shows a brand, one rule for every screen (the owner, 5 November: "think about how should brands be featured
 * everywhere"): on its own sign, in the colours it has in the street, wherever it is shown (the Brands tiles, a brand's
 * page, a place's header and brand line, a card's fascia, the shop screens) and, since the owner asked of a green Albert
 * Heijn among the groceries "Why doesnt albert heijn get its color on this image?" (6 November), on the search's pins
 * and rows too ([signOf]). There a brand whose colour is not known wears its logo on its family's colour ([markOf]);
 * shown itself, it is on the neutral sign ([Palette.NEUTRAL_SIGN]), never on a colour made up for it.
 */
object BrandSigns {
    /** The colour of [brand]'s sign, opaque ARGB: its own, else the neutral sign. */
    fun argb(brand: Brand?): Int = brand?.colour ?: Palette.NEUTRAL_SIGN

    /** The colour of [brand]'s sign: its own, else the neutral sign. */
    fun colour(brand: Brand?): Color = Color(argb(brand))

    /** The lettering on [brand]'s sign: its own text colour, else the ink or white, whichever reads on the sign. */
    fun ink(brand: Brand?): Color = brand?.textColour?.let { Color(it) } ?: inkOn(colour(brand))

    /** The boards' ink on a light sign, white on a dark or a deep one. */
    fun inkOn(sign: Color): Color = if (sign.luminance() > 0.45f) Color(INK) else Color.White

    private const val INK = 0xFF14161B
}

/**
 * [brand]'s sign: its logo, [logo] high, on its colour in [shape]; where it has no logo, the first letter of [name] in
 * [lettering], the screen's own face for it. [brand] may be null for a chain the index does not know.
 */
@Composable
fun BrandSign(
    brand: Brand?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(size * 0.28f),
    logo: Dp = size * 0.62f,
    lettering: TextStyle = txStyle(size.value * 0.5f, 900, size.value * 0.6f),
) {
    val ink = BrandSigns.ink(brand)
    Box(modifier.size(size).clip(shape).background(BrandSigns.colour(brand)), contentAlignment = Alignment.Center) {
        val slug = brand?.logoSlug
        if (slug != null) Symbol("logo-$slug", tint = ink, size = logo)
        else Text(name.trim().take(1).uppercase(), style = lettering, color = ink, maxLines = 1)
    }
}
