package app.atlas.mapcore

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * The façade's half of "kills are not crashes" (plan-vector D5.10, [ProcessExits]): the app's process exits as Android
 * records them (ActivityManager's history, here Robolectric's) reach the core's start as text, each saying whether it
 * counts as a crash, by the plan's rule: the user's, the system's (the routine trim of cached processes, REASON_OTHER,
 * among them) and the package's reasons do not, nor an uncaught exception of the app's own Kotlin or Java (REASON_CRASH:
 * not the driver's); native crashes, ANRs and initialization failures do, as does a reason a later Android adds; a
 * signal or an unknown end counts only in the foreground or visible. API 29 keeps no records, so it passes none and
 * every marker counts there. The core's half (matching a marker's process among them) is the guard's host test's and
 * FacadeTest's.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessExitsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun exit(pid: Int, at: Long, reason: Int, importance: Int = RunningAppProcessInfo.IMPORTANCE_FOREGROUND): ApplicationExitInfo =
        ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(context.packageName)
            .setPid(pid)
            .setTimestamp(at)
            .setReason(reason)
            .setImportance(importance)
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
            exit(4105, 5_000, ApplicationExitInfo.REASON_OTHER, RunningAppProcessInfo.IMPORTANCE_CACHED),
            exit(4106, 6_000, ApplicationExitInfo.REASON_SIGNALED, RunningAppProcessInfo.IMPORTANCE_CACHED),
            exit(4107, 7_000, ApplicationExitInfo.REASON_SIGNALED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND),
            exit(4108, 8_000, ApplicationExitInfo.REASON_CRASH),
        )
        assertEquals(
            listOf(
                "4101 1000 1 crash-native", "4102 2000 0 user-requested", "4103 3000 0 low-memory", "4104 4000 1 anr",
                "4105 5000 0 other", "4106 6000 0 signaled (cached)", "4107 7000 1 signaled (foreground)",
                "4108 8000 0 crash (an uncaught exception, not the driver's)",
            ),
            ProcessExits.text(context).lines().sorted(),
        )
    }

    @Test
    fun everyReasonCountsOrNotAsThePlanSays() {
        val counted = mapOf(
            ApplicationExitInfo.REASON_CRASH_NATIVE to "crash-native",
            ApplicationExitInfo.REASON_ANR to "anr",
            ApplicationExitInfo.REASON_SIGNALED to "signaled (foreground)",
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "initialization-failure",
            ApplicationExitInfo.REASON_UNKNOWN to "unknown (foreground)",
        )
        val stopped = mapOf(
            // An uncaught exception of the app's own Kotlin or Java: not the driver's, whose crashes are native.
            ApplicationExitInfo.REASON_CRASH to "crash (an uncaught exception, not the driver's)",
            ApplicationExitInfo.REASON_USER_REQUESTED to "user-requested",
            ApplicationExitInfo.REASON_USER_STOPPED to "user-stopped",
            ApplicationExitInfo.REASON_LOW_MEMORY to "low-memory",
            ApplicationExitInfo.REASON_EXIT_SELF to "exit-self",
            ApplicationExitInfo.REASON_DEPENDENCY_DIED to "dependency-died",
            ApplicationExitInfo.REASON_PERMISSION_CHANGE to "permission-change",
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "excessive-resource-usage",
            ApplicationExitInfo.REASON_OTHER to "other",
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
            // Wherever the process was: the routine trim of a cached one (OomAdjuster's "too many cached") among them.
            assertTrue(name, !ProcessExits.counted(reason, RunningAppProcessInfo.IMPORTANCE_CACHED))
        }
        // Every reason Android 14 defines is one or the other, and a reason a later Android adds counts.
        assertEquals(17, counted.size + stopped.size)
        assertTrue(ProcessExits.counted(99))
        assertTrue(ProcessExits.counted(99, RunningAppProcessInfo.IMPORTANCE_CACHED))
        assertEquals("reason-99", ProcessExits.name(99))
    }

    @Test
    fun aSignalOrAnUnknownEndCountsOnlyInTheForegroundOrVisible() {
        // A crash proper is CRASH_NATIVE wherever the process was; a bare signal or an unknown end in the background is
        // the system's kill as likely as not (a low-memory kill whose report did not arrive, the kernel's).
        for (reason in listOf(ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_UNKNOWN)) {
            assertTrue(ProcessExits.counted(reason, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
            assertTrue(ProcessExits.counted(reason, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
            assertTrue(ProcessExits.counted(reason, RunningAppProcessInfo.IMPORTANCE_VISIBLE))
            assertFalse(ProcessExits.counted(reason, RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE))
            assertFalse(ProcessExits.counted(reason, RunningAppProcessInfo.IMPORTANCE_SERVICE))
            assertFalse(ProcessExits.counted(reason, RunningAppProcessInfo.IMPORTANCE_CACHED))
            assertFalse(ProcessExits.counted(reason, RunningAppProcessInfo.IMPORTANCE_GONE))
        }
        assertEquals("signaled (visible)", ProcessExits.name(ApplicationExitInfo.REASON_SIGNALED, RunningAppProcessInfo.IMPORTANCE_VISIBLE))
        assertEquals("unknown (cached)", ProcessExits.name(ApplicationExitInfo.REASON_UNKNOWN, RunningAppProcessInfo.IMPORTANCE_CACHED))
        // A native crash and a hang count wherever the process was; an uncaught exception nowhere.
        for (reason in listOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)) {
            assertTrue(ProcessExits.counted(reason, RunningAppProcessInfo.IMPORTANCE_CACHED))
        }
        for (importance in listOf(RunningAppProcessInfo.IMPORTANCE_FOREGROUND, RunningAppProcessInfo.IMPORTANCE_VISIBLE, RunningAppProcessInfo.IMPORTANCE_CACHED)) {
            assertFalse(ProcessExits.counted(ApplicationExitInfo.REASON_CRASH, importance))
        }
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
        // The same start for every config of the process: a later core knows an earlier one's marker for its own.
        assertEquals(config.processStartMs, CoreConfig.of(context).processStartMs)
    }

    @Test
    fun theGuardsFolderIsTheProcesssOwn() {
        // The main process keeps noBackupFilesDir/vk; another process of the app (K20's ":windows") takes one of its own,
        // so that neither reads the other's live marker as a crash nor deletes it.
        assertEquals(File(context.noBackupFilesDir, "vk").path, CoreConfig.of(context).guardDir)
        assertEquals(File(context.noBackupFilesDir, "vk").path, CoreConfig.guardDir(context, context.packageName))
        assertEquals(File(context.noBackupFilesDir, "vk-windows").path, CoreConfig.guardDir(context, "${context.packageName}:windows"))
        assertEquals(File(context.noBackupFilesDir, "vk-other.process").path, CoreConfig.guardDir(context, "other.process"))
    }
}
