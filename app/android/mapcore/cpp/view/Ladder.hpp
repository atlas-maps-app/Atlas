// The view ladder (plan-vector D4, D18; R/live/ViewLadder.kt): the engine's camera settings for any view of a region,
// from the region's own views (the knots `rhead` carries, tiles/Sections.hpp: places.py's views, or the living map's
// camera), held bit-equal to the port's ViewLadder.place over the boards' views, the sweeps and 10^5 random cameras
// (MT/frame/LadderParityTest.kt). The Kotlin version allocates per call (§0.4); this one only reads the knots.
//
// A view that matches one of the region's views (its target within SNAP_M, yaw and pitch within SNAP_DEG, distance
// within SNAP_DIST and the principal point within SNAP_CY) takes that view's own settings as regions.py's cfg_for makes
// them, so a board's camera renders the board byte for byte; any other view gets the distance-driven settings (radius,
// ground_r, win_d, haze0, haze1) interpolated in distance between the views, scaled beyond the nearest and the
// farthest, and the rest from the nearest view.
//
// The port's arithmetic in its order, contraction off: RegionFrame's x and z, LiveCamera's bearing and pitch, Kotlin's
// hypot (Java's StrictMath.hypot, fdlibm's e_hypot, java_hypot below) and abs, Double's % (fmod, exact).
#pragma once

#include "style/CameraState.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Values.hpp"
#include "view/Camera.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::view {

/// ViewLadder's snapping tolerances and the engine's defaults (ViewLadder.Companion).
inline constexpr double snap_m = 1.0;
inline constexpr double snap_deg = 0.05;
inline constexpr double snap_dist = 0.002;
inline constexpr double snap_cy = 0.75;
inline constexpr std::int64_t obj_split = 1'900'000;
inline constexpr double living_haze0 = 380.0;
inline constexpr double living_haze1 = 800.0;
inline constexpr double default_ground_r = 1500.0;
inline constexpr double default_win_d = 390.0;

/// Where the map looks, in the map's terms (LiveView): the target, the eye's distance from it (metres:
/// LiveView.distance, which Kotlin computes from the zoom and sends in the camera state), the bearing (degrees
/// clockwise from north), the map's pitch (degrees from straight down), and the engine frame: widthDp × heightDp with
/// the principal point cyDp down from the top.
struct LiveView {
    double latitude = 0;
    double longitude = 0;
    double distance = 0;
    double bearing = 0;
    double pitch = 0;
    std::int32_t width_dp = 0;
    std::int32_t height_dp = 0;
    double cy_dp = 0;
};

/// A region's ground frame (RegionFrame): x = (lon − lon0)·kx east, z = −(lat − lat0)·kz south, from degrees.
struct GroundFrame {
    double lat0 = 0;
    double lon0 = 0;
    double kx = 0;
    double kz = 0;

    [[nodiscard]] constexpr double x(double lat, double lon) const noexcept {
        (void)lat;
        return (lon - lon0) * kx;
    }
    [[nodiscard]] constexpr double z(double lat, double lon) const noexcept {
        (void)lon;
        return -(lat - lat0) * kz;
    }
    friend constexpr bool operator==(const GroundFrame&, const GroundFrame&) = default;
};

/// The ground frame of a pack's `rhead`.
[[nodiscard]] constexpr GroundFrame frame_of(const tiles::Frame& f) noexcept {
    return {.lat0 = f.lat0, .lon0 = f.lon0, .kx = f.kx, .kz = f.kz};
}

/// LiveCamera.bearing: the map's bearing for an engine yaw, in [0, 360).
[[nodiscard]] double bearing_of(double yaw) noexcept;
/// LiveCamera.enginePitch: the engine's pitch (degrees below the horizon) for the map's pitch (degrees from down).
[[nodiscard]] constexpr double engine_pitch(double map_pitch) noexcept { return 90.0 - map_pitch; }
/// ViewLadder.turn: the shortest signed turn from `from` to `to` degrees.
[[nodiscard]] double turn(double from, double to) noexcept;
/// Kotlin's hypot: Java's StrictMath.hypot (FdLibm.Hypot, fdlibm 5.3's e_hypot.c), bit for bit.
[[nodiscard]] double java_hypot(double x, double y) noexcept;

/// One value of a placed camera's settings: an integer, a double, a boolean, or another value of the view it came from
/// (a string, a list).
struct Setting {
    enum class Kind : std::uint8_t { integer, real, boolean, triple, value } kind = Kind::real;
    std::int64_t integer = 0;
    double real = 0;
    bool boolean = false;
    /// A list of three doubles the ladder computed (a view's target).
    std::array<double, 3> triple{};
    tiles::Value value;

    /// The setting as the engine reads a number (Python's float of an int, Kotlin's toDouble), or nothing.
    [[nodiscard]] std::optional<double> number() const noexcept;
};

/// The engine's camera for a view (ViewLadder.Placed): its settings, in the order the port's cfg holds them, and the
/// region view it matched (none between views). Its keys and values view the ladder's `rhead`: it lives no longer than
/// the pack.
class Placed {
public:
    /// The setting named `key`, or nothing.
    [[nodiscard]] const Setting* find(std::string_view key) const noexcept;
    /// A numeric setting, or nothing (absent, or not a number).
    [[nodiscard]] std::optional<double> number(std::string_view key) const noexcept;
    /// Python's truth of a setting (absent: false).
    [[nodiscard]] bool truthy(std::string_view key) const noexcept;
    /// Every setting, in the cfg's order.
    [[nodiscard]] std::span<const std::pair<std::string_view, Setting>> settings() const noexcept { return settings_; }

    /// The region view it matched (the knot's name), or nothing.
    [[nodiscard]] std::optional<std::string_view> view() const noexcept { return view_; }
    /// Placed.dist: the matched view's distance, or the view's.
    [[nodiscard]] double dist() const noexcept { return dist_; }

    /// The engine camera of these settings (Amsterdam.cameraOf: target, yaw, pitch, dist, fov, W, H, cx, cy).
    [[nodiscard]] CameraSpec camera() const noexcept;

    /// Every setting as `key=value` lines (integers in decimal, doubles as their bits in hex, booleans, other values by
    /// their type), for the parity tests.
    [[nodiscard]] std::string dump() const;

private:
    friend class Ladder;
    void set(std::string_view key, Setting value);

    std::vector<std::pair<std::string_view, Setting>> settings_;
    std::optional<std::string_view> view_;
    double dist_ = 0;
};

/// A region's ladder.
class Ladder {
public:
    /// One of the region's views (ViewLadder.Knot).
    struct Knot {
        std::string_view name;
        tiles::Value camera; ///< a map: the view's settings as places.py writes them
        double dist = 0;
        std::array<double, 3> target{};
        double yaw = 0;
        double pitch = 0;
        double cy = 0; ///< NaN when the view names none
    };

    /// The ladder of a region's views (rhead's), sorted by distance as ViewLadder sorts them (stably); `living` for the
    /// living map, whose camera has no cfg_for. Nothing when a view lacks a number its knot needs, or there is none.
    [[nodiscard]] static std::optional<Ladder> of(std::span<const tiles::HeadView> views, GroundFrame frame,
                                                  bool living);
    /// The ladder of a pack's `rhead` (its views, its frame, living for the living profile).
    [[nodiscard]] static std::optional<Ladder> of(const tiles::RenderHead& head);
    /// The ladder of a pack without views (a frame cell's windows, which place no camera): place() takes the live view
    /// as it is, in `frame`, with what regions.py's cfg_for gives a view that sets nothing but its camera (a region's:
    /// no fin, the haze from the distance); nothing snaps.
    [[nodiscard]] static Ladder bare(GroundFrame frame, bool living);

    [[nodiscard]] std::span<const Knot> knots() const noexcept { return knots_; }
    [[nodiscard]] const GroundFrame& frame() const noexcept { return frame_; }
    [[nodiscard]] bool living() const noexcept { return living_; }

    /// The region view `v` matches (ViewLadder.snap), or null.
    [[nodiscard]] const Knot* snap(const LiveView& v) const noexcept;
    /// The engine's camera for `v` (ViewLadder.place without a ground: the target at street level); a bare ladder's,
    /// the live view's.
    [[nodiscard]] Placed place(const LiveView& v) const;
    /// A region view's own camera with the frame size of the screen (ViewLadder.exact: regions.py's cfg_for).
    [[nodiscard]] Placed exact(const Knot& k, std::int32_t width_dp, std::int32_t height_dp) const;

private:
    [[nodiscard]] double value_of(const Knot& k, std::string_view key) const noexcept;
    [[nodiscard]] double scaled(std::string_view key, double dist) const noexcept;
    [[nodiscard]] double between(double dist, std::string_view key) const noexcept;

    std::vector<Knot> knots_;
    GroundFrame frame_;
    bool living_ = false;
};

/// The live view of a camera state (the map's camera as Kotlin wrote it): its target, distance, bearing, pitch, the
/// engine frame (width and height dp) and the principal point's y.
[[nodiscard]] LiveView live_view(const style::CameraState& c) noexcept;

/// The radius a live frame's canal names are placed within over a pack without views (a window's bare ladder sets
/// none): the canal ring's views' radius at `dist` (places.py's grachten: 1,400 m at 1,600, 1,500 m at 2,300 and
/// 2,700), scaled beyond them and interpolated between them as ViewLadder takes a view's radius, so every place names
/// its canals near the view's target as the canal ring did (ENGINE.md, one pack per country: one ladder).
[[nodiscard]] double live_names_radius(double dist) noexcept;

} // namespace atlas::view
