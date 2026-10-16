package app.atlas.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.frosted

/** The boards' 14 px under the bar, or the system's navigation bar where that is taller. */
@Composable
fun bottomBarMargin(): Dp = max(14.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

/**
 * The five tabs, Map · Go · Soon · Local · Mine, as the tab boards draw them (`<nav aria-label="Main tabs">`): a 64 px
 * glass bar 12 px from the sides and 14 px from the bottom, white at 90 % over a 24 px blur saturated 1.6, with a soft
 * shadow and a hairline ring; each tab its symbol in a 56 × 30 pill over an 11 px label 3 px under it; the selected tab
 * in its colour on a pill of that colour at 12 %, its label #14161B at weight 800, the others #4A505E at 600. The Local
 * board's bar is dark (#14161B at 88 %), its tabs white.
 */
@Composable
fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val dark = selected == Tab.LOCAL
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .padding(start = 12.dp, end = 12.dp, bottom = bottomBarMargin())
            .fillMaxWidth()
            .height(64.dp)
            .then(
                if (dark) {
                    Modifier.cssShadow(shape, y = 10.dp, blur = 30.dp, colour = Color.Black.copy(alpha = 0.45f))
                } else {
                    Modifier
                        .cssShadow(shape, y = 10.dp, blur = 30.dp, colour = Color(24, 28, 48).copy(alpha = 0.16f))
                        .cssShadow(shape, y = 0.dp, blur = 0.dp, spread = 1.dp, colour = Color(20, 22, 27).copy(alpha = 0.05f))
                },
            )
            .frosted(shape, if (dark) Color(20, 22, 27).copy(alpha = 0.88f) else Color.White.copy(alpha = 0.9f), blur = 24.dp, saturate = 1.6f)
            .padding(horizontal = 6.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in Tab.entries) {
            val on = tab == selected
            val pill by animateColorAsState(
                when {
                    !on -> Color.Transparent
                    dark -> Color.White.copy(alpha = 0.16f)
                    else -> tab.accent.copy(alpha = 0x1F / 255f)
                },
                label = "tab",
            )
            val rest = if (dark) Color.White.copy(alpha = 0.72f) else REST
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(on, role = Role.Tab) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.size(width = 56.dp, height = 30.dp).clip(RoundedCornerShape(15.dp)).background(pill), contentAlignment = Alignment.Center) {
                    Symbol(
                        tab.symbol,
                        tint = if (on) (if (dark) Color.White else tab.accent) else rest,
                        size = 22.dp,
                        weight = (if (on) 2.1f else 1.8f) / tab.symbolStroke,
                    )
                }
                Text(
                    stringResource(tab.label),
                    style = if (on) LABEL_ON else LABEL,
                    color = if (on) (if (dark) Color.White else INK) else rest,
                    maxLines = 1,
                )
            }
        }
    }
}

private val INK = Color(0xFF14161B)
private val REST = Color(0xFF4A505E)

/** `font-size: 11px; line-height: 1`, its line box trimmed so the column centres it as CSS does. */
private val LABEL = TextStyle(
    fontFamily = SansFamily,
    fontSize = 11.sp,
    fontWeight = FontWeight.SemiBold,
    lineHeight = 11.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)
private val LABEL_ON = LABEL.copy(fontWeight = FontWeight.ExtraBold)
