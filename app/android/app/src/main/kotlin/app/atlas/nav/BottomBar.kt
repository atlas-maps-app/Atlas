package app.atlas.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.softShadow

/** The height the floating bar and its margin take above the system navigation bar. */
val BottomBarHeight = 84.dp

/**
 * The five tabs, Map · Go · Soon · Local · Mine: line symbols with small labels on a floating white bar, the selected
 * tab on a pill of its own colour, as on the Main, Go and Mine boards.
 */
@Composable
fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = Atlas.colors
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .fillMaxWidth()
            .height(64.dp)
            .softShadow(shape, c.shadow, 14.dp)
            .clip(shape)
            .background(c.card)
            .padding(horizontal = 6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in Tab.entries) {
            val on = tab == selected
            val soft = if (c.isDark) tab.accent.copy(alpha = 0.28f) else tab.accentSoft
            val pill by animateColorAsState(if (on) soft else Color.Transparent, label = "tab")
            val tint = when {
                !on -> c.inkSoft
                c.isDark && tab == Tab.MINE -> c.ink
                else -> tab.accent
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(on, role = Role.Tab) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(width = 50.dp, height = 28.dp).clip(CircleShape).background(pill), contentAlignment = Alignment.Center) {
                    Symbol(tab.symbol, tint = tint, size = 22.dp)
                }
                Text(
                    stringResource(tab.label),
                    Modifier.padding(top = 2.dp),
                    style = Atlas.type.caption.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = if (on) c.ink else c.inkSoft,
                    maxLines = 1,
                )
            }
        }
    }
}
