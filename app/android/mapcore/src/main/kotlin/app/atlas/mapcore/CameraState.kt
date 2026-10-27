package app.atlas.mapcore

import app.atlas.mapcore.state.CameraWriter
import java.nio.ByteBuffer

/**
 * The gesture camera's buffer (plan-vector T9, Appendix D): CameraState version 2, 544 bytes, made once per surface and
 * handed to the core, which passes it back to [FrameSource.camera] at every paced vsync. The layout is defined once in
 * `CP/style/CameraState.hpp`; [CameraWriter] writes it with absolute puts, so a frame allocates nothing.
 */
class CameraState {
    /** The direct, little-endian buffer the core reads. */
    val buffer: ByteBuffer = CameraWriter.allocate()

    private var sequence = 0L

    /**
     * Writes a whole camera: the view (target, bearing, the map's pitch, distance, principal point in dp), the frame
     * (pixels, dp, density) and the flags; the pack and revisions stay as last written.
     */
    fun write(
        out: ByteBuffer,
        flags: Int,
        latitude: Double,
        longitude: Double,
        bearing: Double,
        pitch: Double,
        distance: Double,
        principalXDp: Double,
        principalYDp: Double,
        widthPx: Int,
        heightPx: Int,
        density: Double,
    ) {
        CameraWriter.header(out, ++sequence, flags)
        CameraWriter.view(out, latitude, longitude, bearing, pitch, distance, principalXDp, principalYDp)
        CameraWriter.frame(out, widthPx, heightPx, (widthPx / density).toInt(), (heightPx / density).toInt(), density)
    }
}
