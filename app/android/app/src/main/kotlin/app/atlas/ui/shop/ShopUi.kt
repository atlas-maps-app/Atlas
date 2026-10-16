package app.atlas.ui.shop

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.atlas.nav.LocalServices
import kotlinx.coroutines.delay
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.drawSymbol
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** "19:02": the clock the boards print. */
internal val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

internal fun LocalDateTime.hm(): String = format(HM)

/** Makes the thing a button with a spoken [label]. */
internal fun Modifier.tap(label: String, onClick: () -> Unit): Modifier =
    clickable(onClickLabel = label, role = Role.Button, onClick = onClick).semantics { contentDescription = label }

/** "→ 23:00", "closes 19:30" (amber), "opens 9:00" (grey), "24/7"; null when the hours are not mapped. */
@Composable
internal fun openLine(p: ShopPlace, area: ShopArea, at: LocalDateTime): Pair<String, Color>? {
    if (p.alwaysOpen) return "24/7" to Color(0xFF1E7A46)
    return when (p.openness(at, area.hours)) {
        Open.UNKNOWN -> null
        Open.OPEN -> p.closesAt(at, area.hours)?.let { "→ " + it.hm() to Color(0xFF464C5A) } ?: (androidx.compose.ui.res.stringResource(app.atlas.R.string.kit_open) to Color(0xFF1E7A46))
        Open.CLOSING_SOON -> androidx.compose.ui.res.stringResource(app.atlas.R.string.shop_closes_at, p.closesAt(at, area.hours)!!.hm()) to Color(0xFFB7791F)
        Open.CLOSED -> p.opensAt(at, area.hours)?.let {
            androidx.compose.ui.res.stringResource(app.atlas.R.string.shop_opens_at, opensText(it, at)) to Color(0xFF676D7C)
        } ?: (androidx.compose.ui.res.stringResource(app.atlas.R.string.kit_closed) to Color(0xFF676D7C))
    }
}

/** "9:00" today, "Wed 9:00" another day. */
internal fun opensText(opens: LocalDateTime, now: LocalDateTime): String =
    if (opens.toLocalDate() == now.toLocalDate()) opens.hm()
    else opens.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) + " " + opens.hm()

private val MONOGRAMS = longArrayOf(0xFF2F5D8A, 0xFFC8322D, 0xFF2F7D3A, 0xFFE8962C, 0xFF7B4FD6, 0xFF0E8C8C, 0xFF8A5A2B, 0xFFD2457F)

/** The colour a brand's sign has, or one picked by its key so each chain keeps its own. */
internal fun brandColour(brand: app.atlas.places.Brand?, key: String?): Color =
    brand?.colour?.let { Color(it) } ?: Color(MONOGRAMS[Math.floorMod((brand?.id ?: key ?: "").hashCode(), MONOGRAMS.size)])

/**
 * A chain's logo on its sign colour (a simple-icons glyph where `:places` has one, else its first letter in the aisle
 * sign's lettering): [round] makes it a disc, as the boards draw some of them, else a soft square.
 */
@Composable
internal fun ShopLogo(brand: app.atlas.places.Brand?, name: String, size: Dp, modifier: Modifier = Modifier, key: String? = null, round: Boolean = false) {
    val bg = brandColour(brand, key ?: name)
    val fg = brand?.textColour?.let { Color(it) } ?: app.atlas.ui.kit.contentColourFor(bg)
    // The boards' marks: 12 px corners (or round), the logo 24 px, a letter in Overpass Black at half the size.
    val shape = if (round) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(minOf(12.dp, size * 0.3f))
    Box(modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        val slug = brand?.logoSlug
        if (slug != null) Symbol("logo-$slug", tint = fg, size = minOf(24.dp, size * 0.6f))
        else androidx.compose.material3.Text(
            name.trim().take(1).uppercase(),
            style = androidx.compose.ui.text.TextStyle(fontFamily = ShopType.overpass, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, fontSize = (size.value * 0.5f).sp),
            color = fg,
        )
    }
}

/** Starts [intent] from [context]; false when no app on the phone takes it (a tablet without a dialler, no browser). */
internal fun startSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

/** A `tel:` intent for a number as OpenStreetMap writes it ("+31 20 624 7766; +31 20 555 0100"): the first one, digits and +; null when it has no digits. */
internal fun dialIntent(number: String): Intent? {
    val digits = number.split(';').first().filter { it.isDigit() || it == '+' }
    return if (digits.count { it.isDigit() } >= 3) Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")) else null
}

/** The web address of a `website` tag as a link to open: http or https only (a tag may say anything); a bare domain gets https. */
internal fun webAddress(tag: String): String? {
    val t = tag.trim()
    if (t.isEmpty() || t.any { it.isWhitespace() }) return null
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(t)?.groupValues?.get(1)?.lowercase()
    return when {
        scheme == null -> "https://$t"
        scheme == "http" || scheme == "https" -> t.takeIf { it.substringAfter("//", "").isNotEmpty() }
        else -> null
    }
}

/**
 * "Now" by the app's clock (like the kit's `rememberNow`) that moves on: it is read again at the turn of every minute and
 * whenever the screen comes back to the front, so "open now", closing times and "ready" are not those of an hour ago.
 */
@Composable
internal fun rememberLiveNow(): LocalDateTime {
    val clock = LocalServices.current.clock
    val owner = LocalLifecycleOwner.current
    var now by remember(clock) { mutableStateOf(LocalDateTime.now(clock)) }
    LaunchedEffect(clock, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = LocalDateTime.now(clock)
                delay(60_000L - clock.millis().mod(60_000L))
            }
        }
    }
    return now
}
