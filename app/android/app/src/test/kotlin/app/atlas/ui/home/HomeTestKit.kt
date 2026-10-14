package app.atlas.ui.home

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot

/**
 * Waits until the screen stops changing, three captures in a row alike: the map's tiles and scenes are built off the
 * main thread, which `waitForIdle` does not wait for.
 */
internal fun ComposeContentTestRule.settleScreen(timeoutMs: Long = 120_000) {
    val end = System.currentTimeMillis() + timeoutMs
    var last: IntArray? = null
    var same = 0
    while (System.currentTimeMillis() < end) {
        waitForIdle()
        val bmp = onRoot().captureToImage().asAndroidBitmap()
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        if (last != null && px.contentEquals(last)) {
            if (++same >= 3) return
        } else {
            same = 0
        }
        last = px
        Thread.sleep(400)
    }
    throw AssertionError("the screen did not settle in $timeoutMs ms")
}
