package app.atlas.ui.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import app.atlas.R
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Chip
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Symbol

/**
 * Asks for the location the way the boards would: a pin, a phone and a lock say "your position stays here", one
 * line of words, Allow and Not now. When the system will no longer ask (denied twice), Allow opens the app's settings.
 */
@Composable
fun LocationPermissionCard(onGranted: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        asked = true
        if (grants.values.any { it }) onGranted()
    }
    if (dismissed) return
    val activity = context as? Activity
    val blocked = asked && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    val c = Atlas.colors
    Card(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // Pin → phone → lock: where you are stays on this phone.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Bubble("ui-map-pin", c.go)
                Symbol("ui-arrow-right", tint = c.inkFaint, size = 14.dp)
                Bubble("ui-smartphone", c.ink)
                Symbol("ui-lock", tint = c.open, size = 18.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_location_title), style = Atlas.type.name)
                Text(stringResource(R.string.home_location_text), style = Atlas.type.caption, color = c.inkSoft)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Chip(stringResource(R.string.home_location_later), onClick = { dismissed = true }, outlined = true)
            PrimaryButton(
                stringResource(if (blocked) R.string.home_location_settings else R.string.home_location_allow),
                symbol = "ui-locate",
                onClick = {
                    if (blocked) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },
            )
        }
    }
}

@Composable
private fun Bubble(symbol: String, tint: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Symbol(symbol, tint = tint, size = 18.dp)
    }
}
