package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.model.Place
import app.atlas.nav.LocalServices
import app.atlas.services.EditQueue
import app.atlas.services.EditStatus
import app.atlas.ui.kit.Symbol
import app.atlas.ui.mine.editLine

/**
 * The user's edit of this place to OpenStreetMap, as the account's list says it: waiting and why, on OpenStreetMap, or
 * not sent and why ([EditStatus]). It opens the account, where the edit can be cancelled, sent again or looked at again.
 * Nothing shows for a place the user has not edited.
 */
@Composable
internal fun YourEditCard(place: Place, onOpen: () -> Unit) {
    val services = LocalServices.current
    val edits by services.edits.all.collectAsStateWithLifecycle()
    val user by services.osmAccount.user.collectAsStateWithLifecycle()
    val online by services.connectivity.online.collectAsStateWithLifecycle()
    val id = place.id ?: return
    val q = edits.lastOrNull { it.edit.id == id.id && it.edit.type.name == id.type.name } ?: return
    YourEditView(q, EditStatus.of(q, user != null, online), onOpen)
}

/** The card for edit [q] in [status], laid out as the sheet's review card: its symbol, the title and where it stands. */
@Composable
internal fun YourEditView(q: EditQueue.Queued, status: EditStatus, onOpen: () -> Unit) {
    val problem = !status.waiting && status != EditStatus.MAPPED
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFF1EEE8)).clickable(role = Role.Button, onClick = onOpen).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (symbol, tint) = when {
            status == EditStatus.MAPPED -> "ui.check" to 0xFF1E9E5A
            problem -> "ui.warning" to 0xFFC8322A
            else -> "ui.clock" to 0xFFE8922A
        }
        Symbol(symbol, tint = Color(tint), size = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.osm_place_edit), style = boardText(14f, 700, 18f), color = Color(0xFF14161B))
            Text(editLine(q, status), style = boardText(12f, 400, 15.5f), color = if (problem) Color(0xFFB03A3A) else Color(0xFF464C5A), maxLines = 2)
        }
    }
}
