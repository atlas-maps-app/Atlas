package app.atlas.ui.local

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.atlas.R

/**
 * The faces of the local boards, from fonts the app already bundles: Caveat (handwriting: the week's note on the Soon
 * board, the naturalist's field notes) and Cormorant Garamond (the Local cabinet's engraved labels). Both ship with the
 * eat package (licences in its LICENSES.md).
 */
internal object LocalType {
    private fun variable(res: Int, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
        Font(res, weight, style, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

    val hand: FontFamily = FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { variable(R.font.eat_caveat, it) })

    val cormorant: FontFamily = FontFamily(
        listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).flatMap {
            listOf(variable(R.font.eat_cormorant, it), variable(R.font.eat_cormorant_italic, it, FontStyle.Italic))
        },
    )
}
