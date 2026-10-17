package app.atlas.mapcore

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.util.ReflectionHelpers

/**
 * The façade's half of "kills are not crashes" (plan-vector D5.10, [ProcessExits]): the app's process exits as Android
 * records them (ActivityManager's history, here Robolectric's) reach the core's start as text, each saying whether it
 * counts as a crash, by the plan's rule: the user's, the system's and the package's reasons do not; crashes, ANRs,
 * signals, initialization failures, unknown and other reasons do, as does a reason a later Android adds. API 29 keeps
 * no records, so it passes none and every marker counts there. The core's half (matching a marker's process among
 * them) is the guard's host test's and FacadeTest's.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessExitsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun exit(pid: Int, at: Long, reason: Int): ApplicationExitInfo =
        ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(context.packageName)
            .setPid(pid)
            .setTimestamp(at)
            .setReason(reason)
            .build()

    private fun record(vararg exits: ApplicationExitInfo) {
        val manager = context.getSystemService(ActivityManager::class.java)
        for (e in exits) shadowOf(manager).addApplicationExitInfo(e)
    }

    @Test
    fun theRecordedExitsReachTheCoreSayingWhetherEachCounts() {
        record(
            exit(4101, 1_000, ApplicationExitInfo.REASON_CRASH_NATIVE),
            exit(4102, 2_000, ApplicationExitInfo.REASON_USER_REQUESTED),
            exit(4103, 3_000, ApplicationExitInfo.REASON_LOW_MEMORY),
            exit(4104, 4_000, ApplicationExitInfo.REASON_ANR),
        )
        assertEquals(
            listOf("4101 1000 1 crash-native", "4102 2000 0 user-requested", "4103 3000 0 low-memory", "4104 4000 1 anr"),
            ProcessExits.text(context).lines().sorted(),
        )
    }

    @Test
    fun everyReasonCountsOrNotAsThePlanSays() {
        val counted = mapOf(
            ApplicationExitInfo.REASON_CRASH_NATIVE to "crash-native",
            ApplicationExitInfo.REASON_CRASH to "crash",
            ApplicationExitInfo.REASON_ANR to "anr",
            ApplicationExitInfo.REASON_SIGNALED to "signaled",
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "initialization-failure",
            ApplicationExitInfo.REASON_UNKNOWN to "unknown",
            ApplicationExitInfo.REASON_OTHER to "other",
        )
        val stopped = mapOf(
            ApplicationExitInfo.REASON_USER_REQUESTED to "user-requested",
            ApplicationExitInfo.REASON_USER_STOPPED to "user-stopped",
            ApplicationExitInfo.REASON_LOW_MEMORY to "low-memory",
            ApplicationExitInfo.REASON_EXIT_SELF to "exit-self",
            ApplicationExitInfo.REASON_DEPENDENCY_DIED to "dependency-died",
            ApplicationExitInfo.REASON_PERMISSION_CHANGE to "permission-change",
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "excessive-resource-usage",
            ApplicationExitInfo.REASON_FREEZER to "freezer",
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to "package-state-change",
            ApplicationExitInfo.REASON_PACKAGE_UPDATED to "package-updated",
        )
        for ((reason, name) in counted) {
            assertTrue(name, ProcessExits.counted(reason))
            assertEquals(name, ProcessExits.name(reason))
        }
        for ((reason, name) in stopped) {
            assertTrue(name, !ProcessExits.counted(reason))
            assertEquals(name, ProcessExits.name(reason))
        }
        // Every reason Android 14 defines is one or the other, and a reason a later Android adds counts.
        assertEquals(17, counted.size + stopped.size)
        assertTrue(ProcessExits.counted(99))
        assertEquals("reason-99", ProcessExits.name(99))
    }

    @Test
    fun noRecordIsKnownAsNone() {
        assertEquals("none", ProcessExits.text(context))
    }

    @Test
    fun api29KeepsNoRecordsSoNoneAreKnown() {
        // API 29, the app's minSdk, has no ApplicationExitInfo: the façade passes no exits, and every marker counts.
        // (SDK_INT set by hand: a second Robolectric SDK would be fetched for this case alone.)
        record(exit(4301, 6_000, ApplicationExitInfo.REASON_USER_REQUESTED))
        val sdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 29)
        try {
            assertEquals("", ProcessExits.text(context))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
        assertEquals("4301 6000 0 user-requested", ProcessExits.text(context))
    }

    @Test
    fun theAppsConfigReadsTheExitsAsEachStartBeginsAndKnowsTheProcessStart() {
        val config = CoreConfig.of(context)
        assertEquals("none", config.exits())
        record(exit(4201, 5_000, ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("4201 5000 1 crash-native", config.exits()) // read again by the start, not when the config was made
        assertTrue(config.processStartMs in 1..System.currentTimeMillis())
    }
}
