package app.atlas.mapview.vk

import kotlin.math.max

/**
 * The paper over the core at the start (ENGINE.md step 3): until the core's first picture is complete the map shows the
 * paper alone (no frame, no "Loading"), which then fades out over [FADE_MS], and nothing covers the core's picture
 * again. The core draws every place from then on, with its own cover where it has no tiles yet. Until 5 November the
 * transitional rule faded today's map (the renderer's port on the canvas) in and out over it per place; today's map
 * has left the phone.
 *
 * The fade waits while the core's picture is not complete, so the paper never shows a picture on its way. Main thread
 * only.
 */
internal class StartCover {
    /** How much of the paper covers the core: 1 at the start, 0 once the core's first picture shows alone. */
    var canvas: Float = 1f
        private set

    /** Whether the core's first picture has been shown (the start's paper gone). */
    val started: Boolean get() = canvas <= 0f

    private var lastMs = -1L
    private var fading = false

    /**
     * The cover at [nowMs], [coreReady] saying the core's picture is complete for the camera: true while the paper is
     * still there (frames are due).
     */
    fun update(nowMs: Long, coreReady: Boolean): Boolean {
        if (canvas <= 0f) return false
        val dt = if (lastMs < 0) 0L else max(0L, nowMs - lastMs)
        lastMs = nowMs
        // the paper stays as it is until the picture exists; a fade under way goes on from where it stopped
        if (!coreReady) return true
        // the fade's first step starts now, not at the last update before the picture came
        val step = if (fading) dt else 0L
        fading = true
        canvas = max(0f, canvas - step.toFloat() / FADE_MS)
        return canvas > 0f
    }

    companion object {
        /** The paper's fade, ms (plan-vector §0.1's cross-fade). */
        const val FADE_MS = 300L
    }
}
