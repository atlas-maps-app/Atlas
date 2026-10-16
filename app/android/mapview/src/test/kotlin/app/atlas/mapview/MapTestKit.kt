package app.atlas.mapview

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

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

/**
 * The frame threads of the maps a test shows, waited for once the compose rule inside this one (`RuleChain.outerRule`)
 * has torn the composition down, which closes them: no frame outlives its test. A frame still drawing with Skia while
 * the next test class draws is more than Robolectric's native graphics survive.
 */
class MapFrameThreads : TestRule {
    private val frames = ArrayList<EngineFrames>()

    /** Waits for the frame thread of the map showing [state] at the end of the test. */
    fun track(state: MapState) {
        frames += checkNotNull(state.controller) { "no map shows this state" }.frames
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
            }
            val running = frames.count { !it.awaitStopped(STOP_MS) }
            frames.clear()
            if (running > 0) {
                val e = AssertionError("$running map frame thread(s) still running $STOP_MS ms after the test")
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
            failure?.let { throw it }
        }
    }

    private companion object {
        /** A frame being made stops at its next check once its map is closed: within a second, bar the engine's load. */
        const val STOP_MS = 30_000L
    }
}
