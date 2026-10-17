package app.atlas.mapcore

import android.content.Context
import android.os.Build
import java.io.File

/**
 * How the core starts (plan-vector T8, D5.10): where its pipeline cache and its crash guard live, the system build the
 * guard keys a crash to before the driver is known (the core adds its own build ID), how the app's last processes ended
 * and when this one started (the guard reads a marker by them: a process stopped, not crashed, counts for nothing;
 * [ProcessExits]), the validation layer, CI's per-frame log, K6's answer for configuration B, and where to crash on
 * purpose (debug and bench builds, the planted-crash tests; `CP/gpu/Guard.hpp` names them all):
 *  - `<stage>` aborts as the stage begins (`instance`, `facts`, `device`, `cache`, `surface`, `frames`, `profiles`,
 *    `close`), but `pipelines` and `colour` at their first pipeline;
 *  - `pipelines:<name>` and `colour:<name>` as that pipeline or shader module is made (`pipelines:composite`);
 *  - `<stage>:release` as the stage's first thing goes, and `<stage>:release:<kind>` as its first of that kind does
 *    (frame, swapchain, cache, standin, device, instance); in VK bench's 10-bit colour stage only a thing of 10 bits:
 *    `colour:release` as the 10-bit frame or the 10-bit swapchain goes, whichever goes first, never the 8-bit swapchain
 *    (which goes before the stage opens) nor the stage's own pipeline cache; `colour:release:frame` as the 10-bit
 *    frame goes; `colour:release:swapchain` as the 10-bit swapchain goes; `colour:release:cache` as the stage's cache
 *    goes; `pipelines:release:frame` as an 8-bit frame goes; `surface:release:swapchain` as an 8-bit swapchain goes;
 *    `close:release:device` as the device goes at the core's close.
 */
data class CoreConfig(
    val cacheDir: String,
    val guardDir: String,
    val system: String,
    val plant: String = "",
    val validation: Boolean = false,
    val ciLog: Boolean = false,
    val msaa2Close: Boolean = false,
    /** When this process started, ms since the epoch (0: the core takes its own start). */
    val processStartMs: Long = 0,
    /** The app's process exits as [ProcessExits.text] gives them, read as each start begins ("": none known). */
    val exits: () -> String = { "" },
) {
    /** The flags of `Native.create` (`CP/jni/Bridge.cpp`'s core_flag). */
    val flags: Int get() = (if (validation) 1 else 0) or (if (ciLog) 2 else 0) or (if (msaa2Close) 4 else 0)

    companion object {
        /**
         * The app's: `cacheDir` (the pipeline cache), `noBackupFilesDir/vk` (the guard), the build fingerprint, this
         * process's start and its recorded exits. The guard's folder is one process's at a time: the debug and bench
         * builds share it (one package, installed over each other, never side by side; each build's ID keys its own
         * verdicts), and the app runs the core in its main process only. A process of its own that starts a core
         * (K20's `:windows`) takes a folder of its own, or a start there would read the main process's live marker as
         * a crash with no exit recorded.
         */
        fun of(context: Context, plant: String = "", validation: Boolean = false, ciLog: Boolean = false): CoreConfig {
            val app = context.applicationContext ?: context
            return CoreConfig(
                cacheDir = context.cacheDir.path,
                guardDir = File(context.noBackupFilesDir, "vk").path,
                system = Build.FINGERPRINT ?: "unknown",
                plant = plant,
                validation = validation,
                ciLog = ciLog,
                processStartMs = ProcessExits.processStartMs(),
                exits = { ProcessExits.text(app) },
            )
        }
    }
}
