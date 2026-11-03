package app.atlas.ui.mine

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.ui.search.copy
import app.atlas.ui.shop.startSafely
import io.nayuki.qrcodegen.QrCode
import io.nayuki.qrcodegen.QrSegment
import kotlin.math.floor

/**
 * A saved palette shared (the Map look board's share sheet): over the page's scrim a sheet 12 px from the edges, the
 * palette's name (22, 900; typed over to rename it), the delete and close buttons, the line under it, the QR code of
 * the palette's link (196 px), its code in Geist Mono, and Copy code and Share…. Delete asks once more, its button
 * turning red with the word, before the palette goes.
 */
@Composable
internal fun PaletteSheet(name: String, code: String, onClose: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var typed by remember(name) { mutableStateOf(name) }
    var deleting by remember { mutableStateOf(false) }
    fun close() {
        if (typed != name) onRename(typed)
        onClose()
    }
    BackHandler { close() }
    Box(Modifier.fillMaxSize()) {
        // the scrim: rgba(14, 18, 26, .45); a tap on it closes the sheet
        Box(Modifier.fillMaxSize().background(Color(0x730E121A)).clickable(remember { MutableInteractionSource() }, null) { close() })
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = foot(12f)).fillMaxWidth()
                .bbox(WHITE, cr(30f)).clickable(remember { MutableInteractionSource() }, null) {}
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val nameCd = stringResource(R.string.mine_look_name_cd)
                BasicTextField(
                    typed, { typed = it.replace("\n", " ").take(MapLookModel.NAME_MAX) },
                    Modifier.weight(1f).semantics { contentDescription = nameCd }
                        .onFocusChanged { if (!it.isFocused && typed != name && typed.isNotBlank()) onRename(typed) },
                    textStyle = bt(22f, 900, 28f).copy(color = Color(INK)),
                    singleLine = true,
                    cursorBrush = SolidColor(Color(INK)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                if (deleting) {
                    TextPill(
                        stringResource(R.string.mine_look_delete), bt(14f, 800, 18f), 0xFFC8322A, WHITE,
                        h = 40f, padH = 14f, onClick = onDelete,
                    )
                } else {
                    val deleteCd = stringResource(R.string.mine_look_delete_cd, name)
                    RoundIcon(LookIcons.TRASH, deleteCd) { deleting = true }
                }
                RoundIcon(LookIcons.CROSS, stringResource(R.string.kit_close)) { close() }
            }
            BText(stringResource(R.string.mine_look_share_sub), bt(13f, 600, 17f), SOFT, Modifier.padding(top = 2.dp), lines = 3)
            Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                QrImage(MapLookModel.link(code), stringResource(R.string.mine_look_qr_cd), Modifier.size(196.dp))
            }
            BText(
                code, bt(15f, 700, 20f, MONO, spacing = 0.6f, align = TextAlign.Center), INK,
                Modifier.fillMaxWidth().bbox(PAGE, cr(14f)).padding(horizontal = 12.dp, vertical = 11.dp), lines = 12,
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val copied = stringResource(R.string.mine_look_copied)
                SheetButton(stringResource(R.string.mine_look_copy), HAIR, INK, Modifier.weight(1f)) {
                    copy(context, name, code)
                    // Android 13 on shows its own note of what was copied
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(context, copied)
                }
                val share = stringResource(R.string.mine_look_share)
                SheetButton(share, BLUE, WHITE, Modifier.weight(1f)) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, name)
                        .putExtra(Intent.EXTRA_TEXT, MapLookModel.message(name, code))
                    startSafely(context, Intent.createChooser(send, share))
                }
            }
        }
    }
}

/** The sheet's round button (40 px of the pale grey) with a board icon (18 px). */
@Composable
private fun RoundIcon(paths: List<String>, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).bbox(HAIR, cr(20f)).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { PathIcon(paths, 18f, INK) }
}

/** One of the sheet's two buttons: 48 high, radius 24, 14 px 800. */
@Composable
private fun SheetButton(text: String, bg: app.atlas.ui.kit.Argb, fg: app.atlas.ui.kit.Argb, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(48.dp).bbox(bg, cr(24f)).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        BText(text, bt(14f, 800, 18f), fg)
    }
}

/**
 * The QR code of [text] in its box, as the board draws it (crisp, ink on white, the modules filling the box): each
 * module a whole number of pixels, the code centred, so no module blurs into the next. The sheet's white around it is
 * its quiet zone.
 */
@Composable
internal fun QrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { PaletteQr.encode(text) }
    Canvas(modifier.semantics { contentDescription = description }) {
        drawRect(Color.White)
        val n = qr.size
        val m = floor(minOf(size.width, size.height) / n)
        val x0 = floor((size.width - m * n) / 2f)
        val y0 = floor((size.height - m * n) / 2f)
        for (y in 0 until n) for (x in 0 until n) {
            if (qr.getModule(x, y)) drawRect(Color(INK), Offset(x0 + x * m, y0 + y * m), Size(m, m))
        }
    }
}

/** The palette's QR code: its link's scheme as bytes (lower case, as Android matches it), the code as alphanumerics. */
internal object PaletteQr {
    fun encode(link: String): QrCode {
        val at = link.indexOf(':') + 1
        val code = link.substring(at)
        val segments = if (at > 0 && QrSegment.isAlphanumeric(code)) {
            listOf(QrSegment.makeBytes(link.substring(0, at).toByteArray(Charsets.US_ASCII)), QrSegment.makeAlphanumeric(code))
        } else QrSegment.makeSegments(link)
        return QrCode.encodeSegments(segments, QrCode.Ecc.MEDIUM)
    }
}
