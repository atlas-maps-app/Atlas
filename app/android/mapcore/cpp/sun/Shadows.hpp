// Sunlight over prisms (plan-vector D8, K8): the port's Real.Shadows.lit (render/real.py:243), the march that decides
// sun or shade at every sample point. From the point it steps toward the sun from 0.8 m in steps of 1.6 m, up to 220 m
// or until it climbs past the scene's tallest solid, and at each step asks the 16 m cell under it for the solids whose
// box holds the step, lower than none of whose tops it is, and whose footprint holds it; the first such solid (but the
// one left out, the sample's own building) shades the point. Every operation is the port's, in its order, in double
// with contraction off, so each answer is the port's bit.
#pragma once

#include "sun/Grid.hpp"

namespace atlas::sun {

/// The sun's direction: the unit vector toward the sun as the port computes it (`_L`, Living.sunVector), or straight
/// down when the sun is set.
struct Sun {
    double x = 0.0;
    double y = -1.0;
    double z = 0.0;

    friend constexpr bool operator==(const Sun&, const Sun&) = default;
};

/// How far a march goes, metres (Shadows.lit's reach).
inline constexpr double reach = 220.0;

/// Whether the ray from (px, py, pz) toward `sun` leaves every solid of `grid` within `reach_m` metres, `skip` left
/// out: Shadows.lit. False whenever the sun is not above the horizon.
[[nodiscard]] bool lit(const Grid& grid, const Sun& sun, double px, double py, double pz, PrismId skip = no_prism,
                       double reach_m = reach) noexcept;

/// What a march did, for the benchmarks and the tests: the steps taken and the cells read.
struct MarchCount {
    std::uint64_t steps = 0;
    std::uint64_t cells = 0;
    std::uint64_t tests = 0; ///< solids whose footprint was tested
};

/// lit, counting its work into `count`.
[[nodiscard]] bool lit_counted(const Grid& grid, const Sun& sun, double px, double py, double pz, PrismId skip,
                               MarchCount& count, double reach_m = reach) noexcept;

} // namespace atlas::sun
