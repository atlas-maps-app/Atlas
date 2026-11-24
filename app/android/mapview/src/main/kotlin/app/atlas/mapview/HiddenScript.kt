package app.atlas.mapview

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * CI's hidden pass with the window kept (ci/smoke.sh's `hidden_kept`; debug and bench builds, MainActivity's
 * `atlas.vk.script hidden`). On the emulator the home key's stop destroys the surface before the core hears it is
 * hidden, so that pass draws no frame whatever the core's hiding does (the review of `hidden`, part 2b's point 4). Here
 * the map hides the core itself, as its window's ON_STOP does ([MapController.hidden]), while the activity stays
 * resumed and its surface kept, once its first picture is final; smoke.sh swipes it meanwhile, which asks for frames,
 * and none may come. After [HIDDEN_MS] the map is shown again, as at ON_START. `vk script` lines mark each step with
 * the camera's target, the one the swipe left among them, which the core's first frame back (its `vk hidden: back`
 * line) must be at.
 */
internal object HiddenScript {
    const val NAME = "hidden"

    /** How long the core is hidden: the swipe and its fling, and the emulator's frames they would draw, well within. */
    const val HIDDEN_MS = 20_000L
    private const val WAIT_MS = 300_000L
    private const val SETTLE_MS = 2_000L

    suspend fun run(controller: MapController, state: MapState) {
        val log = { line: String -> Log.i(MapEngine.TAG, line) }
        val ready = withTimeoutOrNull(WAIT_MS) {
            while (controller.core == null || state.status !is MapStatus.Ready || !controller.settled) delay(250)
        }
        if (ready == null) {
            log("vk script: hidden: the map's picture was not final within ${WAIT_MS / 1000} s")
            log("vk script done")
            return
        }
        delay(SETTLE_MS)
        log("vk script: hidden for ${HIDDEN_MS / 1000} s, the window kept, the camera at ${at(state.camera)}")
        controller.hidden = true
        delay(HIDDEN_MS)
        log("vk script: shown again, the swipe left the camera at ${at(state.camera)}")
        controller.hidden = false
        delay(SETTLE_MS)
        log("vk script done")
    }

    /** A camera's target, as the core's `vk hidden: back` line writes its own. */
    private fun at(c: CameraPosition): String = String.format(Locale.ROOT, "%.6f,%.6f", c.target.lat, c.target.lon)
}
