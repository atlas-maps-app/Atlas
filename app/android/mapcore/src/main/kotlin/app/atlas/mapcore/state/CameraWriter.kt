package app.atlas.mapcore.state

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The gesture camera of one frame (plan-vector T9, Appendix D), written into the reused direct buffer the core hands
 * `FrameSource.camera` at each paced vsync: CameraState, version 1, 512 bytes, little-endian (`CP/style/CameraState.hpp`
 * defines it; [FIELDS] and the flag and profile constants mirror its tables, and `MT/style/LayoutTest.kt` holds them
 * equal). The core refuses a state outside the ranges CameraState.hpp lists (a target off the globe, a pitch outside
 * 0-90 degrees, dp sizes that are not the px sizes over the density, shapes far off the frame).
 *
 * Only absolute puts of primitives on the caller's buffer: nothing here allocates, so the render thread's upcall stays
 * allocation-free (K7 counts it on the emulator). The parts can be written separately, each only when it changed:
 * [header] every frame, [view] and [frame] when the camera or the surface moved, the rest when they changed.
 */
object CameraWriter {
    /** The layout's version (`camera_state_version`). */
    const val VERSION = 1

    /** The state's size in bytes. */
    const val SIZE = 512

    /** The most keep-out shapes a frame carries. */
    const val MAX_KEEP_OUTS = 16

    // The flag bits.
    /** The camera rests: the frame snaps to the SVG's precision (D4). */
    const val REST = 1

    /** Engine mode: every per-camera rule of the renders, the radius cut included (the likeness cases). */
    const val ENGINE_MODE = 2

    /** The radius cut alone (D9). */
    const val RADIUS_CUT = 4

    /** Follow mode: the camera follows the location. */
    const val FOLLOW = 8

    // The profiles (whose rules draw the frame): the active pack's rhead profile plus one, and none without a pack.
    const val PROFILE_NONE = 0
    const val PROFILE_LIVING = 1
    const val PROFILE_REGION = 2
    const val PROFILE_WILLIAMSBURG = 3
    const val PROFILE_WORLD_BASE = 4

    const val VERSION_AT = 0
    const val FLAGS_AT = 4
    const val SEQUENCE_AT = 8
    const val LATITUDE_AT = 16
    const val LONGITUDE_AT = 24
    const val BEARING_AT = 32
    const val PITCH_AT = 40
    const val DISTANCE_AT = 48
    const val PRINCIPAL_X_AT = 56
    const val PRINCIPAL_Y_AT = 64
    const val DENSITY_AT = 72
    const val WIDTH_PX_AT = 80
    const val HEIGHT_PX_AT = 84
    const val WIDTH_DP_AT = 88
    const val HEIGHT_DP_AT = 92
    const val PACK_ID_AT = 96
    const val PROFILE_AT = 104
    const val THEME_REVISION_AT = 108
    const val LABELS_REVISION_AT = 112
    const val OVERLAYS_REVISION_AT = 116
    const val KEEP_OUT_COUNT_AT = 120
    const val RESERVED_AT = 124
    const val KEEP_OUT_AT = 128

    /** One keep-out shape's bytes: its box and radius (floats, dp), blur (sixteenths of a dp), saturation (hundredths), flags. */
    const val KEEP_OUT_SIZE = 24

    /** The layout as (name, offset, size), named as the core's table names its fields (`CP/style/Layouts.cpp`). */
    val FIELDS: List<Triple<String, Int, Int>> = listOf(
        Triple("version", VERSION_AT, 4), Triple("flags", FLAGS_AT, 4), Triple("sequence", SEQUENCE_AT, 8),
        Triple("latitude", LATITUDE_AT, 8), Triple("longitude", LONGITUDE_AT, 8), Triple("bearing", BEARING_AT, 8),
        Triple("pitch", PITCH_AT, 8), Triple("distance", DISTANCE_AT, 8), Triple("principal_x", PRINCIPAL_X_AT, 8),
        Triple("principal_y", PRINCIPAL_Y_AT, 8), Triple("density", DENSITY_AT, 8), Triple("width_px", WIDTH_PX_AT, 4),
        Triple("height_px", HEIGHT_PX_AT, 4), Triple("width_dp", WIDTH_DP_AT, 4), Triple("height_dp", HEIGHT_DP_AT, 4),
        Triple("pack_id", PACK_ID_AT, 8), Triple("profile", PROFILE_AT, 4), Triple("theme_revision", THEME_REVISION_AT, 4),
        Triple("labels_revision", LABELS_REVISION_AT, 4), Triple("overlays_revision", OVERLAYS_REVISION_AT, 4),
        Triple("keep_out_count", KEEP_OUT_COUNT_AT, 4), Triple("reserved", RESERVED_AT, 4),
        Triple("keep_out", KEEP_OUT_AT, KEEP_OUT_SIZE * MAX_KEEP_OUTS),
        Triple("keep_out.left", KEEP_OUT_AT, 4), Triple("keep_out.top", KEEP_OUT_AT + 4, 4), Triple("keep_out.right", KEEP_OUT_AT + 8, 4),
        Triple("keep_out.bottom", KEEP_OUT_AT + 12, 4), Triple("keep_out.radius", KEEP_OUT_AT + 16, 4),
        Triple("keep_out.blur16", KEEP_OUT_AT + 20, 2), Triple("keep_out.saturation", KEEP_OUT_AT + 22, 1),
        Triple("keep_out.flags", KEEP_OUT_AT + 23, 1), Triple("keep_out[1]", KEEP_OUT_AT + KEEP_OUT_SIZE, KEEP_OUT_SIZE),
    )

    /** A direct, little-endian buffer for one camera state, all zeros (made once, then reused for every frame). */
    fun allocate(): ByteBuffer = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.LITTLE_ENDIAN)

    /** The version, the [flags] (REST, ENGINE_MODE, RADIUS_CUT, FOLLOW) and the frame's [sequence] number. */
    fun header(out: ByteBuffer, sequence: Long, flags: Int) {
        check(out)
        out.putInt(VERSION_AT, VERSION)
        out.putInt(FLAGS_AT, flags)
        out.putLong(SEQUENCE_AT, sequence)
        out.putInt(RESERVED_AT, 0)
    }

    /**
     * The view: the target ([latitude], [longitude], degrees), the [bearing] (degrees clockwise from north), the map's
     * [pitch] (degrees from straight down), the eye's [distance] from the target (metres) and the principal point
     * ([principalXDp], [principalYDp], dp from the top left).
     */
    fun view(out: ByteBuffer, latitude: Double, longitude: Double, bearing: Double, pitch: Double, distance: Double, principalXDp: Double, principalYDp: Double) {
        check(out)
        out.putDouble(LATITUDE_AT, latitude)
        out.putDouble(LONGITUDE_AT, longitude)
        out.putDouble(BEARING_AT, bearing)
        out.putDouble(PITCH_AT, pitch)
        out.putDouble(DISTANCE_AT, distance)
        out.putDouble(PRINCIPAL_X_AT, principalXDp)
        out.putDouble(PRINCIPAL_Y_AT, principalYDp)
    }

    /** The surface: its size in px and in dp, and the [density] (px per dp). */
    fun frame(out: ByteBuffer, widthPx: Int, heightPx: Int, widthDp: Int, heightDp: Int, density: Double) {
        check(out)
        out.putInt(WIDTH_PX_AT, widthPx)
        out.putInt(HEIGHT_PX_AT, heightPx)
        out.putInt(WIDTH_DP_AT, widthDp)
        out.putInt(HEIGHT_DP_AT, heightDp)
        out.putDouble(DENSITY_AT, density)
    }

    /** The active pack ([packId], as `packs()` named it) and its [profile] (PROFILE_…). */
    fun pack(out: ByteBuffer, packId: Long, profile: Int) {
        check(out)
        out.putLong(PACK_ID_AT, packId)
        out.putInt(PROFILE_AT, profile)
    }

    /** The revisions of the theme, the labels and the overlays the app last handed the core. */
    fun revisions(out: ByteBuffer, theme: Int, labels: Int, overlays: Int) {
        check(out)
        out.putInt(THEME_REVISION_AT, theme)
        out.putInt(LABELS_REVISION_AT, labels)
        out.putInt(OVERLAYS_REVISION_AT, overlays)
    }

    /** How many keep-out shapes are in use ([count], at most [MAX_KEEP_OUTS]); the slots past them are cleared. */
    fun keepOuts(out: ByteBuffer, count: Int) {
        check(out)
        require(count in 0..MAX_KEEP_OUTS) { "at most $MAX_KEEP_OUTS keep-out shapes" }
        out.putInt(KEEP_OUT_COUNT_AT, count)
        var o = KEEP_OUT_AT + count * KEEP_OUT_SIZE
        val end = KEEP_OUT_AT + MAX_KEEP_OUTS * KEEP_OUT_SIZE
        while (o < end) {
            out.putLong(o, 0L)
            o += 8
        }
    }

    /**
     * Keep-out shape [index]: a box in dp ([left], [top], [right], [bottom]) with its corner [radiusDp], frosted under it
     * ([frosted]) with a blur of [blurDp] and a [saturation] (1.8 for the home's pills), or only kept clear.
     */
    fun keepOut(out: ByteBuffer, index: Int, left: Float, top: Float, right: Float, bottom: Float, radiusDp: Float, blurDp: Float, saturation: Float, frosted: Boolean) {
        check(out)
        require(index in 0 until MAX_KEEP_OUTS) { "keep-out shape $index" }
        val o = KEEP_OUT_AT + index * KEEP_OUT_SIZE
        out.putFloat(o, left)
        out.putFloat(o + 4, top)
        out.putFloat(o + 8, right)
        out.putFloat(o + 12, bottom)
        out.putFloat(o + 16, radiusDp)
        out.putShort(o + 20, (blurDp * 16f).roundToInt().coerceIn(0, 65535).toShort())
        out.put(o + 22, (saturation * 100f).roundToInt().coerceIn(0, 255).toByte())
        out.put(o + 23, (if (frosted) 1 else 0).toByte())
    }

    private fun check(out: ByteBuffer) {
        require(out.capacity() >= SIZE && out.order() == ByteOrder.LITTLE_ENDIAN) { "a little-endian buffer of $SIZE bytes is needed" }
    }
}
