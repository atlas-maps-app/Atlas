// The style ladder's values (ENGINE.md step 5, "The style ladder" and "The globe"; its step 8): what the world looks
// like beyond a camera 18 km from its target, by its distance D. They live in the world's style, the core's
// (load::world_style: Style::ladder, which a country's or a region's style has none of), so no pack carries them and
// none is built again when one changes; load::world_ladder holds them, the Globe board's Earth as the app draws it
// (CosmosArt.drawEarth) among them, and the globe check holds the two together.
#pragma once

#include <array>
#include <cstdint>

namespace atlas::tiles {

/// A colour as 0xRRGGBB (tiles/Style.hpp's StyleRgb) at an offset along a ramp.
struct LadderStop {
    std::uint32_t colour = 0;
    double at = 0;
};

/// A colour and its opacity.
struct LadderTint {
    std::uint32_t colour = 0;
    double opacity = 0;
};

struct WorldLadder {
    /// The knots, metres: up to `from` the renders' rules and the moment, unchanged; the night's hand-over to the caps
    /// ends at `caps`; the land and the water go to the board's ramps from `ramps` to `board`; the ring, the
    /// graticule, the rim and the shine come from `ring` and are whole at `board`; a pitched view's sky is the
    /// moment's at `caps` and space at `ring`; the haze follows the camera's distance up to `ramps` and is gone by
    /// `ring`.
    double from = 0;
    double caps = 0;
    double ramps = 0;
    double ring = 0;
    double board = 0;
    /// The haze's start and reach beyond `from`, as shares of the camera's distance.
    double haze_start = 0;
    double haze_reach = 0;

    /// The day's ground, water and roads, drawn under the caps where the moment is night.
    std::uint32_t day_ground = 0;
    std::uint32_t day_water = 0;
    std::uint32_t day_road = 0;

    /// The board's sea: a radial ramp about `sea_centre` on the disc (x right, y up, in radii) out to `sea_radius`.
    std::array<LadderStop, 3> sea{};
    std::array<double, 2> sea_centre{};
    double sea_radius = 0;
    /// The board's land: a ramp down the disc, 0 at its top; and the coast, the line along its edge (its width, dp),
    /// which comes with the ramps.
    std::array<LadderStop, 5> land{};
    LadderTint coast;
    double coast_width = 0;
    /// The ice: glaciers, ice shelves and the south pole's cap, below the globe and on it. The board's Antarctic stop,
    /// the last of its land's, which ice keeps from a glacier in view to the globe: it does not take the land's ramp
    /// (the user's yes of 3 November, kept to the globe on 11 November).
    std::uint32_t ice = 0;

    /// The night: its colour and the caps where the sun stands under each altitude (degrees), each over the last; how
    /// soft their edges are below the globe (degrees of the sun's altitude; a pixel on it).
    std::uint32_t night = 0;
    std::array<double, 4> cap_altitudes{};
    std::array<double, 4> cap_opacities{};
    double cap_soft = 0;

    /// A city's light: its radius by its place's size (dp: a place of `light_sizes` people or more is of the first
    /// size, or of the second), its colour and opacity in the deep night, the middle and the dusk, its halo (times its
    /// radius, at a share of its opacity), and the board's rule: the sun's depth under the horizon the lights begin
    /// at, the depth over which they come in (degrees), and the facing (the cosine to the view) they are whole from
    /// and are left out under.
    std::array<double, 3> light_radii{};
    std::array<std::int64_t, 2> light_sizes{};
    std::array<LadderTint, 3> lights{};
    double light_halo = 0;
    double light_halo_opacity = 0;
    double light_from = 0;
    double light_over = 0;
    double light_facing = 0;
    double light_least_facing = 0;

    /// The graticule over the water: its colour, its width (dp), its meridians' and parallels' steps (degrees), the
    /// latitude its parallels reach and the one its meridians end at.
    LadderTint graticule;
    double graticule_width = 0;
    double meridian_step = 0;
    double parallel_step = 0;
    double parallel_most = 0;
    double meridian_most = 0;

    /// The atmosphere's ring about the disc: where it begins, is brightest and ends (radii of the disc).
    LadderTint air;
    std::array<double, 3> air_at{};
    /// The dashed outline behind the disc and the rim over it: their widths (dp), the outline's dash and gap (dp).
    LadderTint outline;
    double outline_width = 0;
    std::array<double, 2> outline_dash{};
    LadderTint rim;
    double rim_width = 0;
    /// The shine: about `shine_centre` on the disc (x right, y up, radii) out to `shine_radius`.
    LadderTint shine;
    std::array<double, 2> shine_centre{};
    double shine_radius = 0;

    /// Space: its colour, and its glow about (`glow_centre` of the frame's width and height) from `glow` to space at
    /// `glow_end` of `glow_radius` times the frame's longer side.
    std::uint32_t space = 0;
    std::uint32_t glow = 0;
    std::array<double, 2> glow_centre{};
    double glow_end = 0;
    double glow_radius = 0;
};

} // namespace atlas::tiles
