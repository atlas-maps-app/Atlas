package app.atlas.ui.go

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.atlas.services
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The trip, kept alive: a foreground service of type `location` that holds the process while a trip is guided (or Ears
 * only runs), so location, the voice and the vibrations go on with the screen off. It shows the ongoing notification
 * (a Live Update on Android 16+), and while a trip is guided a media session lets a headset press repeat the last
 * instruction. The sessions themselves live in [GoHub]; this service ends when they do.
 *
 * Android allows a location service only to be started while the app is visible ([start] is called from the screen
 * where the trip starts), and Android 17 lets it play audio then, with the screen off, because it holds that right.
 */
class NavigationService : LifecycleService() {
    private var headset: HeadsetControl? = null
    private var headsetFor: NavSession? = null
    private var watching: Job? = null

    /** The trip the headset's media session is bound to (for tests). */
    internal val headsetTrip: NavSession? get() = headsetFor

    private val hub: GoHub get() = applicationContext.services.go

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                hub.stopAll()
                finish()
                return START_NOT_STICKY
            }
            ACTION_REPEAT -> hub.nav.value?.repeat()
            ACTION_MUTE -> hub.setMuted(!hub.muted.value)
        }
        if (!foreground()) return START_NOT_STICKY
        if (watching == null) watching = lifecycleScope.launch { watch() }
        return START_NOT_STICKY
    }

    /** Goes to the foreground with the current notice; false (and the service ends) when Android does not allow it. */
    private fun foreground(): Boolean {
        val notice = notice(hub.nav.value?.view?.value)
        return try {
            ServiceCompat.startForeground(
                this, NavNotification.ID, NavNotification.build(this, notice),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
            true
        } catch (e: Exception) {
            // No location permission, or started from the background: the trip goes on while the screen is on.
            stopSelf()
            false
        }
    }

    /** Follows the sessions: the notification as the trip moves on, the headset while guided, the end when both end. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun watch() {
        val nm = NotificationManagerCompat.from(this)
        combine(hub.nav, hub.ears, hub.muted) { nav, ears, muted -> Triple(nav, ears, muted) }
            .flatMapLatest { (nav, ears, muted) ->
                when {
                    nav != null -> nav.view.map { v -> Triple(nav, notice(v, muted), true) }
                    ears != null -> flowOf(Triple(null, earsNotice(muted), true))
                    else -> flowOf(Triple(null, null, false))
                }
            }
            .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second && a.third == b.third }
            .collect { (nav, notice, active) ->
                if (!active || notice == null) {
                    finish()
                    return@collect
                }
                // One media session per trip: a new trip (started while this one still ran) gets its own, so a press
                // repeats the instruction of the trip being guided, not of one that has ended.
                if (nav != null && (headset == null || headset?.isReleased == true || headsetFor !== nav)) {
                    headset?.release()
                    headsetFor = nav
                    headset = HeadsetControl(this, nav.destinationName ?: getString(app.atlas.R.string.go_destination)) { cmd ->
                        when (cmd) {
                            HeadsetCommand.REPEAT -> nav.repeat()
                            HeadsetCommand.NEXT -> nav.repeat()
                            HeadsetCommand.SUMMARY -> nav.summary()
                        }
                    }
                } else if (nav == null) {
                    headset?.release()
                    headset = null
                    headsetFor = null
                }
                if (canNotify()) runCatching { nm.notify(NavNotification.ID, NavNotification.build(this, notice)) }
                // At most one update a second: the status bar need not follow every fix.
                delay(1_000)
            }
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notice(view: NavView?, muted: Boolean = hub.muted.value): NavNotice {
        val nav = hub.nav.value
        if (view == null || nav == null) return earsNotice(muted)
        return NavNotice.of(this, view, nav.destinationName, hub.units, ZoneId.systemDefault(), muted)
    }

    private fun earsNotice(muted: Boolean) =
        NavNotice(getString(app.atlas.R.string.go_ears_title), getString(app.atlas.R.string.go_ears_notice), null, 0, muted)

    private fun finish() {
        headset?.release()
        headset = null
        headsetFor = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        headset?.release()
        headset = null
        headsetFor = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.atlas.go.START"
        const val ACTION_STOP = "app.atlas.go.STOP"
        const val ACTION_REPEAT = "app.atlas.go.REPEAT"
        const val ACTION_MUTE = "app.atlas.go.MUTE"

        /**
         * Starts the service for the session in the hub; call it from a visible screen. False when Android will not
         * run it (no location permission yet): the trip is then guided while the screen is on.
         */
        fun start(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java).setAction(ACTION_START))
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
