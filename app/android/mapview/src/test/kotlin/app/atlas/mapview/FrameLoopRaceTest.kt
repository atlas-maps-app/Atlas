package app.atlas.mapview

import app.atlas.engine.ApproximateMeasurer
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * The frame loop never loses a request: a camera submitted while a frame is being made is always drawn afterwards,
 * even when nothing else asks for a frame.
 */
class FrameLoopRaceTest {
    private val phone = Viewport(824, 1830, 2f)

    @Test
    fun aSubmitDuringAFrameIsNeverLost() {
        val pipeline = ScenePipeline(SceneCache(1L shl 20), onChange = {})
        lateinit var loop: FrameLoop
        // The view draws each frame as soon as it is made, so the loop runs flat out.
        loop = FrameLoop(ApproximateMeasurer, pipeline, onFrame = { loop.latest?.let { loop.drawn(it.number) } })
        try {
            val look = Looks.resolve(MapStyle(theme = Theme.DAY), Fixtures.afternoon, Fixtures.westermarkt)
            val style = Looks.frameStyle(MapStyle(), 15.0)
            val inputs = Array(64) { k ->
                RenderInput(CameraOps.camera(CameraPosition(Fixtures.westermarkt, 15.0 + k / 64.0), phone), look, style)
            }
            val last = AtomicReference<RenderInput>()
            val end = System.currentTimeMillis() + 2_000
            var round = 0
            while (System.currentTimeMillis() < end) {
                // A burst of requests, the last of which must be drawn.
                val burst = 1 + round % 7
                for (b in 0 until burst) {
                    val i = inputs[(round * 7 + b) % inputs.size]
                    last.set(i)
                    loop.submit(i)
                    if (b % 2 == 1) Thread.yield()
                }
                val deadline = System.currentTimeMillis() + 1_000
                while (loop.latest?.input !== last.get() || loop.busy) {
                    if (System.currentTimeMillis() > deadline) break
                    Thread.onSpinWait()
                }
                assertSame("round $round: the last request was never drawn", last.get(), loop.latest?.input)
                round++
            }
        } finally {
            loop.close()
            pipeline.close()
        }
    }
}
