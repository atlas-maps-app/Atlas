package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A screen of its own on paper, with the Design board's head: the 44 px back button and, 10 after it, the title in
 * Instrument Serif at 32 (italic for moods, upright for nouns), optional [actions] on the right, the row at the boards'
 * 42 px from the top; then [content] in a scrolling column with the boards' 16 dp margins. The paper is the Design
 * board's #F4F1EA. [onBack] null hides the back button (a tab's home).
 */
@Composable
fun AtlasScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    italic: Boolean = false,
    subtitle: String? = null,
    background: Color = Color(0xFFF4F1EA),
    scroll: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // The boards keep their top 40 px for the status bar; a taller one moves the head down.
    val shift = (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 40.dp).coerceAtLeast(0.dp)
    Column(
        modifier
            .fillMaxSize()
            .background(background),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColourFor(background)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 42.dp + shift, bottom = 12.dp).heightIn(min = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) BackButton(onBack)
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        Modifier.semantics { heading() },
                        style = txStyle(32f, 400, 41.5f, family = "Instrument Serif", italic = italic),
                        color = LocalContentColor.current,
                        maxLines = 2,
                    )
                    if (subtitle != null) Text(subtitle, style = txStyle(13f, 400, 17f), color = LocalContentColor.current.copy(alpha = 0.65f))
                }
                actions()
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/**
 * An honest state instead of content: loading, empty, offline or an error, each said with a big [symbol] in a soft
 * disc, a short [title] and an optional line and action.
 */
@Composable
fun StateMessage(
    symbol: String,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    tint: Color = Atlas.colors.inkSoft,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Symbol(symbol, tint = tint, size = 34.dp)
        }
        Text(title, style = Atlas.type.name, textAlign = TextAlign.Center)
        if (text != null) Text(text, style = Atlas.type.body, color = Atlas.colors.inkSoft, textAlign = TextAlign.Center)
        if (action != null) action()
    }
}

/** Padding that keeps content clear of the system bars on a full-bleed screen such as the map. */
@Composable
fun safePadding(): PaddingValues = WindowInsets.safeDrawing.asPaddingValues()
