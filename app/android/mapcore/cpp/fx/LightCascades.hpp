// The night's light on the ground by radiance cascades (ENGINE.md part 4, the night; the user, 12 November: "Yes, try
// it"): the street lamps' and the open places' light over a grid of the ground, cut by the buildings that stand in its
// way, where the renders draw each light's pool as an ellipse through every wall.
//
// The scene is flat, a grid of 1 m: a texel is solid where a building's footprint holds its centre, and each light is
// a disc of light_radius on the ground about its foot. Three cascades of probes hold the radiance that reaches a place
// from each direction's wedge: cascade i has a probe every 2^i texels and 16 · 4^i wedges, and its interval is [0, 2),
// [2, 6) or [6, 12) m from the probe. A light is shared between the cascades by its distance from each probe, the
// near ones the first's and the far ones the last's, by weights that sum to one and change slowly against the probes'
// spacing, so nothing rings where one cascade hands a light to the next. A cascade's radiance in a wedge is what its
// own lights fill of it (a disc's share of the wedge, exact, where the straight line to the disc is free of
// buildings), then, where the wedge's middle ray crosses its interval free, the cascade above's radiance in its four
// finer wedges there, between its four nearest probes that stand in the open. A texel's light is the mean of its
// probe's wedges.
//
// A light's radiance is its pool's: at d metres from its foot, what makes the mean over the wedges the optical depth
// -ln(1 - a(d)) of the pool's own gradient a (the renders' `pool` of 10 m and `openpool` of 11 m). So a lone light in
// the open lays the renders' pool, lights in the open lay their pools one over the other as the renders do
// (1 - e^-depth is their composite's alpha), and a building takes away the part of each it hides, softly where the
// probes of a cascade see it differently.
//
// No sample is random: a disc's share of a wedge is its angle's, so the light has no noise and no spokes, and the same
// grid gives the same result wherever floats round as IEEE 754's do.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace atlas::fx {

/// The kinds of light a grid holds, each its own fluence: the street lamps, the open places.
inline constexpr std::size_t light_kinds = 2;
inline constexpr std::uint8_t light_lamp = 0;
inline constexpr std::uint8_t light_open = 1;

/// A kind's pool: how far it reaches from its light's foot (texels), and its gradient's alpha at the foot and at
/// `middle` of the way (none at its edge).
struct LightPool {
    float reach = 0;
    float middle = 0;
    float foot = 0;
    float mid = 0;
};
/// The renders' `pool` (amsterdam.py:812) and `openpool` (:818).
inline constexpr std::array<LightPool, light_kinds> light_pools{
    {{.reach = 10.0F, .middle = 0.5F, .foot = 0.55F, .mid = 0.18F},
     {.reach = 11.0F, .middle = 0.45F, .foot = 0.75F, .mid = 0.28F}}};

/// A light's disc on the ground: its radius (texels).
inline constexpr float light_radius = 1.2F;
/// How far a light reaches (texels), and no farther: a grid's lights within this of a texel are all that light it, and
/// its solids within it all that shade it.
inline constexpr float light_reach = 12.0F;
/// The cascades: their count, the first's wedges, the bounds of the intervals their wedges' middle rays cross, and
/// where each cascade's share of a light rises from nothing to all of it, by the light's distance (texels): the second's
/// over 0 to 4, the third's over 4 to 12. A rise is two of its cascade's probes' spacings wide, so the probes carry it
/// between them without a ring.
inline constexpr std::size_t light_cascades = 3;
inline constexpr std::uint32_t light_wedges = 16;
inline constexpr std::array<float, 4> light_bounds{0.0F, 2.0F, 6.0F, 12.0F};
inline constexpr std::array<std::array<float, 2>, 3> light_rises{{{0.0F, 0.0F}, {0.0F, 4.0F}, {4.0F, 12.0F}}};

/// A light: its foot in the grid (texels: x to the right, z down, a texel's centre at its half) and its kind.
struct LightEmitter {
    float x = 0;
    float z = 0;
    std::uint8_t kind = light_lamp;
};

/// A piece of the ground: `width` × `height` texels (each a multiple of 4), row by row.
struct LightGrid {
    std::int32_t width = 0;
    std::int32_t height = 0;
    std::vector<std::uint8_t> solid; ///< 1 where a building holds the texel's centre
    std::vector<LightEmitter> emitters;
};

/// What the cascades did, for the bake's measures.
struct LightCost {
    std::uint64_t pairs = 0; ///< a probe's light, looked at
    std::uint64_t rays = 0;  ///< a wedge's middle ray, marched
    std::uint64_t steps = 0; ///< the texels those rays and the lights' lines read
};

/// Each texel's light by kind (light_kinds floats a texel, row by row): the optical depth of the pools that reach it,
/// 0 in a solid.
[[nodiscard]] std::vector<float> light_depths(const LightGrid& grid, LightCost* cost = nullptr);

/// A kind's pool's alpha `d` texels from its light's foot (the gradient between its stops, as the renders' ellipse
/// has it along its axes).
[[nodiscard]] float light_alpha(std::uint8_t kind, float d) noexcept;
/// The alpha of light of optical depth `depth`: 1 - e^-depth, the pools one over the other.
[[nodiscard]] float light_over(float depth) noexcept;

} // namespace atlas::fx
