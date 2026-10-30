package app.atlas.ui.mine

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.Services
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.EditQueue
import app.atlas.services.EditSender
import app.atlas.services.EditStatus
import app.atlas.services.OsmAccount
import app.atlas.services.OsmDevCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The OpenStreetMap account (the Settings board's row, `ui.account` on its purple tile): who is signed in, signing in in
 * the user's own browser and out, that edits are public under their user name, and the user's edits, each saying where
 * it stands: waiting and why, on OpenStreetMap, or not sent and why, with the way to cancel, send again or look again.
 * Debug and bench builds that carry the test server's client id add its round trip (OsmDevCheck). Laid out from the
 * Settings board's cards, rows and buttons.
 */
@Composable
fun OsmAccountScreen(args: Dest.OsmAccount, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val account = services.osmAccount
    val user by account.user.collectAsStateWithLifecycle()
    val waiting by account.waiting.collectAsStateWithLifecycle()
    val failure by account.failure.collectAsStateWithLifecycle()
    val edits by services.edits.all.collectAsStateWithLifecycle()
    val online by services.connectivity.online.collectAsStateWithLifecycle()
    var noBrowser by remember { mutableStateOf(false) }
    OsmAccountView(
        user = user,
        available = account.available,
        waiting = waiting,
        failure = failure,
        noBrowser = noBrowser,
        edits = edits,
        online = online,
        onBack = { nav.back() },
        onSignIn = { scope.launch { noBrowser = !signIn(context, account) } },
        onSignOut = { scope.launch(Dispatchers.IO) { account.signOut() } },
        onCancel = { q -> scope.launch(Dispatchers.IO) { services.edits.undo(q.id) } },
        onRetry = { q ->
            scope.launch(Dispatchers.IO) {
                if (services.edits.retry(q.id)) EditSender.schedule(context.applicationContext)
            }
        },
        onLookAgain = { q -> nav.go(Dest.FillIn(q.edit.type.api, q.edit.id, q.lat, q.lon)) },
        onSendNow = { EditSender.schedule(context.applicationContext) },
        dev = services.osmDev?.let { dev -> { DevCard(dev, context) } },
    )
}

/** Opens [account]'s sign-in page in the user's own browser; false when no browser opens it. */
private suspend fun signIn(context: Context, account: OsmAccount): Boolean {
    val page = withContext(Dispatchers.IO) { account.begin() } ?: return true
    return openInBrowser(context, page)
}

/**
 * Opens [url] in the user's browser, never in Atlas: Atlas answers openstreetmap.org links itself, so the page goes to the
 * browser that opens any web page, the phone's default one where it has one, and the page that takes the user's password
 * is seen by no view of Atlas's. False when no browser opens it.
 */
@Suppress("DEPRECATION") // the flags' typed overloads begin at API 33, above the app's least
internal fun openInBrowser(context: Context, url: String): Boolean {
    val pm = context.packageManager
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/")).addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = pm.queryIntentActivities(web, 0).map { it.activityInfo.packageName }.filter { it != context.packageName }
    val default = pm.resolveActivity(web, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    val browser = default?.takeIf { it in browsers } ?: browsers.firstOrNull() ?: return false
    val page = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser)
    if (context !is Activity) page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(page)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * The account board, stateless so its screenshots show each state: the account's card (who, the public note, sign in or
 * out, a sign-in waiting in the browser or failed), then the edits, newest first, and the test server's [dev] card.
 */
@Composable
internal fun OsmAccountView(
    user: String?,
    available: Boolean,
    waiting: Boolean,
    failure: OsmAccount.Failure?,
    noBrowser: Boolean,
    edits: List<EditQueue.Queued>,
    online: Boolean,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onCancel: (EditQueue.Queued) -> Unit,
    onRetry: (EditQueue.Queued) -> Unit,
    onLookAgain: (EditQueue.Queued) -> Unit,
    onSendNow: () -> Unit,
    dev: (@Composable () -> Unit)? = null,
) {
    BoardPage(stringResource(R.string.osm_account_title), onBack = onBack) {
        // The account: the board's row (the purple tile, the name, what it is for), the public note and the button.
        BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).bbox(MineTone.purple.argbLong(), cr(10f)), contentAlignment = Alignment.Center) { MSy("ui.account", 18f, WHITE) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    BText(user ?: stringResource(R.string.osm_account_not_signed_in), bt(14f, 800, 18f), INK)
                    BText(stringResource(if (user != null) R.string.osm_account_signed_in_sub else R.string.osm_account_signed_out_sub), bt(11f, 400, 14f), SOFT, lines = 2)
                }
                when {
                    user != null -> MiniButton(stringResource(R.string.osm_account_sign_out), onSignOut, primary = false)
                    available -> MiniButton(stringResource(R.string.osm_account_sign_in), onSignIn)
                }
            }
            BText(stringResource(R.string.osm_account_public), bt(12f, 400, 15.5f), SOFT, Modifier.padding(top = 12.dp), lines = 4)
            val note = when {
                !available -> stringResource(R.string.osm_account_unavailable)
                user != null -> null
                noBrowser -> stringResource(R.string.osm_account_no_browser)
                failure?.declined == true -> stringResource(R.string.osm_account_declined)
                failure != null -> failure.why?.let { stringResource(R.string.osm_account_failed, it) } ?: stringResource(R.string.osm_account_failed_unknown)
                waiting -> stringResource(R.string.osm_account_waiting)
                else -> null
            }
            val trouble = user == null && (noBrowser || failure != null)
            note?.let { BText(it, bt(12f, 600, 15.5f), if (trouble) RED else SOFT, Modifier.padding(top = 8.dp), lines = 3) }
        }
        // The edits: one row each, newest first, saying where it stands.
        BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp)) {
            CardLabel(stringResource(R.string.osm_edits_label), Modifier.padding(bottom = 6.dp))
            if (edits.isEmpty()) BText(stringResource(R.string.osm_edits_none), bt(12f, 400, 15.5f), SOFT, Modifier.padding(bottom = 12.dp), lines = 3)
            for (q in edits.asReversed()) {
                EditRow(q, EditStatus.of(q, user != null, online), onCancel, onRetry, onLookAgain)
            }
            if (user != null && online && edits.any { EditStatus.of(it, true, true).waiting }) {
                MiniButton(stringResource(R.string.osm_edits_send_now), onSendNow, Modifier.padding(top = 4.dp, bottom = 12.dp))
            }
        }
        dev?.invoke()
    }
}

private const val RED: Long = 0xFFC8322A
private const val GREEN: Long = 0xFF178A4E

/** One edit, as the Privacy board's kept rows: the pale tile with where it stands, its label and status, its buttons. */
@Composable
private fun EditRow(
    q: EditQueue.Queued,
    status: EditStatus,
    onCancel: (EditQueue.Queued) -> Unit,
    onRetry: (EditQueue.Queued) -> Unit,
    onLookAgain: (EditQueue.Queued) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().bbox(border = hairTop()).padding(top = 10.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val (symbol, tint) = when {
            status == EditStatus.MAPPED -> "ui.check" to GREEN
            status.waiting -> "ui.clock" to INK
            else -> "ui.warning" to RED
        }
        Box(Modifier.size(34.dp).bbox(PAGE, cr(10f)), contentAlignment = Alignment.Center) { MSy(symbol, 18f, tint) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BText(q.label, bt(14f, 800, 18f), INK, lines = 2)
            BText(editLine(q, status), bt(11f, 400, 14f), if (status.waiting || status == EditStatus.MAPPED) SOFT else RED, lines = 3)
        }
        when (status) {
            EditStatus.MAPPED -> Unit
            EditStatus.REFUSED -> Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                MiniButton(stringResource(R.string.osm_edit_retry), { onRetry(q) })
                MiniButton(stringResource(R.string.osm_edit_remove), { onCancel(q) }, primary = false)
            }
            EditStatus.CONFLICT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                MiniButton(stringResource(R.string.osm_edit_look_again), { onLookAgain(q) })
                MiniButton(stringResource(R.string.osm_edit_remove), { onCancel(q) }, primary = false)
            }
            else -> MiniButton(stringResource(R.string.osm_edit_cancel), { onCancel(q) }, primary = false)
        }
    }
}

/** What the user is told of edit [q] in [status]: waiting and why, on OpenStreetMap, or not sent and why. */
@Composable
internal fun editLine(q: EditQueue.Queued, status: EditStatus): String = when (status) {
    EditStatus.MAPPED -> q.changeset?.let { stringResource(R.string.osm_edit_mapped, it) } ?: stringResource(R.string.osm_edit_on_osm)
    EditStatus.SIGNED_OUT -> stringResource(R.string.osm_edit_signed_out)
    EditStatus.OFFLINE -> stringResource(R.string.osm_edit_offline)
    EditStatus.UNREACHABLE -> stringResource(R.string.osm_edit_unreachable)
    EditStatus.BUSY -> stringResource(R.string.osm_edit_busy)
    EditStatus.SENDING -> stringResource(R.string.osm_edit_sending)
    EditStatus.CONFLICT -> stringResource(R.string.osm_edit_conflict)
    EditStatus.REFUSED -> stringResource(R.string.osm_edit_refused, q.reason.orEmpty())
}

/**
 * The test server's card: its own account on master.apis.dev.openstreetmap.org, signed in and out as the user's is, and
 * the round trip (a node created there, then edited as an edit is sent), with what came of it.
 */
@Composable
private fun DevCard(dev: OsmDevCheck, context: Context) {
    val scope = rememberCoroutineScope()
    val user by dev.account.user.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
    var trip by remember { mutableStateOf<Result<OsmDevCheck.Trip>?>(null) }
    var noBrowser by remember { mutableStateOf(false) }
    BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
        CardLabel(stringResource(R.string.osm_dev_label), Modifier.padding(bottom = 6.dp))
        BText(stringResource(R.string.osm_dev_text), bt(12f, 400, 15.5f), SOFT, lines = 4)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BText(user ?: stringResource(R.string.osm_account_not_signed_in), bt(14f, 800, 18f), INK, Modifier.weight(1f))
            if (user == null) {
                MiniButton(stringResource(R.string.osm_account_sign_in), { scope.launch { noBrowser = !signIn(context, dev.account) } })
            } else if (!running) {
                MiniButton(stringResource(R.string.osm_dev_round_trip), {
                    running = true
                    scope.launch {
                        val at = Services.WESTERKERK
                        trip = withContext(Dispatchers.IO) { runCatching { dev.run(at.lat, at.lon) } }
                        running = false
                    }
                })
                MiniButton(stringResource(R.string.osm_account_sign_out), { scope.launch(Dispatchers.IO) { dev.account.signOut() } }, primary = false)
            }
        }
        val done = trip?.getOrNull()
        val problem = trip?.exceptionOrNull()
        val line = when {
            running -> stringResource(R.string.osm_dev_running)
            user == null && noBrowser -> stringResource(R.string.osm_account_no_browser)
            done != null -> stringResource(R.string.osm_dev_done, done.node, done.changeset)
            problem != null -> stringResource(R.string.osm_dev_failed, problem.message ?: problem.javaClass.simpleName)
            else -> null
        }
        val failed = !running && (problem != null || (user == null && noBrowser))
        line?.let { BText(it, bt(12f, 600, 15.5f), if (failed) RED else if (running) SOFT else GREEN, Modifier.padding(top = 8.dp), lines = 3) }
    }
}
