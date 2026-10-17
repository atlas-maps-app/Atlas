package app.atlas.mapcore

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock

/**
 * How the app's last processes ended, as Android records them (`ApplicationExitInfo`, API 30), for the crash guard
 * (plan-vector D5.10, `CP/gpu/Guard.hpp`). The guard's marker survives a swipe-away, a low-memory kill or a force-stop
 * just as it survives a crash, so the core looks the marker's process up among these exits (by its id, and an exit after
 * its start): one the user, the system or an update stopped counts for nothing; one that crashed, hung (an ANR: a
 * driver's hang repeats), was signalled, ended for a reason Android does not give, or left no record at all counts.
 * API 29 keeps no records, and there every marker counts, as before.
 *
 * The core stays platform-free: the façade passes this text into its start, one line per exit,
 * "<pid> <ms> <counted 0|1> <reason>" (`gpu::parse_exits`), "none" when Android recorded none, "" on API 29.
 */
object ProcessExits {
    /** One exit: the process's id, when it ended (ms since the epoch) and Android's reason (`REASON_*`). */
    data class Exit(val pid: Int, val timestampMs: Long, val reason: Int)

    /** The app's recorded exits as the core's start takes them ("" on API 29, or when they cannot be read). */
    fun text(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val manager = context.getSystemService(ActivityManager::class.java) ?: return ""
        val infos = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 0) }.getOrNull() ?: return ""
        return format(infos.map { Exit(it.pid, it.timestamp, it.reason) })
    }

    /** [exits] as the core's start takes them. */
    fun format(exits: List<Exit>): String =
        if (exits.isEmpty()) "none" else exits.joinToString("\n") { "${it.pid} ${it.timestampMs} ${if (counted(it.reason)) 1 else 0} ${name(it.reason)}" }

    /**
     * Whether an exit counts as a crash: not where the user (a swipe-away, a force-stop), the system (low memory, the
     * freezer, too much of a resource, a permission's change, a process the app depends on) or the package (updated,
     * its state changed, its own exit) ended it; every other reason does, a reason a later Android adds included.
     */
    fun counted(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_FREEZER,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        -> false
        else -> true // CRASH_NATIVE, CRASH, ANR, SIGNALED, INITIALIZATION_FAILURE, UNKNOWN, OTHER, and any newer reason
    }

    /** A reason's name in the guard's lines ("crash-native", "user-requested", …). */
    fun name(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash-native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package-state-change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package-updated"
        else -> "reason-$reason"
    }

    /** When this process started, in ms since the epoch: the marker records it, so that its process's exit is the first after it. */
    fun processStartMs(): Long = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
}
