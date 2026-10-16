package app.atlas.ui.eat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily

/**
 * The type of the eat screens' objects, the boards' own faces: Cormorant Garamond for the printed menu and the bar,
 * Caveat for chalk and ink, Poiret One for Art Deco caps, Tilt Neon for the neon, Fredoka for the sweets, Special
 * Elite and Courier Prime for the market's typewriter (licences in LICENSES.md in this package). Geist is the app's
 * Inter.
 */
object EatType {
    private fun variable(res: Int, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
        Font(res, weight, style, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

    private val weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    /** Handwriting: chalk on the café's slate, ink on the guest check. */
    val hand: FontFamily = FontFamily(weights.map { variable(R.font.eat_caveat, it) })

    /** Cormorant Garamond, upright and italic, 500 to 700. */
    val cormorant: FontFamily = FontFamily(
        listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).flatMap {
            listOf(variable(R.font.eat_cormorant, it), variable(R.font.eat_cormorant_italic, it, FontStyle.Italic))
        },
    )

    /** Poiret One: the bar's Art Deco capitals. */
    val poiret: FontFamily = FontFamily(Font(R.font.eat_poiret_one))

    /** Tilt Neon: the late-night tubes. */
    val tiltNeon: FontFamily = FontFamily(Font(R.font.eat_tilt_neon))

    /** Fredoka: the sweets' round letters. */
    val fredoka: FontFamily = FontFamily(weights.map { variable(R.font.eat_fredoka, it) })

    /** Special Elite: the market's stamped typewriter. */
    val specialElite: FontFamily = FontFamily(Font(R.font.eat_special_elite))

    /** Courier Prime: the market's typed rows. */
    val courier: FontFamily = FontFamily(Font(R.font.eat_courier_prime), Font(R.font.eat_courier_prime_bold, FontWeight.Bold))

    /** The printed menu: Cormorant Garamond, as the Carte board sets it. */
    val menu = TextStyle(fontFamily = cormorant, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp)
    val menuItalic = menu.copy(fontStyle = FontStyle.Italic)

    /** Letter-spaced small caps for the menu's chapters and tabs. */
    val smallCaps = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.18.em)

    val chalk = TextStyle(fontFamily = hand, fontSize = 22.sp, lineHeight = 26.sp, color = Color(0xFFF3F0E6))
    val ink = TextStyle(fontFamily = hand, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, color = Color(0xFF1F3A93))

    /** Art Deco caps: a light, wide-set grotesque. */
    val deco = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Light, letterSpacing = 0.06.em)
    val decoSerif = TextStyle(fontFamily = SerifFamily, letterSpacing = 0.08.em)

    val typewriter = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 0.04.em)

    /** Neon: [colour] text with a glow of itself. */
    fun neon(colour: Color, size: Float, glow: Float = 18f) = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = size.sp,
        color = lerp(colour, Color.White, 0.4f),
        shadow = Shadow(colour, blurRadius = glow),
    )
}
