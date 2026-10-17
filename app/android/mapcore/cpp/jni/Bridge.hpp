// The façade's native side (plan-vector T9, Appendix D): the JNI entry points of app.atlas.mapcore.Native, registered
// by JNI_OnLoad (jni/Registry.cpp) after the class's ABI matches the library's, and the layouts both sides share.
#pragma once

#include <jni.h>

#include <cstdint>
#include <span>
#include <string>

namespace atlas::jni {

/// The façade's ABI (MapCore.ABI and Native.ABI in Kotlin): an APK built against another core refuses to load it. 2:
/// Native.resetGuard (VK facts' "Reset guard").
inline constexpr jint abi = 2;

/// The Kotlin class whose static natives the library registers.
inline constexpr const char* native_class = "app/atlas/mapcore/Native";

/// The natives of Native, as RegisterNatives takes them.
[[nodiscard]] std::span<const JNINativeMethod> facade_methods() noexcept;

/// Registers the façade's natives on `cls` after checking its static int field ABI; "" on success, else the refusal.
[[nodiscard]] std::string register_facade(JNIEnv* env, jclass cls);

/// The offset tables of the layouts the façade shares with Kotlin (the camera state, the frame records), as text:
/// "<layout> version <v> size <n>" then "<layout>.<field> <offset> <size>" lines.
[[nodiscard]] std::string layout_tables();

/// One frame as the core reports it to VK bench (Native.records): 96 bytes, little-endian (MK/FrameRecords.kt).
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
    std::uint32_t flags;       // FrameFlag bits, and the configuration in bits 8-15

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
} // namespace frame_flag

} // namespace atlas::jni
