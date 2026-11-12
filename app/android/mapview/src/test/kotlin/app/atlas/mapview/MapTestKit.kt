package app.atlas.mapview

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot

/**
 * Waits until the map's picture is final: the paper and the overlays drawn, fades and animations over (MapController's
 * settled; the JVM's tests run no core). Each round draws the view once.
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
