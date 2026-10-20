package app.atlas.mapcore

import java.nio.ByteBuffer

/**
 * What the core asks the map for, on its render thread (plan-vector T9, Appendix D). Both calls must allocate nothing:
 * they run at every paced vsync while the map moves.
 */
interface FrameSource {
    /**
     * Writes the gesture camera for the frame of [vsyncNanos] into [out] (CameraState, through
     * `app.atlas.mapcore.state.CameraWriter`); false when nothing changed, and the core stops asking until
     * [MapCore.requestFrame].
     */
    fun camera(vsyncNanos: Long, out: ByteBuffer): Boolean

    /** Frame [frame] was presented at [nanos] (CLOCK_MONOTONIC); post elsewhere to act on it. */
    fun presented(frame: Long, nanos: Long, complete: Boolean)

    /**
     * The core turned the map off for this session ([reason]: the device lost three times in a row, or not made again
     * after a loss), once; no frame follows. Post elsewhere to act on it.
     */
    fun off(reason: String) {}
}
