package app.atlas.ui.go

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import app.atlas.MainActivity
import app.atlas.R
import app.atlas.model.Turn
import app.atlas.services.Units
import java.time.ZoneId

/** What the guidance notification says: the next turn and its distance, the arrival and what is left, how far along. */
data class NavNotice(
    val title: String,
    val text: String,
    /** The status-bar chip: the distance to the next turn ("120 m"), at most 7 characters. */
    val chip: String?,
    /** Progress along the route, 0..1000. */
    val progress: Int,
    val muted: Boolean,
) {
    companion object {
        /** The notice for [view] in [units], arrival times in [zone]; [arrived], [rerouting] and the instruction as said. */
        fun of(context: Context, view: NavView, destination: String?, units: Units, zone: ZoneId, muted: Boolean): NavNotice {
            val s = view.state
            val to = destination ?: context.getString(R.string.go_destination)
            if (s == null) return NavNotice(context.getString(R.string.go_notify_starting), to, null, 0, muted)
            if (view.arrived) return NavNotice(context.getString(R.string.go_notify_arrived), to, null, 1000, muted)
            val arrow = s.next?.maneuver?.turn?.let(::arrowChar) ?: "↑"
            val title = when {
                view.rerouting -> context.getString(R.string.go_notify_rerouting)
                s.offRoute -> context.getString(R.string.go_off_route_title)
                else -> "$arrow ${view.instruction ?: to}"
            }
            val dist = GoFormat.distance(s.distanceToManeuver, units).toString()
            val text = context.getString(R.string.go_notify_text, GoFormat.clock(s.eta, zone), GoFormat.distance(s.remaining, units).toString(), to)
            return NavNotice(title, text, dist.takeIf { it.length <= 7 }, (s.progress * 1000).toInt().coerceIn(0, 1000), muted)
        }

        /** The arrow that leads the title: the turn as one character. */
        fun arrowChar(turn: Turn): String = when (turn) {
            Turn.STRAIGHT -> "↑"
            Turn.SLIGHT_RIGHT -> "↗"
            Turn.RIGHT, Turn.SHARP_RIGHT -> "↱"
            Turn.SLIGHT_LEFT -> "↖"
            Turn.LEFT, Turn.SHARP_LEFT -> "↰"
            Turn.U_TURN -> "↶"
        }
    }
}

/**
 * The ongoing notification of a guided trip (and of Ears only): on Android 16 and later a promotable Live Update in
 * `Notification.ProgressStyle`, below that the classic progress bar; with Repeat, Mute and End. Tapping it opens the app.
 */
object NavNotification {
    const val CHANNEL = "go_guidance"
    const val ID = 4201

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, context.getString(R.string.go_notify_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.go_notify_channel_text)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    fun build(context: Context, notice: NavNotice): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val repeat = action(context, NavigationService.ACTION_REPEAT, 1)
        val mute = action(context, NavigationService.ACTION_MUTE, 2)
        val end = action(context, NavigationService.ACTION_STOP, 3)
        val muteLabel = context.getString(if (notice.muted) R.string.go_action_unmute else R.string.go_action_mute)
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000).setColor(GO_BLUE)))
                .setProgress(notice.progress)
                .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.go_navigation))
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.go_navigation)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setStyle(style)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.go_repeat), context.getString(R.string.go_action_repeat), repeat).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.go_mute), muteLabel, mute).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.go_stop), context.getString(R.string.go_action_end), end).build())
            notice.chip?.let { b.setShortCriticalText(it) }
            // Asks for promotion to a Live Update (the 36.1 builder call, by its extra, so it reads on 36.0 too).
            b.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            return b.build()
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.go_navigation)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setProgress(1000, notice.progress, false)
            .addAction(R.drawable.go_repeat, context.getString(R.string.go_action_repeat), repeat)
            .addAction(R.drawable.go_mute, muteLabel, mute)
            .addAction(R.drawable.go_stop, context.getString(R.string.go_action_end), end)
            .build()
    }

    private fun action(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context, code,
            Intent(context, NavigationService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`. */
    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private val GO_BLUE = Color.rgb(0x2E, 0x5B, 0xFF)
}
