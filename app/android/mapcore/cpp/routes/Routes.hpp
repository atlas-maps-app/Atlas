// The app's routes and areas (ENGINE.md step 6; MapCore.routes): what lies on the ground under the map's symbols,
// drawn by the core in the map's own frame (passes/Routes.cpp) so it moves with the map in the same frame, as mapview's
// OverlayPainter.drawGround draws it over the canvas:
//   - an area, a shaded circle on the street (AreaOverlay): its fill, and a 1.5 dp line round it;
//   - a route, a ribbon on the street (RouteLine) widened in metres, so it narrows into the distance like paint on the
//     road (never thinner than a hairline), with round joins and caps: its white casing under it, its colour before
//     the distance already travelled and a faded one after, dashed where the route walks; an alternative quieter and
//     narrower, under the main ones; and its label's chip halfway along the part of it on the view, over every ribbon.
// The app writes each route's look as the painter sets it (the colours mixed, the widths in dp) with its points and
// their distances along it (true metres, RouteRibbons.prepare's), and draws each chip as a sprite with the device's own
// Skia exactly as OverlayPainter.routeChip draws it (premultiplied RGBA8 at the screen's density, the chip's centre on
// a whole pixel of it). The core widens the ribbons by the camera's scale each frame (passes/Routes.cpp).
//
// A routes() call makes a RouteSet: immutable, shared by the frames that draw it, the chips' texels one after another
// in one buffer.
#pragma once

#include "labels/Sprites.hpp"

#include <array>
#include <cstdint>
#include <expected>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::routes {

/// One image: width x height px, premultiplied RGBA8 (R in the low byte), rows top to bottom, and where its centre lies
/// in it, whole px.
using Image = labels::SpriteImage;

/// A route without a chip.
inline constexpr std::uint32_t no_chip = 0xFFFF'FFFFU;

/// A shaded circle on the street.
struct Area {
    double latitude = 0.0;
    double longitude = 0.0;
    float radius = 0.0F;      ///< metres
    std::uint32_t fill = 0;   ///< ARGB, as the canvas's paint takes it (its alpha the fill's)
    std::uint32_t stroke = 0; ///< ARGB
};

/// A route's look, as OverlayPainter.drawGround sets it for the route, in the order the routes are drawn (the
/// alternatives first).
struct Route {
    /// Its points: latitude, longitude, and the distance along the route to it, true metres.
    std::vector<std::array<double, 3>> points;
    float width = 6.0F;           ///< its fill's width, dp (an alternative's already narrower)
    std::uint32_t colour = 0;     ///< its fill before the distance travelled (ARGB, opaque)
    std::uint32_t faded = 0;      ///< its fill after it
    std::uint32_t casing = 0;     ///< its casing (ARGB, opaque)
    bool dashed = false;          ///< a walking leg: dashes of 1.3 widths every 2.6
    double travelled = 0.0;       ///< metres along it already travelled
    std::uint32_t chip = no_chip; ///< its label's chip: an index into the set's chips, or none
};

/// Why a set of routes was refused.
enum class RouteError : std::uint8_t {
    size,    ///< a chip's pixels are not its width x height, or a chip is empty
    large,   ///< a chip is larger than max_side on a side
    points,  ///< a route has fewer than two points or more than max_points, a point that is none, or distances that go
             ///< back
    chip,    ///< a route's chip is none of the set's
    width,   ///< a route's width or an area's radius is not a finite number above 0
    count,   ///< more routes or areas than may be
    density, ///< the density is not a finite number from 0.5 to 8
};

[[nodiscard]] std::string_view describe(RouteError error) noexcept;

/// The routes and areas of one routes() call.
class RouteSet {
public:
    static constexpr std::uint32_t max_side = 2048;
    /// A route's points at most, and the routes' and areas' at most.
    static constexpr std::size_t max_points = std::size_t{1} << 20U;
    static constexpr std::size_t max_routes = 64;
    static constexpr std::size_t max_areas = 256;

    /// The set of `areas`, `routes` and the routes' `chips`, drawn at `density` (the app's, which the chips are drawn
    /// at), at `revision` (the caller's count of hand-overs); or why not.
    [[nodiscard]] static std::expected<std::shared_ptr<const RouteSet>, RouteError>
    make(std::vector<Area> areas, std::vector<Route> routes, std::vector<Image> chips, float density,
         std::uint64_t revision);

    [[nodiscard]] std::span<const Area> areas() const noexcept { return areas_; }
    [[nodiscard]] std::span<const Route> routes() const noexcept { return routes_; }
    /// The chips; their pixels are in texels(), each from its offset in chip_offsets().
    [[nodiscard]] std::span<const Image> chips() const noexcept { return chips_; }
    [[nodiscard]] std::span<const std::uint32_t> chip_offsets() const noexcept { return chip_offsets_; }
    [[nodiscard]] std::span<const std::uint32_t> texels() const noexcept { return texels_; }
    [[nodiscard]] float density() const noexcept { return density_; }
    [[nodiscard]] std::uint64_t revision() const noexcept { return revision_; }

private:
    RouteSet() = default;
    std::vector<Area> areas_;
    std::vector<Route> routes_;
    std::vector<Image> chips_;
    std::vector<std::uint32_t> chip_offsets_;
    std::vector<std::uint32_t> texels_;
    float density_ = 1.0F;
    std::uint64_t revision_ = 0;
};

/// The magic number that opens a block of routes ('RTE1', little-endian).
inline constexpr std::uint32_t route_block_magic = 0x3145'5452U;

/// The routes of a block as the app writes it (MK/state/RouteBlock.kt; Native.routes), little-endian: the magic, the
/// density (f32) and the counts of areas, routes and chips (u32 each); each area: its latitude and longitude (f64), its
/// radius (f32), its fill and stroke (u32 ARGB); each route: its points' count (u32), its width (f32), its colour,
/// faded colour and casing (u32 ARGB), its dashed flag (u8) and three zero bytes, the distance travelled (f64), its
/// chip (u32, no_chip for none), and its points (f64 latitude, longitude and distance along it each); each chip: its
/// width and height (u32), centre (i32 x, y) and pixels (width x height u32). Or why it was refused; the set's own
/// checks are RouteSet::make's.
struct RouteBlock {
    float density = 0.0F;
    std::vector<Area> areas;
    std::vector<Route> routes;
    std::vector<Image> chips;
};
[[nodiscard]] std::expected<RouteBlock, std::string> read_route_block(std::span<const std::byte> block);

} // namespace atlas::routes
