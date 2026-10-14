package app.atlas.testing

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File

/** Where screenshot tests write their PNGs: the module's `outputs/roborazzi` folder (CI keeps it). */
val shotsDir: File = File(System.getProperty("atlas.shots") ?: "build/outputs/roborazzi").also { it.mkdirs() }

/** Renders [content] and writes it to `<shotsDir>/<name>.png`; returns the file. */
fun shot(name: String, content: @Composable () -> Unit): File {
    val file = File(shotsDir, "$name.png")
    captureRoboImage(file.absolutePath, content = content)
    return file
}

/**
 * Waits until the screen has settled: Compose idle and no "Looking…" line left (the kit's loading state), so a
 * screenshot shows the answer rather than the wait. Background work (tile decoding, the place store) runs off the
 * main thread, which `waitForIdle` alone does not wait for.
 */
fun androidx.compose.ui.test.junit4.ComposeContentTestRule.settle(timeoutMillis: Long = 10_000, loading: String = "Looking…") {
    val end = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < end) {
        waitForIdle()
        if (onAllNodes(androidx.compose.ui.test.hasText(loading, substring = true)).fetchSemanticsNodes().isEmpty()) break
        Thread.sleep(100)
    }
    waitForIdle()
}
