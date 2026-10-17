// One frame as the core reports it to VK bench (Native.records, MK/FrameRecords.kt): written by the core's session on
// its render thread (standin/Session.hpp), read through the façade by Kotlin, laid out the same on both sides
// (jni/Layouts.cpp's tables, :mapcorecheck's layout test).
#pragma once

#include <cstdint>

namespace atlas::standin {

/// One frame: 96 bytes, little-endian.
struct FrameRecord {
    std::uint64_t frame;       // the frame's number
    std::int64_t vsync_ns;     // Choreographer's frame time it was drawn for
    std::int64_t start_ns;     // the render thread began it (CLOCK_MONOTONIC)
    std::int64_t end_ns;       // its present returned
    std::int64_t presented_ns; // the display showed it (VK_GOOGLE_display_timing), 0 when unknown
    std::int64_t desired_ns;   // the presentation time asked for (the vsync's expected presentation), 0 for none
    std::uint64_t gpu_frame;   // the frame the GPU times belong to (an earlier one: GPU results are read late)
    float gpu_ms;
    float gpu_main_ms;
    float gpu_offscreen_ms;
    float gpu_after_ms;
    float record_ms;  // recording the frame's commands
    float prepare_ms; // the stand-in's CPU work before recording (ranks, casters, uniforms)
    std::uint32_t draws;
    std::uint32_t triangles_k; // thousands
    std::uint32_t fragments_k; // thousands of fragment invocations (pipeline statistics; 0 where unsupported)
    std::uint32_t flags;       // FrameFlag bits, the configuration in bits 8-15, the colour depth in bit 16

    static constexpr std::uint32_t version = 1;
};
static_assert(sizeof(FrameRecord) == 96);

/// FrameRecord::flags.
namespace frame_flag {
inline constexpr std::uint32_t presented = 1U << 0; // presented_ns is the display's own time
inline constexpr std::uint32_t skipped = 1U << 1;   // not drawn: the swapchain was out of date
inline constexpr std::uint32_t gpu = 1U << 2;       // the GPU times are valid
inline constexpr std::uint32_t guarded = 1U << 3;   // within the crash guard's first 120 frames
/// The configuration: bits 8-10 FrameConfig, 11 route (1 dynamic), 12 form (1 onepass), 13 target (1 direct), 14 host
/// (1 TextureView), 15 pills.
inline constexpr std::uint32_t config_shift = 8;
inline constexpr std::uint32_t ten_bit = 1U << 16; // drawn in 10 bits a channel (rgb10a2 or bgr10a2), else 8
} // namespace frame_flag

} // namespace atlas::standin
