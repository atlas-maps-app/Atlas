package app.atlas.ui.go

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.atlas.R
import app.atlas.guide.GuideSettings
import app.atlas.guide.Simulator
import app.atlas.model.RouteRequest
import app.atlas.services.Units
import java.time.ZoneId
import app.atlas.model.Mode
import app.atlas.model.Turn
import app.atlas.services
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The navigation service's life: it starts only with a location permission, goes to the foreground with the guidance
 * notification while a trip (or Ears only) runs, answers the notification's buttons, and ends with the trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val hub get() = app.services.go

    @After
    fun tearDown() = hub.stopAll()

    private fun startTrip(): NavSession {
        val route = GoFixtures.fixture(Mode.BIKE)!!
        val req = RouteRequest(Mode.BIKE, listOf(route.points.first(), route.points.last()))
        val s = hub.startNav(app, req, route, "Amsterdam Centraal", GuideSettings(mode = Mode.BIKE), demo = true)
        // Hold the demo feed: the test drives.
        s.start(emptyFlow())
        return s
    }

    @Test
    fun itStartsOnlyWithALocationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(NavigationService.start(app))
        assertNull(shadowOf(app).nextStartedService)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(NavigationService.start(app))
        val started = shadowOf(app).nextStartedService
        assertEquals(NavigationService::class.java.name, started.component?.className)
        assertEquals(NavigationService.ACTION_START, started.action)
    }

    @Test
    fun itGoesToTheForegroundWithTheTripAndEndsWithIt() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        val session = startTrip()
        val controller = Robolectric.buildService(NavigationService::class.java, Intent(app, NavigationService::class.java).setAction(NavigationService.ACTION_START))
        val service = controller.create().startCommand(0, 1).get()
        val shadow = shadowOf(service)
        assertFalse(shadow.isForegroundStopped)
        val notification = shadow.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(NavNotification.ID, shadow.lastForegroundNotificationId)
        assertEquals(NavNotification.CHANNEL, notification.channelId)
        // Repeat, mute and end on the notification.
        assertEquals(3, notification.actions.size)
        assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel(NavNotification.CHANNEL))

        // The notification's Mute button mutes the voice; End stops the trip and the service.
        controller.withIntent(Intent(app, NavigationService::class.java).setAction(NavigationService.ACTION_MUTE)).startCommand(0, 2)
        assertTrue(hub.muted.value)
        controller.withIntent(Intent(app, NavigationService::class.java).setAction(NavigationService.ACTION_STOP)).startCommand(0, 3)
        ShadowLooper.idleMainLooper()
        assertTrue(session.finished)
        assertNull(hub.nav.value)
        assertTrue(shadow.isStoppedBySelf)
        hub.setMuted(false)
    }

    @Test
    fun aNewTripGetsItsOwnHeadsetSessionNotTheOldOnes() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        val first = startTrip()
        val controller = Robolectric.buildService(NavigationService::class.java, Intent(app, NavigationService::class.java).setAction(NavigationService.ACTION_START))
        val service = controller.create().startCommand(0, 1).get()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(first, service.headsetTrip)
        // A second trip starts while the first is guided: a headset press must reach the second.
        val second = startTrip()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertTrue(first !== second)
        assertEquals(second, service.headsetTrip)
        hub.stopNav()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertNull(service.headsetTrip)
    }

    @Test
    fun aNewTripStartsWithTheVoiceOnAndASimulatedOneIsNoPlaceYouWent() {
        hub.setMuted(true)
        startTrip()
        assertFalse("a mute left from before must not silence the next trip", hub.muted.value)
        ShadowLooper.idleMainLooper()
        Thread.sleep(300)
        assertTrue(kotlinx.coroutines.runBlocking { hub.store.recent.first() }.isEmpty())
    }

    @Test
    fun theNoticeSaysTheNextTurnAndTheArrival() {
        val session = startTrip()
        // Before the first fix (startNav's demo feed may already have sent one before the test held it).
        val notice0 = NavNotice.of(app, session.view.value.copy(state = null), "Amsterdam Centraal", Units.METRIC, ZoneId.of("Europe/Amsterdam"), muted = false)
        assertEquals(app.getString(R.string.go_notify_starting), notice0.title)
        Simulator(session.view.value.route).fixes().take(15).forEach(session::onFix)
        val v = session.view.value
        val n = NavNotice.of(app, v, "Amsterdam Centraal", Units.METRIC, ZoneId.of("Europe/Amsterdam"), muted = true)
        assertTrue(n.title, n.title.startsWith(NavNotice.arrowChar(v.state!!.next!!.maneuver.turn)))
        assertTrue(n.text, "Amsterdam Centraal" in n.text)
        assertTrue(n.chip!!.endsWith("m"))
        assertTrue(n.progress in 1..999)
        assertTrue(n.muted)
        assertEquals("↱", NavNotice.arrowChar(Turn.RIGHT))
        assertEquals("↰", NavNotice.arrowChar(Turn.LEFT))
    }

    @Test
    fun earsOnlyKeepsTheServiceToo() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        hub.startEars(app)
        val controller = Robolectric.buildService(NavigationService::class.java, Intent(app, NavigationService::class.java).setAction(NavigationService.ACTION_START))
        val service = controller.create().startCommand(0, 1).get()
        val n = shadowOf(service).lastForegroundNotification
        assertEquals(app.getString(R.string.go_ears_title), n.extras.getString(android.app.Notification.EXTRA_TITLE))
        hub.stopEars()
        // The service follows the sessions, updating at most once a second: let a few seconds pass.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
