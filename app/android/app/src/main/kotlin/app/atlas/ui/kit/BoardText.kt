package app.atlas.ui.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * [Tx]'s text as a style, for text a layout places: the board's face by CSS name ([family]), [size] and [weight], its
 * CSS line box ([lineHeight] px, the glyphs centred in it as CSS centres them), letter [spacing] in px, tabular figures
 * ([tnum]) and alignment.
 */
fun txStyle(
    size: Float,
    weight: Int = 400,
    lineHeight: Float = 0f,
    family: String = "Geist",
    italic: Boolean = false,
    spacing: Float = 0f,
    tnum: Boolean = false,
    align: TextAlign = TextAlign.Unspecified,
): TextStyle = TextStyle(
    fontFamily = BoardFonts.of(family),
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    lineHeight = if (lineHeight > 0f) lineHeight.sp else TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    letterSpacing = if (spacing != 0f) (spacing / size).em else TextUnit.Unspecified,
    textAlign = align,
    fontFeatureSettings = if (tnum) "tnum" else null,
)

/** Board text in a layout: [lines] lines at most of [style] in [colour], ellipsised when live text is longer. */
@Composable
fun TxLine(text: String, style: TextStyle, colour: Argb, modifier: Modifier = Modifier, lines: Int = 1) {
    Text(text, modifier, style = style, color = argb(colour), maxLines = lines, overflow = TextOverflow.Ellipsis, softWrap = lines > 1)
}
