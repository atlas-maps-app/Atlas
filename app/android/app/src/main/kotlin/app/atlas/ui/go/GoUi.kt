package app.atlas.ui.go

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** A clickable that is a button named [description] for TalkBack (its only words when it shows a symbol alone). */
internal fun Modifier.button(description: String?, onClick: () -> Unit): Modifier =
    clickable(role = Role.Button, onClick = onClick).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)

/**
 * Text set as large as it fits, from [style]'s size down to [minSize]: every word whole on its line and the whole in
 * [maxLines] lines, as a sign maker would letter it. Below [minSize] it ellipsises.
 */
@androidx.compose.runtime.Composable
internal fun FitText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    minSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
    maxLines: Int = 2,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 16)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val longest = text.split(' ').maxByOrNull { it.length }.orEmpty()
        val sizes = generateSequence(style.fontSize.value) { it - 1f }.takeWhile { it >= minSize.value }.toList().ifEmpty { listOf(minSize.value) }
        val size = sizes.firstOrNull { s ->
            val st = style.copy(fontSize = androidx.compose.ui.unit.TextUnit(s, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(s * 1.15f, androidx.compose.ui.unit.TextUnitType.Sp))
            val word = measurer.measure(longest, st, maxLines = 1, softWrap = false)
            val whole = measurer.measure(text, st, maxLines = maxLines, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width))
            word.size.width <= width && !whole.hasVisualOverflow
        } ?: sizes.last()
        androidx.compose.material3.Text(
            text,
            style = style.copy(fontSize = androidx.compose.ui.unit.TextUnit(size, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(size * 1.15f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = color, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
