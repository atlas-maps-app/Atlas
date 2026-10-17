package app.atlas.mapcore

import android.content.Context
import android.os.Build
import java.io.File

/**
 * How the core starts (plan-vector T8, D5.10): where its pipeline cache and its crash guard live, the system build the
 * guard keys a crash to before the driver is known, a stage to crash at on purpose (debug and bench builds, the
 * planted-crash test), the validation layer, CI's per-frame log, and K6's answer for configuration B.
 */
data class CoreConfig(
    val cacheDir: String,
    val guardDir: String,
    val system: String,
    val plant: String = "",
    val validation: Boolean = false,
    val ciLog: Boolean = false,
    val msaa2Close: Boolean = false,
) {
    /** The flags of `Native.create` (`CP/jni/Bridge.cpp`'s core_flag). */
    val flags: Int get() = (if (validation) 1 else 0) or (if (ciLog) 2 else 0) or (if (msaa2Close) 4 else 0)

    companion object {
        /** The app's: `cacheDir` (the pipeline cache), `noBackupFilesDir/vk` (the guard), the build fingerprint. */
        fun of(context: Context, plant: String = "", validation: Boolean = false, ciLog: Boolean = false): CoreConfig = CoreConfig(
            cacheDir = context.cacheDir.path,
            guardDir = File(context.noBackupFilesDir, "vk").path,
            system = Build.FINGERPRINT ?: "unknown",
            plant = plant,
            validation = validation,
            ciLog = ciLog,
        )
    }
}
