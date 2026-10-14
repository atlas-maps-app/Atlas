package app.atlas.mapview

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot

/**
 * Waits until the map's picture is final: every scene built, the last frame made and drawn, fades and animations
 * over. Scenes build and frames are made off the main thread, which Compose's idling does not see; each round draws
 * the view once, which is what lets the frame loop make the next frame.
 */
fun ComposeContentTestRule.settleMap(state: MapState, timeoutMillis: Long = 90_000) {
    val end = System.currentTimeMillis() + timeoutMillis
    var calm = 0
    while (System.currentTimeMillis() < end) {
        waitForIdle()
        onRoot().captureToImage()
        if (state.controller?.settled == true) {
            // Settled twice in a row: nothing was still on its way.
            if (++calm >= 2) return
        } else {
            calm = 0
        }
        Thread.sleep(40)
    }
    throw AssertionError("the map did not settle in $timeoutMillis ms")
}
