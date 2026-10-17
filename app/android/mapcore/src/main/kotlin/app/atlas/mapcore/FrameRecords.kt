package app.atlas.mapcore

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The frames as the core reports them to VK bench (`CP/jni/Bridge.hpp`'s FrameRecord, version 1): 96 bytes each,
 * little-endian, copied in batches by `Native.records`. [FIELDS] mirrors the core's offset table, and
 * `MT/facade/FacadeLayoutTest.kt` holds the two equal.
 */
object FrameRecords {
    const val VERSION = 1
    const val SIZE = 96

    const val FRAME_AT = 0
    const val VSYNC_AT = 8
    const val START_AT = 16
    const val END_AT = 24
    const val PRESENTED_AT = 32
    const val DESIRED_AT = 40
    const val GPU_FRAME_AT = 48
    const val GPU_MS_AT = 56
    const val GPU_MAIN_MS_AT = 60
    const val GPU_OFFSCREEN_MS_AT = 64
    const val GPU_AFTER_MS_AT = 68
    const val RECORD_MS_AT = 72
    const val PREPARE_MS_AT = 76
    const val DRAWS_AT = 80
    const val TRIANGLES_K_AT = 84
    const val FRAGMENTS_K_AT = 88
    const val FLAGS_AT = 92

    // FLAGS: the record's state, the configuration in bits 8-15, the colour depth in bit 16.
    const val PRESENTED = 1
    const val SKIPPED = 2
    const val GPU = 4
    const val GUARDED = 8

    /** The frame was drawn in 10 bits a channel (rgb10a2 or bgr10a2), else 8. */
    const val TEN_BIT = 1 shl 16

    /** The layout as (name, offset, size), named as the core's table names its fields. */
    val FIELDS: List<Triple<String, Int, Int>> = listOf(
        Triple("frame", FRAME_AT, 8), Triple("vsync_ns", VSYNC_AT, 8), Triple("start_ns", START_AT, 8), Triple("end_ns", END_AT, 8),
        Triple("presented_ns", PRESENTED_AT, 8), Triple("desired_ns", DESIRED_AT, 8), Triple("gpu_frame", GPU_FRAME_AT, 8),
        Triple("gpu_ms", GPU_MS_AT, 4), Triple("gpu_main_ms", GPU_MAIN_MS_AT, 4), Triple("gpu_offscreen_ms", GPU_OFFSCREEN_MS_AT, 4),
        Triple("gpu_after_ms", GPU_AFTER_MS_AT, 4), Triple("record_ms", RECORD_MS_AT, 4), Triple("prepare_ms", PREPARE_MS_AT, 4),
        Triple("draws", DRAWS_AT, 4), Triple("triangles_k", TRIANGLES_K_AT, 4), Triple("fragments_k", FRAGMENTS_K_AT, 4),
        Triple("flags", FLAGS_AT, 4),
    )

    /** A buffer for [count] records. */
    fun allocate(count: Int): ByteBuffer = ByteBuffer.allocateDirect(count * SIZE).order(ByteOrder.LITTLE_ENDIAN)

    /** Record [index] of [buffer] (read off the frame path: it allocates the record). */
    fun read(buffer: ByteBuffer, index: Int): Record {
        val o = index * SIZE
        return Record(
            frame = buffer.getLong(o + FRAME_AT),
            vsyncNs = buffer.getLong(o + VSYNC_AT),
            startNs = buffer.getLong(o + START_AT),
            endNs = buffer.getLong(o + END_AT),
            presentedNs = buffer.getLong(o + PRESENTED_AT),
            desiredNs = buffer.getLong(o + DESIRED_AT),
            gpuFrame = buffer.getLong(o + GPU_FRAME_AT),
            gpuMs = buffer.getFloat(o + GPU_MS_AT),
            gpuMainMs = buffer.getFloat(o + GPU_MAIN_MS_AT),
            gpuOffscreenMs = buffer.getFloat(o + GPU_OFFSCREEN_MS_AT),
            gpuAfterMs = buffer.getFloat(o + GPU_AFTER_MS_AT),
            recordMs = buffer.getFloat(o + RECORD_MS_AT),
            prepareMs = buffer.getFloat(o + PREPARE_MS_AT),
            draws = buffer.getInt(o + DRAWS_AT),
            trianglesK = buffer.getInt(o + TRIANGLES_K_AT),
            fragmentsK = buffer.getInt(o + FRAGMENTS_K_AT),
            flags = buffer.getInt(o + FLAGS_AT),
        )
    }

    /** One frame's numbers. */
    data class Record(
        val frame: Long,
        val vsyncNs: Long,
        val startNs: Long,
        val endNs: Long,
        val presentedNs: Long,
        val desiredNs: Long,
        val gpuFrame: Long,
        val gpuMs: Float,
        val gpuMainMs: Float,
        val gpuOffscreenMs: Float,
        val gpuAfterMs: Float,
        val recordMs: Float,
        val prepareMs: Float,
        val draws: Int,
        val trianglesK: Int,
        val fragmentsK: Int,
        val flags: Int,
    ) {
        val presented: Boolean get() = flags and PRESENTED != 0
        val skipped: Boolean get() = flags and SKIPPED != 0
        val gpuValid: Boolean get() = flags and GPU != 0
        val tenBit: Boolean get() = flags and TEN_BIT != 0
        /** The render thread's milliseconds for the frame (from its start to the present returning). */
        val cpuMs: Double get() = (endNs - startNs) / 1e6
    }
}
