package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.SvgShaderCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A debug screen (`atlas.screen=filters`): the SVG module's filter probes (turbulence, displacement, colour matrices,
 * blurs, as the engine's renders use them) drawn through the AGSL path on the GPU next to the exact CPU path, with the
 * difference of the two. Each probe's result also goes to logcat (tag `AtlasMap`), so an emulator run verifies them.
 */
@Composable
fun FilterProbesScreen(modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    var rows by remember { mutableStateOf<List<ProbeRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.Default) { FilterProbes.run(assets) }
    }
    LazyColumn(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            BasicText(
                "SVG filter probes · AGSL ${if (Build.VERSION.SDK_INT >= 33) "on the GPU" else "needs Android 13 (CPU only here)"} · API ${Build.VERSION.SDK_INT}",
                style = TextStyle(fontSize = 15.sp),
            )
        }
        if (rows.isEmpty()) item { BasicText("Drawing…", style = TextStyle(fontSize = 13.sp)) }
        items(rows) { r ->
            Column {
                BasicText("${r.name}: ${r.summary}", style = TextStyle(fontSize = 12.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(r.cpu.asImageBitmap(), "${r.name} on the CPU", Modifier.size(120.dp))
                    r.gpu?.let { Image(it.asImageBitmap(), "${r.name} through AGSL", Modifier.size(120.dp)) }
                }
            }
        }
    }
}

/** One probe's outcome: the CPU drawing, the AGSL one (API 33+), and how far apart they are. */
class ProbeRow(val name: String, val cpu: Bitmap, val gpu: Bitmap?, val summary: String)

/** Runs the filter probes of this library's assets: every SVG in `svgprobes`. */
object FilterProbes {
    const val DIR = "svgprobes"

    fun run(assets: android.content.res.AssetManager): List<ProbeRow> {
        val fonts: SvgFonts = MapEngine.fonts(assets)
        val names = assets.list(DIR).orEmpty().filter { it.endsWith(".svg") }.sorted()
        return names.map { file ->
            val name = file.removeSuffix(".svg")
            val doc = assets.open("$DIR/$file").use { SvgParser.parse(it) }
            val row = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    val r = SvgShaderCheck.compare(doc, fonts)
                    ProbeRow(name, r.cpu, r.gpu, r.toString())
                } else {
                    ProbeRow(name, cpuOnly(doc, fonts), null, "CPU only (AGSL needs API 33)")
                }
            } catch (e: Exception) {
                ProbeRow(name, cpuOnly(doc, fonts), null, "AGSL failed: $e")
            }
            Log.i(EngineFrames.TAG, "filter probe $name: ${row.summary}")
            row
        }
    }

    private fun cpuOnly(doc: app.atlas.mapview.svg.SvgDocument, fonts: SvgFonts): Bitmap {
        val w = Math.round(doc.width * 2)
        val h = Math.round(doc.height * 2)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(b).also {
            it.drawColor(Color.WHITE)
            SvgCanvas(doc, fonts, SvgCanvas.FilterMode.CPU).draw(it, w.toFloat(), h.toFloat())
        }
        return b
    }
}
