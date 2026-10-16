package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The guidance screens' colours, from the Ride, Drive and Sail boards. */
object NavColours {
    val black = Color(0xFF000000)
    val panel = Color(0xFF121212)
    val lime = Color(0xFFB8F36A)
    val end = Color(0xFFD93A3A)
    val amber = Color(0xFFFFB547)
    /** The Drive board's night. */
    val night = Color(0xFF0B1020)
    val text = Color(0xFFFFFFFF)
    val textSoft = Color(0xFFA9B1C7)
}

/**
 * The last thing the voice said, as the Drive and Sail boards show it: the speaking waveform (36 × 20, the boards'
 * blue) and the line in quotes, 14 px at 1.4, on night glass ([dark]) or on white glass with a soft shadow.
 */
@Composable
fun VoiceBubble(text: String?, modifier: Modifier = Modifier, dark: Boolean = true) {
    if (text.isNullOrBlank()) return
    Row(
        modifier
            .fillMaxWidth()
            .cssBox(
                bg = if (dark) 0xC70C1120 else 0xE6FFFFFF, r = app.atlas.ui.kit.CssCorners(20f, 20f, 20f, 20f),
                shadows = if (dark) emptyList() else listOf(app.atlas.ui.kit.CssShadow(0f, 6f, 20f, 0f, 0x1F181C30)), blur = 16f,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SymbolFit("ui.speaking", Modifier.padding(top = 1.dp).size(width = 36.dp, height = 20.dp), if (dark) Color(0xFF8FB0FF) else Color(0xFF2E5BFF))
        BoardText("“$text”", boardType("Geist, system-ui, sans-serif", 14f, lineHeight = 19.6f), if (dark) 0xFFDDE3F2 else 0xFF2A2E38, lines = 4)
    }
}
