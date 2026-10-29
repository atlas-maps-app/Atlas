// The gesture camera of one frame (plan-vector T9, Appendix D): the 552 bytes Kotlin writes on the render thread at
// each paced vsync (MK/state/CameraWriter.kt, from FrameSource.camera), read and checked here. Everything derived from
// the camera (the frame's metres, the basis, ViewLadder's values, ScreenLayers' stops) is the core's (D4).
//
// CameraState, version 3, little-endian, defined here once: jni/Layouts.cpp (the façade's tables) and every other unit
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
//    80  u32  width, height in px; 88 u32 width, height in dp   544  u64  the parts the map leaves out (camera_part)
//
// Version 2 adds the location (camera_flag::location): latitude and longitude (f64), the fix's accuracy in metres and
// its heading in degrees from north (f32, with camera_flag::heading), and 8 reserved bytes (0). Written with the camera
// at each vsync, the location's puck moves with the map in the same frame (ENGINE.md step 6).
//
// Version 3 adds the parts of the picture the map leaves out (camera_part: a bit a layer or an effect, every one of
// them a switch of the app's), which the flags' five bits of version 2 (the trees, the soft shadows, the clouds'
// shadows, the water's light, the facades' detail) could not all hold. A part left out is left out whole: no draw,
// dispatch or offscreen pass of it, and none of its work in stage F (ENGINE.md, "On the user's phone first").
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
inline constexpr std::uint32_t camera_state_version = 3;

/// CameraState's size in bytes (CameraWriter.SIZE in Kotlin).
inline constexpr std::size_t camera_state_size = 552;

/// The most keep-out shapes a frame carries (CameraWriter.MAX_KEEP_OUTS).
inline constexpr std::size_t max_keep_outs = 16;

/// The camera state's flag bits (CameraWriter.REST, ENGINE_MODE, RADIUS_CUT, FOLLOW, LOCATION, HEADING).
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
inline constexpr std::uint32_t known = 0x3FU;
} // namespace camera_flag

/// The parts of the picture the map leaves out (CameraState::parts; CameraWriter.PART_* in Kotlin are their bit
/// numbers): the map's own layers (MapLayer.TREES and SHADOWS off) and the switches of a debug build's Look settings,
/// so the map's panel shows on the phone what each part costs (ENGINE.md, "On the user's phone first"). A part left
/// out is not decided, laid or recorded: no draw, dispatch or offscreen pass of it and none of its stage F, and an item
/// kind whose parts are all left out takes no rank. Everything else is decided as before.
namespace camera_part {
// The ground (passes/Ground.hpp).
/// The land cover, the areas, the roads (with a big road's lines and a runway's marks, which go with their pavement)
/// and the plate under them.
inline constexpr std::uint64_t ground = 1ULL << 0U;
/// The water's surface: its fill, its tint and its clip, and so everything drawn on it.
inline constexpr std::uint64_t water = 1ULL << 1U;
/// The canopy over the land cover.
inline constexpr std::uint64_t canopy = 1ULL << 2U;
/// The ground's lines: rails, big roads' edges and lanes, tram rails, bridges' lines, runways' and taxiways' lines and
/// the parks' edges.
inline constexpr std::uint64_t ground_lines = 1ULL << 3U;
/// The sett roads' courses.
inline constexpr std::uint64_t setts = 1ULL << 4U;
/// The names on the ground: the canal names and the runways' designators (passes/GroundText.hpp).
inline constexpr std::uint64_t ground_text = 1ULL << 5U;
/// The quay walls.
inline constexpr std::uint64_t quays = 1ULL << 6U;
/// The sky: its band, the sun's glow and disc, the city's glow.
inline constexpr std::uint64_t sky = 1ULL << 7U;
// The shadows.
/// The buildings' soft shadows on the ground: their mask, their blur and their fill (or their sharp union).
inline constexpr std::uint64_t building_shadows = 1ULL << 8U;
/// The parks' shadows on the ground: the woods' trees' and the coaster tracks'.
inline constexpr std::uint64_t park_shadows = 1ULL << 9U;
/// The clouds' shadows (the composite's step).
inline constexpr std::uint64_t cloud_shadows = 1ULL << 10U;
// The water's light.
/// The reflections in the water, and the ripple's layer with them.
inline constexpr std::uint64_t reflections = 1ULL << 11U;
/// The ripple that wobbles the reflections (its layer and filter): the reflections drawn as they are.
inline constexpr std::uint64_t ripple = 1ULL << 12U;
/// The sun's glitter on the water.
inline constexpr std::uint64_t glitter = 1ULL << 13U;
/// The drizzle's rings on the water.
inline constexpr std::uint64_t rings = 1ULL << 14U;
/// The wind's glints on the water.
inline constexpr std::uint64_t glints = 1ULL << 15U;
/// The night's light streaks on the water: the street lamps' and the lit roads' reflections.
inline constexpr std::uint64_t water_streaks = 1ULL << 16U;
// The night.
/// The glowing streets: the lit roads' glow, its layer and its blur.
inline constexpr std::uint64_t lit_streets = 1ULL << 17U;
/// The lit roads' wet cores on a wet night, and their layer.
inline constexpr std::uint64_t wet_cores = 1ULL << 18U;
/// The street lamps' pools on the ground.
inline constexpr std::uint64_t lamp_pools = 1ULL << 19U;
/// The street lamps' halos.
inline constexpr std::uint64_t lamp_halos = 1ULL << 20U;
/// The street lamps' wet streaks on a wet night.
inline constexpr std::uint64_t lamp_streaks = 1ULL << 21U;
// The composite's weather and light.
/// The rain (the composite's step).
inline constexpr std::uint64_t rain = 1ULL << 22U;
/// The golden wash (the composite's step).
inline constexpr std::uint64_t golden_wash = 1ULL << 23U;
// The buildings (passes/Walls.hpp).
/// The walls, with their near cells.
inline constexpr std::uint64_t walls = 1ULL << 24U;
/// The roofs and the rest of the walls' plain list (an n-gon's triangles, a kit's ground floor band).
inline constexpr std::uint64_t roofs = 1ULL << 25U;
/// The roofs' thin edges: their decide stage and their draws.
inline constexpr std::uint64_t roof_edges = 1ULL << 26U;
/// The windows (and a houseboat cabin's panes): their decide stage, frames and glass.
inline constexpr std::uint64_t windows = 1ULL << 27U;
/// The deep windows' parts and the sash bars: reveals, sills and bars.
inline constexpr std::uint64_t window_depth = 1ULL << 28U;
/// The facades' flat detail: shop glazing, plinths and cornices, and their decide stage.
inline constexpr std::uint64_t flat_detail = 1ULL << 29U;
/// A church's own windows and their decide stage.
inline constexpr std::uint64_t church_windows = 1ULL << 30U;
/// The walls' material faces and their decide stage.
inline constexpr std::uint64_t material_faces = 1ULL << 31U;
/// The hoist beams under old gables.
inline constexpr std::uint64_t hoist_beams = 1ULL << 32U;
/// A tradition's kit's details: its roofs' courses and ridges, its walls' boards' lines, corner boards and eaves'
/// shadows.
inline constexpr std::uint64_t kit_details = 1ULL << 33U;
/// The doors (passes/Doors.hpp), and their numbers with them.
inline constexpr std::uint64_t doors = 1ULL << 34U;
/// The house numbers on the doors.
inline constexpr std::uint64_t house_numbers = 1ULL << 35U;
// Trees and things.
/// The trees (passes/Trees.hpp).
inline constexpr std::uint64_t trees = 1ULL << 36U;
/// The trees' leaf filters: the crowns' and the reflected trees' leaves'.
inline constexpr std::uint64_t leaf_filters = 1ULL << 37U;
/// The street furniture (passes/Things.hpp): lamps, benches, racks, baskets, bollards and the bridges' railings.
inline constexpr std::uint64_t furniture = 1ULL << 38U;
/// A region's items (passes/Items.hpp): towers, turbines, cranes, masts, gantries, conveyors, coasters, power lines
/// and the parks' woods.
inline constexpr std::uint64_t region_items = 1ULL << 39U;
// What lies over the map.
/// The labels (passes/Labels.hpp).
inline constexpr std::uint64_t labels = 1ULL << 40U;
/// The routes and areas (passes/Routes.hpp).
inline constexpr std::uint64_t routes = 1ULL << 41U;
/// The markers (passes/Markers.hpp).
inline constexpr std::uint64_t markers = 1ULL << 42U;
/// The location's puck (passes/Overlays.hpp).
inline constexpr std::uint64_t location = 1ULL << 43U;
/// The frosted glass under the chrome (passes/Frosted.hpp).
inline constexpr std::uint64_t frosted = 1ULL << 44U;
// The shadows again.
/// The soft shadows' blur: their mask and its blur, the shadows drawn sharp (their stencil's union) instead.
inline constexpr std::uint64_t shadow_blur = 1ULL << 45U;
/// Every part version 3 knows; a state with another is refused.
inline constexpr std::uint64_t known = (1ULL << 46U) - 1U;

/// The facades' detail: every part of a facade wall (ENGINE.md step 4, the facades), its doors and numbers too.
inline constexpr std::uint64_t facade_detail = windows | window_depth | flat_detail | church_windows | material_faces |
                                               hoist_beams | kit_details | doors | house_numbers;
/// Every part of the buildings pass (the doors are a pass of their own).
inline constexpr std::uint64_t buildings = walls | roofs | roof_edges | windows | window_depth | flat_detail |
                                           church_windows | material_faces | hoist_beams | kit_details;
/// The water's light: every effect on the water.
inline constexpr std::uint64_t water_light = reflections | ripple | glitter | rings | glints | water_streaks;
} // namespace camera_part

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

/// The camera state, version 3 (the layout above).
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
    std::uint64_t parts;

    [[nodiscard]] constexpr bool has(std::uint32_t flag) const noexcept { return (flags & flag) != 0U; }
    /// Whether the map leaves `part` out (a camera_part bit; with several, any of them).
    [[nodiscard]] constexpr bool leaves_out(std::uint64_t part) const noexcept { return (parts & part) != 0U; }
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
              offsetof(CameraState, keep_out) == 128 && offsetof(CameraState, location) == 512 &&
              offsetof(CameraState, parts) == 544);

/// Why a camera state was refused.
enum class CameraError : std::uint8_t {
    size,       // not 552 bytes
    version,    // another layout version
    flags,      // a flag bit version 3 does not know
    reserved,   // a reserved field or an unused keep-out slot that is not 0
    profile,    // a profile past the known ones
    keep_outs,  // more than 16 shapes, or one with no area, out of range or with an unknown flag
    frame,      // a size or density out of range, or dp sizes that are not the px sizes over the density
    not_finite, // a NaN or an infinity
    target,     // a latitude or longitude out of range
    view,       // a bearing, pitch or distance out of range
    principal,  // the principal point far outside the frame
    location,   // a location, accuracy or heading out of range
    parts,      // a part version 3 does not know
};

/// The state in `bytes`, checked: size, version, flags, the ranges above, the keep-out shapes in use and the unused
/// slots.
[[nodiscard]] std::expected<CameraState, CameraError> read_camera_state(std::span<const std::byte> bytes) noexcept;

/// The name of a refusal, for logs and tests.
[[nodiscard]] const char* name_of(CameraError error) noexcept;

} // namespace atlas::style
