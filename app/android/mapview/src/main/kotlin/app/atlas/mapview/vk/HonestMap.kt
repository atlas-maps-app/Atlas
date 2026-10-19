package app.atlas.mapview.vk

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.mapcore.MapCore
import app.atlas.mapview.EngineFrames
import app.atlas.mapview.MapController
import app.atlas.mapview.MapEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T2's honest state (plan-vector T2, K11): where the phone's graphics driver cannot draw the map (no usable Vulkan, the
 * crash guard turned it off for this driver, or the debug switch `atlas.vk.off`), the map's place says so instead of a
 * map, with the reason and the VK facts to copy; the rest of the app works around it (search, places, routes, lists).
 * Today's map is no fallback for it (T2). Logged once as `vk honest: <reason>`, which smoke.sh reads.
 */
@Composable
internal fun HonestMap(reason: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf("") }
    LaunchedEffect(reason) { Log.i(EngineFrames.TAG, "vk honest: $reason") }
    Box(modifier.fillMaxSize().background(Color(MapController.DAY_EARTH)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp).widthIn(max = 340.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp),
        ) {
            BasicText(VK_HONEST, style = TextStyle(fontSize = 17.sp, color = Color(0xFF14161B)))
            BasicText(reason, Modifier.padding(top = 8.dp, bottom = 12.dp), style = TextStyle(fontSize = 12.sp, color = Color(0xFF5A5F69)))
            Button(if (copied.isEmpty()) "Copy VK facts" else copied) {
                scope.launch {
                    val lines = withContext(Dispatchers.Default) {
                        listOf("vulkan.state: $VK_HONEST", "vulkan.refused: $reason") +
                            (MapEngine.coreConfig?.let { runCatching { MapCore.facts(it) }.getOrDefault(emptyList()) } ?: emptyList())
                    }
                    copy(context, "Atlas VK facts", lines.joinToString("\n"))
                    copied = "Copied"
                }
            }
        }
    }
}
