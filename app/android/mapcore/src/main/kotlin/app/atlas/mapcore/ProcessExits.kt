package app.atlas.mapcore

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock

/**
 * How the app's last processes ended, as Android records them (`ApplicationExitInfo`, API 30), for the crash guard
 * (plan-vector D5.10, `CP/gpu/Guard.hpp`). The guard's marker survives a swipe-away, a low-memory kill, a force-stop or
 * the system's routine trim of cached processes just as it survives a crash, so the core looks the marker's process up
 * among these exits (by its id, and an exit after its start): one the user, the system or an update stopped counts for
 * nothing, and so does one an uncaught exception of the app's own Kotlin or Java ended (the guard is for the driver's
 * crashes); one that crashed natively, hung (an ANR: a driver's hang repeats) or left no record at all counts, and so
 * does one signalled or ended for a reason Android does not know while it was in the foreground or visible. API 29
 * keeps no records, and there every marker counts, as before (but one of the running process itself, which the core
 * knows by its id and start).
 *
 * The core stays platform-free: the façade passes this text into its start, one line per exit,
 * "<pid> <ms> <counted 0|1> <reason>" (`gpu::parse_exits`), "none" when Android recorded none, "" on API 29.
 */
object ProcessExits {
    /**
     * One exit: the process's id, when it ended (ms since the epoch), Android's reason (`REASON_*`) and the process's
     * importance as it ended (`RunningAppProcessInfo.IMPORTANCE_*`).
     */
    data class Exit(
        val pid: Int,
        val timestampMs: Long,
        val reason: Int,
        val importance: Int = RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
    )

    /** The app's recorded exits as the core's start takes them ("" on API 29, or when they cannot be read). */
    fun text(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val manager = context.getSystemService(ActivityManager::class.java) ?: return ""
        val infos = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 0) }.getOrNull() ?: return ""
        return format(infos.map { Exit(it.pid, it.timestamp, it.reason, it.importance) })
    }

    /** [exits] as the core's start takes them. */
    fun format(exits: List<Exit>): String =
        if (exits.isEmpty()) {
            "none"
        } else {
            exits.joinToString("\n") {
                "${it.pid} ${it.timestampMs} ${if (counted(it.reason, it.importance)) 1 else 0} ${name(it.reason, it.importance)}"
            }
        }

    /**
     * Whether an exit counts as a crash: not where the user (a swipe-away, a force-stop), the system (low memory, the
     * routine trim of cached and empty processes, REASON_OTHER, which Android documents as "not by problems in apps",
     * the freezer, too much of a resource, a permission's change, a process the app depends on) or the package (updated,
     * its state changed, its own exit) ended it, nor where an uncaught exception of the app's own Kotlin or Java did
     * (REASON_CRASH): the guard is for the driver's native crashes, which end as REASON_CRASH_NATIVE, a signal or an ANR
     * for a hang, and a Kotlin bug during a guarded stage is not the driver's (the core's build ID would not change with
     * its fix, so Vulkan would stay off after it until a reset). A signal or an unknown end counts only while the process
     * was in the foreground or visible ([importance] at most IMPORTANCE_VISIBLE): in the background it is the system's
     * kill as likely as not (a low-memory kill whose report did not arrive, the kernel's), and a driver's crash there
     * repeats in the foreground, where it counts. Every other reason counts, a reason a later Android adds included.
     */
    fun counted(reason: Int, importance: Int = RunningAppProcessInfo.IMPORTANCE_FOREGROUND): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_FREEZER,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        -> false
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_UNKNOWN,
        -> shown(importance)
        else -> true // CRASH_NATIVE, ANR, INITIALIZATION_FAILURE, and any newer reason
    }

    /**
     * A reason's name in the guard's lines ("crash-native", "user-requested", …); a signal's and an unknown end's with
     * where the process was ("signaled (cached)", "unknown (visible)"), which decides whether they count; an uncaught
     * exception's saying whose it is, so the guard's line reads "vk guard: <stage> marker after crash (an uncaught
     * exception, not the driver's), not counted".
     */
    fun name(reason: Int, importance: Int = RunningAppProcessInfo.IMPORTANCE_FOREGROUND): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown (${where(importance)})"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled (${where(importance)})"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "crash (an uncaught exception, not the driver's)"
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

    /** Whether a process of [importance] was in the foreground or visible (Android's numbers grow as it recedes). */
    private fun shown(importance: Int): Boolean = importance <= RunningAppProcessInfo.IMPORTANCE_VISIBLE

    /** Where a process of [importance] was, in a word or two. */
    private fun where(importance: Int): String = when {
        importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
        importance <= RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance <= RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        importance < RunningAppProcessInfo.IMPORTANCE_CACHED -> "background"
        importance < RunningAppProcessInfo.IMPORTANCE_GONE -> "cached"
        else -> "gone"
    }

    /**
     * When this process started, in ms since the epoch: the marker records it, so that its process's exit is the first
     * after it, and a later core of this process knows the marker for its own. Read once a process (two clocks, which
     * would otherwise give a few milliseconds' difference from one read to the next).
     */
    fun processStartMs(): Long = start

    private val start: Long by lazy {
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
    }
}
