// The night's light on the ground by radiance cascades (fx/LightCascades.hpp) and a tile's bake of it
// (passes/LightMap.hpp), on the CPU: a lone light in the open lays the renders' pool, lights in the open lay their
// pools one over the other, a building takes away what it hides and nothing else, the same grid gives the same result,
// and a tile's map is the grid's whatever piece a texel fell in.
#include "fx/LightCascades.hpp"
#include "passes/LightMap.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

using namespace atlas;

namespace {

/// An empty grid of `side` texels.
[[nodiscard]] fx::LightGrid grid_of(std::int32_t side) {
    fx::LightGrid g;
    g.width = side;
    g.height = side;
    g.solid.assign(static_cast<std::size_t>(side) * static_cast<std::size_t>(side), 0);
    return g;
}

/// The distance from texel (x, z)'s centre to `e`'s foot.
[[nodiscard]] float distance(std::int32_t x, std::int32_t z, const fx::LightEmitter& e) {
    return std::hypot(static_cast<float>(x) + 0.5F - e.x, static_cast<float>(z) + 0.5F - e.z);
}

} // namespace

TEST_CASE("fx: a lone light in the open lays its pool, and lights in the open their pools one over the other") {
    constexpr std::int32_t side = 64;
    for (const std::uint8_t kind : {fx::light_lamp, fx::light_open}) {
        // off the texels' and the probes' lattices
        fx::LightGrid g = grid_of(side);
        g.emitters.push_back({.x = 31.3F, .z = 29.7F, .kind = kind});
        fx::LightCost cost;
        const std::vector<float> depths = fx::light_depths(g, &cost);
        REQUIRE(depths.size() == static_cast<std::size_t>(side) * side * fx::light_kinds);
        double worst = 0;
        std::size_t lit = 0;
        for (std::int32_t z = 0; z < side; ++z) {
            for (std::int32_t x = 0; x < side; ++x) {
                const std::size_t at = ((static_cast<std::size_t>(z) * side) + static_cast<std::size_t>(x)) * 2;
                const float want = fx::light_alpha(kind, distance(x, z, g.emitters[0]));
                worst = std::max(worst, static_cast<double>(std::abs(fx::light_over(depths[at + kind]) - want)));
                if (want > 0) ++lit;
                // nothing of the other kind
                CHECK(depths[at + (1 - kind)] == 0.0F);
            }
        }
        MESSAGE("a lone " << std::string(kind == fx::light_lamp ? "lamp" : "open place") << ": " << lit
                          << " texels in its pool, the worst " << worst << " of alpha from the pool's gradient; "
                          << cost.pairs << " pairs, " << cost.rays << " rays, " << cost.steps << " steps");
        CHECK(lit > 250);
        CHECK(worst < 1e-4);
    }
    // two lamps 7 m apart, and an open place between them: each kind the composite of its pools
    fx::LightGrid g = grid_of(side);
    g.emitters.push_back({.x = 28.2F, .z = 30.4F, .kind = fx::light_lamp});
    g.emitters.push_back({.x = 35.1F, .z = 31.9F, .kind = fx::light_lamp});
    g.emitters.push_back({.x = 31.6F, .z = 36.5F, .kind = fx::light_open});
    const std::vector<float> depths = fx::light_depths(g);
    CHECK(depths == fx::light_depths(g));
    double worst = 0;
    for (std::int32_t z = 0; z < side; ++z) {
        for (std::int32_t x = 0; x < side; ++x) {
            const std::size_t at = ((static_cast<std::size_t>(z) * side) + static_cast<std::size_t>(x)) * 2;
            const float a = fx::light_alpha(fx::light_lamp, distance(x, z, g.emitters[0]));
            const float b = fx::light_alpha(fx::light_lamp, distance(x, z, g.emitters[1]));
            const float c = fx::light_alpha(fx::light_open, distance(x, z, g.emitters[2]));
            worst =
                std::max(worst, static_cast<double>(std::abs(fx::light_over(depths[at]) - (1 - ((1 - a) * (1 - b))))));
            worst = std::max(worst, static_cast<double>(std::abs(fx::light_over(depths[at + 1]) - c)));
        }
    }
    MESSAGE("three lights in the open: the worst " << worst << " of alpha from their pools' composite");
    CHECK(worst < 1e-4);
}

TEST_CASE("fx: a building takes away the light it hides, and none beside it") {
    constexpr std::int32_t side = 64;
    fx::LightGrid g = grid_of(side);
    const fx::LightEmitter lamp{.x = 24.4F, .z = 32.3F, .kind = fx::light_lamp};
    g.emitters.push_back(lamp);
    // a wall of houses 4 m east of the lamp, 2 m deep and 9 m long
    for (std::int32_t z = 28; z < 37; ++z) {
        for (std::int32_t x = 28; x < 30; ++x)
            g.solid[(static_cast<std::size_t>(z) * side) + static_cast<std::size_t>(x)] = 1;
    }
    const std::vector<float> depths = fx::light_depths(g);
    const auto alpha = [&](std::int32_t x, std::int32_t z) {
        return fx::light_over(depths[((static_cast<std::size_t>(z) * side) + static_cast<std::size_t>(x)) * 2]);
    };
    std::size_t behind = 0;
    std::size_t before = 0;
    double leaked = 0;
    double lost = 0;
    for (std::int32_t z = 0; z < side; ++z) {
        for (std::int32_t x = 0; x < side; ++x) {
            const float want = fx::light_alpha(fx::light_lamp, distance(x, z, lamp));
            CHECK(alpha(x, z) >= 0.0F);
            CHECK(alpha(x, z) <= want + 0.02F); // (a shaded texel takes its probes' light, a little past its own)
            if (g.solid[(static_cast<std::size_t>(z) * side) + static_cast<std::size_t>(x)] != 0) {
                CHECK(alpha(x, z) == 0.0F);
                continue;
            }
            if (want <= 0) continue;
            if (x >= 30 && z >= 31 && z < 34) {
                // behind the wall's middle: all of the light's disc is hidden
                ++behind;
                leaked = std::max(leaked, static_cast<double>(alpha(x, z)));
            } else if (x < 24) {
                // west of the lamp: the house hides none of it
                ++before;
                lost = std::max(lost, static_cast<double>(want - alpha(x, z)));
            }
        }
    }
    MESSAGE("a lamp 4 m from a wall of houses: " << behind << " texels behind it, the most light there " << leaked
                                                 << "; " << before
                                                 << " texels on the lamp's other side, the most lost there " << lost);
    CHECK(behind >= 4);
    CHECK(leaked == 0.0);
    CHECK(before > 100);
    CHECK(lost < 1e-4);
    // between the lamp and the wall, and beside the wall's end where the light goes by, the pool is whole within a
    // hundredth of alpha (a probe in the wall, or one the wall shades, gives its neighbours less of its share)
    CHECK(std::abs(alpha(26, 32) - fx::light_alpha(fx::light_lamp, distance(26, 32, lamp))) < 0.01F);
    CHECK(alpha(26, 40) > 0.04F);
    CHECK(std::abs(alpha(26, 40) - fx::light_alpha(fx::light_lamp, distance(26, 40, lamp))) < 0.01F);
}

TEST_CASE(
    "passes: a tile's light map is the cascades' over its grid, its lit blocks alone, whatever piece a texel is in") {
    passes::LightJob job;
    job.blocks_x = 9; // 288 texels: three pieces across, the last cut short
    job.blocks_z = 9;
    // lamps on and about the pieces' seams (128, 256), one in a corner block, a house between two of them
    job.emitters.push_back({.x = 127.6F, .z = 60.2F, .kind = fx::light_lamp});
    job.emitters.push_back({.x = 128.9F, .z = 129.4F, .kind = fx::light_lamp});
    job.emitters.push_back({.x = 255.5F, .z = 200.5F, .kind = fx::light_open});
    job.emitters.push_back({.x = 10.5F, .z = 280.5F, .kind = fx::light_lamp});
    for (const auto& [x, z] : {std::pair{120.0F, 64.0F}, {136.0F, 64.0F}, {136.0F, 70.0F}, {120.0F, 70.0F}}) {
        job.ring_points.push_back(x);
        job.ring_points.push_back(z);
    }
    job.ring_first.push_back(4);
    const passes::LightTile tile = passes::bake_light(job);
    CHECK(tile.index.size() == 81);
    CHECK(tile.blocks.size() == std::size_t{tile.lit()} * passes::light_slot_words);
    CHECK(tile.pieces >= 4);
    // against one grid over the whole with the pieces' 24 texels about it (on the pieces' own lattice), as bytes: a
    // texel's light is the same whichever piece walked it, but for the last place of a float where a light's distance
    // was taken from another corner
    constexpr std::int32_t about = 24;
    constexpr std::int32_t whole = 288 + (2 * about);
    fx::LightGrid g = grid_of(whole);
    for (const fx::LightEmitter& e : job.emitters)
        g.emitters.push_back(
            {.x = e.x + static_cast<float>(about), .z = e.z + static_cast<float>(about), .kind = e.kind});
    for (std::int32_t z = 64; z < 70; ++z) {
        for (std::int32_t x = 120; x < 136; ++x)
            g.solid[(static_cast<std::size_t>(z + about) * whole) + static_cast<std::size_t>(x + about)] = 1;
    }
    const std::vector<float> depths = fx::light_depths(g);
    std::size_t lit = 0;
    std::size_t apart = 0;
    std::size_t nudged = 0;
    for (std::int32_t z = 0; z < 288; ++z) {
        for (std::int32_t x = 0; x < 288; ++x) {
            const std::size_t at =
                ((static_cast<std::size_t>(z + about) * whole) + static_cast<std::size_t>(x + about)) * 2;
            const std::array<std::uint8_t, 2> have = tile.at(x, z);
            for (std::size_t kind = 0; kind < 2; ++kind) {
                const long want = std::lround(std::clamp(fx::light_over(depths[at + kind]), 0.0F, 1.0F) * 255.0F);
                if (want > 0) ++lit;
                const long d = std::abs(want - static_cast<long>(have[kind]));
                if (d == 1) ++nudged;
                if (d > 1) ++apart;
            }
        }
    }
    MESSAGE("a tile of 9 × 9 blocks: " << tile.lit() << " lit, " << tile.pieces << " pieces walked in " << tile.ms
                                       << " ms; " << lit << " lit texels, " << nudged
                                       << " a level from the one grid's, " << apart << " farther");
    CHECK(nudged * 50 < lit);
    CHECK(lit > 900);
    CHECK(apart == 0);
    CHECK(tile.lit() >= 8);
    CHECK(tile.lit() <= 24);
    // the house's shade: dark just north of it, where the lamp south of it at 60.2 would reach in the open
    CHECK(tile.at(127, 71)[0] == 0);
    CHECK(tile.at(127, 61)[0] > 100);
    // the baker bakes the same on its own thread
    passes::LightBaker baker;
    baker.submit(7, job);
    baker.submit(9, passes::LightJob{.blocks_x = 2, .blocks_z = 2});
    baker.wait();
    CHECK(baker.pending() == 2);
    const auto done = baker.take();
    REQUIRE(done.size() == 2);
    CHECK(done[0].first == 7);
    CHECK(done[0].second->blocks == tile.blocks);
    CHECK(done[0].second->index == tile.index);
    CHECK(done[1].first == 9);
    CHECK(done[1].second->lit() == 0);
    CHECK(baker.pending() == 0);
}
