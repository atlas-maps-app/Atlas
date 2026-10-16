package app.atlas.ui.mine.charge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.providers.EvModel
import app.atlas.providers.EvModels
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import app.atlas.ui.mine.INK
import app.atlas.ui.mine.PAGE
import app.atlas.ui.mine.FAINT
import app.atlas.ui.mine.SOFT
import app.atlas.ui.mine.WHITE
import app.atlas.ui.mine.MineTone
import app.atlas.ui.mine.mt
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "My car": search the open EV data for your model, say how full the battery is, and choose the plugs to look for. The
 * choice is kept on this phone and, as the car's battery, goes to the Go tab so trips there plan charging stops too.
 */
@Composable
internal fun CarDialog(store: CarStore, car: Car, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var soc by remember { mutableFloatStateOf(car.soc.toFloat()) }
    val models = remember { EvModels.bundled() }
    val results = remember(query) { if (query.trim().length >= 2) models.search(query.trim(), 6) else emptyList() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.mine_car_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                car.model?.let { m ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(PAGE)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(m.title, style = mt(14f, 800))
                        Text(stringResource(R.string.mine_car_facts, m.batteryKwh.roundToInt(), m.maxDcKw?.roundToInt() ?: 0, m.consumption.roundToInt()), style = mt(12f, 400), color = Color(SOFT))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${(soc * 100).roundToInt()}%", style = mt(13f, 800, tabular = true), modifier = Modifier.padding(end = 4.dp))
                            Slider(soc, { soc = it }, valueRange = 0.05f..1f, onValueChangeFinished = { scope.launch { store.setSoc(soc.toDouble()) } }, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val on = ChargeModel.plugIdsOf(car.ports)
                            for (p in ChargeModel.PLUGS.filter { p -> p.types.any { it in m.connectors } }) {
                                val sel = p.id in on
                                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) Color(INK) else MineTone.of(0xFFF1F3F6, 0xFF2A2D34)).clickable {
                                    val next = if (sel) on - p.id else on + p.id
                                    scope.launch { store.setPorts(ChargeModel.portsOf(next)) }
                                }.padding(horizontal = 10.dp, vertical = 6.dp)) { Text(p.label, style = mt(12f, 700), color = if (sel) Color(WHITE) else Color(INK)) }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(PAGE)).padding(12.dp)) {
                    val hint = stringResource(R.string.mine_car_search)
                    if (query.isEmpty()) Text(hint, style = mt(14f, 400), color = Color(FAINT))
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = mt(14f, 500).copy(color = Color(INK)), cursorBrush = SolidColor(Color(INK)), modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint })
                }
                for (m in results) Column(Modifier.fillMaxWidth().clickable { scope.launch { store.choose(m, soc.toDouble()); onClose() } }.padding(vertical = 6.dp)) {
                    Text(m.title, style = mt(14f, 700), maxLines = 2)
                    Text(stringResource(R.string.mine_car_facts, m.batteryKwh.roundToInt(), m.maxDcKw?.roundToInt() ?: 0, m.consumption.roundToInt()), style = mt(12f, 400), color = Color(SOFT))
                }
                Text(stringResource(R.string.mine_car_credit), style = mt(10.5f, 400, line = 14f), color = Color(FAINT))
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.mine_pick_close)) } },
        dismissButton = if (car.model != null) ({ TextButton({ scope.launch { store.choose(null); onClose() } }) { Text(stringResource(R.string.mine_car_forget), color = MineTone.red) } }) else null,
    )
}
