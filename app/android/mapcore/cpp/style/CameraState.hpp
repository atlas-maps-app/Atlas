// The gesture camera of one frame (plan-vector T9, Appendix D): the 544 bytes Kotlin writes on the render thread at
// each paced vsync (MK/state/CameraWriter.kt, from FrameSource.camera), read and checked here. Everything derived from
// the camera (the frame's metres, the basis, ViewLadder's values, ScreenLayers' stops) is the core's (D4).
//
// CameraState, version 2, little-endian, defined here once: jni/Layouts.cpp (the façade's tables) and every other unit
// include this header, and MT/style/LayoutTest.kt holds Kotlin's offsets, flags and enum numbers to its tables
// (style/Layouts.hpp).
//
//     0  u32  version (1)                            96  u64  the active pack's id
//     4  u32  flags (camera_flag)                   104  u32  its profile (Profile)
//     8  u64  sequence                              108  u32  revisions: the theme,
//    16  f64  target latitude, longitude (degrees)  112  u32    the labels,
//    32  f64  bearing (degrees from north)          116  u32    the overlays
//    40  f64  the map's pitch (degrees from down)   120  u32  keep-out shapes in use (0-16)
//    48  f64  distance, eye to target (metres)      124  u32  reserved (0)
//    56  f64  principal point x, y (dp)             128  KeepOut[16], 24 bytes each: the chrome's keep-out shapes
//    72  f64  density (px per dp)                  512  Location: where the user is, as the map shows it
//    80  u32  width, height in px; 88 u32 width, height in dp
//
// Version 2 adds the location (camera_flag::location): latitude and longitude (f64), the fix's accuracy in metres and
// its heading in degrees from north (f32, with camera_flag::heading), and 8 reserved bytes (0). Written with the camera
// at each vsync, the location's puck moves with the map in the same frame (ENGINE.md step 6).
//
// The reader accepts a state only within these ranges: latitude within ±90 and longitude within ±180 degrees; a
// bearing within ten turns of north (the core reduces it); the map's pitch 0-90 degrees; a distance of 0.6 m (the
// engine's near plane) to 10^9 m (the whole planet with room to spare); 1-16384 px a side, a density over 0.25 and
// under 16 px per dp, and each dp size the px size over the density, rounded either way (within one density of it);
// the principal point and every keep-out shape within one frame's size of the frame; a corner radius at most half the
// shape's shorter side (and half a dp more for rounding), a blur up to 256 dp. A shape's flags hold whether it is
// frosted (bit 0) and how far the chrome over it has faded (bits 1-7, 0 to 127: a screen's fade, a row fading out), so
// that the core's glass fades with its tint whether or not the surface follows the views' alpha (K11b). A location
// within ±90 and ±180 degrees, an accuracy of 0 to 100 km, a heading within ten turns of north; without the location
// flag, every byte of the location 0, and no heading flag.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <span>

namespace atlas::style {

/// CameraState's layout version (CameraWriter.VERSION in Kotlin).
inline constexpr std::uint32_t camera_state_version = 2;

/// CameraState's size in bytes (CameraWriter.SIZE in Kotlin).
inline constexpr std::size_t camera_state_size = 544;

/// The most keep-out shapes a frame carries (CameraWriter.MAX_KEEP_OUTS).
inline constexpr std::size_t max_keep_outs = 16;

/// The camera state's flag bits (CameraWriter.REST, ENGINE_MODE, RADIUS_CUT, FOLLOW, LOCATION, HEADING, NO_TREES,
/// NO_SHADOWS, NO_CLOUD_SHADOWS, NO_WATER, NO_FACADES).
namespace camera_flag {
/// The camera is at rest: the frame snaps to the SVG's precision (D4).
inline constexpr std::uint32_t rest = 1U;
/// Engine mode: every per-camera rule of the renders, the radius cut included (the likeness cases).
inline constexpr std::uint32_t engine_mode = 2U;
/// The radius cut alone (D9), apart from engine mode.
inline constexpr std::uint32_t radius_cut = 4U;
/// Follow mode: the camera follows the location (30 fps unless a finger is down, D5.8).
inline constexpr std::uint32_t follow = 8U;
/// The map shows where the user is: the core draws the location's puck (passes/Overlays.hpp).
inline constexpr std::uint32_t location = 16U;
/// The location has a heading: its cone.
inline constexpr std::uint32_t heading = 32U;
/// The map leaves a part of its picture out: its own layers (MapLayer.TREES and SHADOWS off), and the app's switches
/// that show on the phone what each part costs (ENGINE.md, "On the user's phone first"). The frame skips those draws;
/// what it decides for them is unchanged. The trees (passes/Trees.hpp); the soft shadows (their mask and their fill);
/// the clouds' shadows (the composite's step); the water's light (its reflections and ripple, glitter, rings and
/// glints); the facades' detail (their windows, material faces, doors and a kit's walls' lines and eaves' shadows).
inline constexpr std::uint32_t no_trees = 64U;
inline constexpr std::uint32_t no_shadows = 128U;
inline constexpr std::uint32_t no_cloud_shadows = 256U;
inline constexpr std::uint32_t no_water = 512U;
inline constexpr std::uint32_t no_facades = 1024U;
inline constexpr std::uint32_t known = 0x7FFU;
} // namespace camera_flag

/// Whose rules draw the frame (D2): the active pack's profile as its rhead names it (tiles::Profile, Appendix A) plus
/// one, and none (0, a zeroed state's) without a pack (CameraWriter.PROFILE_*).
// NOLINTNEXTLINE(performance-enum-size): a u32 field of the layout Kotlin writes (CameraWriter.PROFILE_AT, 4 bytes)
enum class Profile : std::uint32_t { none = 0, living = 1, region = 2, williamsburg = 3, world_base = 4 };

/// One keep-out shape of the chrome: a rounded box in dp, frosted under it or only kept clear (K11b).
struct KeepOut {
    float left;
    float top;
    float right;
    float bottom;
    float radius;            // corner radius, dp
    std::uint16_t blur16;    // the frosted glass's blur radius, sixteenths of a dp
    std::uint8_t saturation; // the glass's saturation, hundredths (180: 1.8)
    std::uint8_t flags;      // bit 0: frosted; bits 1-7: the chrome's fade, 0 (opaque) to 127 (gone)

    [[nodiscard]] constexpr bool frosted() const noexcept { return (flags & 1U) != 0U; }
    [[nodiscard]] constexpr float blur_dp() const noexcept { return static_cast<float>(blur16) / 16.0F; }
    /// The alpha the chrome over the shape is drawn at (1 opaque, 0 gone): what its glass is drawn at.
    [[nodiscard]] constexpr float alpha() const noexcept {
        return 1.0F - (static_cast<float>(static_cast<unsigned>(flags) >> 1U) / 127.0F);
    }
};

static_assert(sizeof(KeepOut) == 24);

/// Where the user is, as the map shows it (camera_flag::location): degrees, the fix's accuracy in metres, and its
/// heading in degrees clockwise from north (camera_flag::heading; 0 without).
struct Location {
    double latitude;
    double longitude;
    float accuracy;
    float heading;
    std::uint32_t reserved0;
    std::uint32_t reserved1;
};

static_assert(sizeof(Location) == 32);

/// The camera state, version 2 (the layout above).
struct CameraState {
    std::uint32_t version;
    std::uint32_t flags;
    std::uint64_t sequence;
    double latitude;
    double longitude;
    double bearing;
    double pitch;
    double distance;
    double principal_x;
    double principal_y;
    double density;
    std::uint32_t width_px;
    std::uint32_t height_px;
    std::uint32_t width_dp;
    std::uint32_t height_dp;
    std::uint64_t pack_id;
    Profile profile;
    std::uint32_t theme_revision;
    std::uint32_t labels_revision;
    std::uint32_t overlays_revision;
    std::uint32_t keep_out_count;
    std::uint32_t reserved;
    std::array<KeepOut, max_keep_outs> keep_out;
    Location location;

    [[nodiscard]] constexpr bool has(std::uint32_t flag) const noexcept { return (flags & flag) != 0U; }
    /// The keep-out shapes in use.
    [[nodiscard]] std::span<const KeepOut> keep_outs() const noexcept {
        return std::span(keep_out).first(keep_out_count);
    }
};

static_assert(sizeof(CameraState) == camera_state_size);
static_assert(offsetof(CameraState, latitude) == 16 && offsetof(CameraState, principal_x) == 56 &&
              offsetof(CameraState, density) == 72);
static_assert(offsetof(CameraState, width_px) == 80 && offsetof(CameraState, width_dp) == 88 &&
              offsetof(CameraState, pack_id) == 96);
static_assert(offsetof(CameraState, profile) == 104 && offsetof(CameraState, keep_out_count) == 120 &&
              offsetof(CameraState, keep_out) == 128 && offsetof(CameraState, location) == 512);

/// Why a camera state was refused.
enum class CameraError : std::uint8_t {
    size,       // not 544 bytes
    version,    // another layout version
    flags,      // a flag bit version 1 does not know
    reserved,   // a reserved field or an unused keep-out slot that is not 0
    profile,    // a profile past the known ones
    keep_outs,  // more than 16 shapes, or one with no area, out of range or with an unknown flag
    frame,      // a size or density out of range, or dp sizes that are not the px sizes over the density
    not_finite, // a NaN or an infinity
    target,     // a latitude or longitude out of range
    view,       // a bearing, pitch or distance out of range
    principal,  // the principal point far outside the frame
    location,   // a location, accuracy or heading out of range
};

/// The state in `bytes`, checked: size, version, flags, the ranges above, the keep-out shapes in use and the unused
/// slots.
[[nodiscard]] std::expected<CameraState, CameraError> read_camera_state(std::span<const std::byte> bytes) noexcept;

/// The name of a refusal, for logs and tests.
[[nodiscard]] const char* name_of(CameraError error) noexcept;

} // namespace atlas::style
