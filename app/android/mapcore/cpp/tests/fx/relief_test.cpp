// The relief's light and bake on the CPU (fx/Relief.hpp; shaders/shared/relief.slang's own text): the style file reads,
// to the values the core falls back on; flat open ground takes exactly 1 at every moment, so the map's palette holds
// on the flat; on open ground the light is the renders' (render/relief.py: 1 + k·(max(0, n·L) − L_y) under a clear
// day's sun, the north-west sky's under an overcast one, straight above at night), eased where they clip; a hollow
// darkens with its sky-view, and not without it; the shade takes the tint; a tile's bake gives a plane its normal and
// a whole sky, a pit less sky, and a tile's edge texels are its neighbour's own.
#include "fx/Relief.hpp"
#include "gen/data/relief_style.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <numbers>
#include <span>
#include <string_view>
#include <vector>

using namespace atlas;

namespace {

/// A theme of sun vector `l` with `flags` (theme.slang's THEME_ bits).
shaders::Theme theme_of(std::array<double, 3> l, std::uint32_t flags) {
    shaders::Theme th{};
    th.flags.x = flags;
    const auto words = [](double v) {
        const auto bits = std::bit_cast<std::uint64_t>(v);
        return std::array<std::uint32_t, 2>{static_cast<std::uint32_t>(bits), static_cast<std::uint32_t>(bits >> 32U)};
    };
    th.bits_sky_lx.z = words(l[0])[0];
    th.bits_sky_lx.w = words(l[0])[1];
    th.bits_lyz.x = words(l[1])[0];
    th.bits_lyz.y = words(l[1])[1];
    th.bits_lyz.z = words(l[2])[0];
    th.bits_lyz.w = words(l[2])[1];
    return th;
}

/// The Dunes board's sun: 26 degrees up from azimuth 222 (x east, y up, z south).
std::array<double, 3> board_sun() {
    const double az = 222.0 * std::numbers::pi / 180.0;
    const double el = 26.0 * std::numbers::pi / 180.0;
    return {std::sin(az) * std::cos(el), std::sin(el), -std::cos(az) * std::cos(el)};
}

/// The unit normal of a surface rising `gx` east and `gz` south (metres a metre).
shaders::float3 normal_of(double gx, double gz) {
    const double inverse = 1.0 / std::sqrt((gx * gx) + 1.0 + (gz * gz));
    const shaders::float3 n = {static_cast<float>(-gx * inverse), static_cast<float>(inverse),
                               static_cast<float>(-gz * inverse)};
    return n;
}

const shaders::float3 up = {0.0F, 1.0F, 0.0F};

/// A raster of `side` and `apron` over tile `key`, its sample (r, c) at `height(r, c)` steps of `step` metres.
struct Raster {
    std::vector<std::int32_t> samples;
    tiles::Heights heights;

    template <class F>
    Raster(tiles::TileKey key, std::uint16_t side, std::uint16_t apron, double step, F&& height) {
        const std::int32_t w = side + (2 * apron);
        samples.resize(static_cast<std::size_t>(w) * static_cast<std::size_t>(w));
        std::int32_t top = 0;
        for (std::int32_t r = 0; r < w; ++r) {
            for (std::int32_t c = 0; c < w; ++c) {
                const std::int32_t v = height(r - apron, c - apron);
                samples[(static_cast<std::size_t>(r) * static_cast<std::size_t>(w)) + static_cast<std::size_t>(c)] = v;
                top = std::max(top, v);
            }
        }
        heights.tile = key;
        heights.side = side;
        heights.apron = apron;
        heights.step = step;
        heights.base = 0;
        heights.max = static_cast<std::uint32_t>(top);
        heights.samples = samples;
    }
};

} // namespace

TEST_CASE("the relief's style file reads, to the core's own defaults") {
    const auto read = fx::read_relief_style(std::as_bytes(std::span(data::relief_style)));
    REQUIRE(read.has_value());
    CHECK(*read == fx::ReliefStyle{});
    CHECK(fx::relief_style() == fx::ReliefStyle{});
    // a file that lacks a value, or holds one out of its range, is refused whole
    const auto refused = [](std::string_view text) {
        return !fx::read_relief_style(std::as_bytes(std::span(text))).has_value();
    };
    CHECK(refused("{}"));
    CHECK(refused("[1]"));
    std::string file(reinterpret_cast<const char*>(data::relief_style.data()), // NOLINT: the file's bytes as text
                     data::relief_style.size());
    const std::size_t sun = file.find("\"sun\": 0.8");
    REQUIRE(sun != std::string::npos);
    file.replace(sun, 10, "\"sun\": 1.2");
    CHECK(refused(file));
}

TEST_CASE("flat open ground keeps its colours at every moment") {
    const fx::ReliefStyle& style = fx::relief_style();
    const std::array<double, 3> noon{0.05, 0.99, 0.1};
    const std::array<double, 3> down{0.0, -1.0, 0.0};
    for (const bool sky_view : {true, false}) {
        for (const shaders::Theme& th :
             {theme_of(board_sun(), 0U), theme_of(noon, 0U), theme_of(board_sun(), shaders::THEME_OVERCAST),
              theme_of(down, shaders::THEME_NIGHT), theme_of({0.7, -0.02, 0.7}, 0U)}) {
            const shaders::ReliefLight l = fx::relief_light(th, style, sky_view, 1.0);
            CHECK(shaders::relief_factor(l, up, 1.0F) == doctest::Approx(1.0).epsilon(1e-6));
            const shaders::float3 c = shaders::relief_colour(l, shaders::relief_factor(l, up, 1.0F));
            CHECK(c.x == doctest::Approx(1.0).epsilon(1e-6));
            CHECK(c.z == doctest::Approx(1.0).epsilon(1e-6));
            // and a flat texel as the bake writes it, through the lanes a sampler gives
            const shaders::float3 at = shaders::relief_at(l, fx::relief_lanes(fx::relief_texel(0.0, 0.0, 1.0)), 1.0F);
            CHECK(std::abs(at.x - 1.0F) < 1e-5F);
            CHECK(std::abs(at.y - 1.0F) < 1e-5F);
            CHECK(std::abs(at.z - 1.0F) < 1e-5F);
        }
    }
}

TEST_CASE("on open ground the light is the renders', eased where they clip") {
    const fx::ReliefStyle& style = fx::relief_style();
    const std::array<double, 3> sun = board_sun();
    const std::array<double, 3> side{-0.6 / std::sqrt(1.53), 0.9 / std::sqrt(1.53), -0.6 / std::sqrt(1.53)};
    const shaders::ReliefLight day = fx::relief_light(theme_of(sun, 0U), style, true, 1.0);
    const shaders::ReliefLight overcast = fx::relief_light(theme_of(sun, shaders::THEME_OVERCAST), style, true, 1.0);
    const shaders::ReliefLight night =
        fx::relief_light(theme_of({0.0, -1.0, 0.0}, shaders::THEME_NIGHT), style, true, 1.0);
    for (const auto& [gx, gz] : {std::pair{0.2, 0.0}, std::pair{-0.2, 0.1}, std::pair{0.05, -0.3}, std::pair{0.6, 0.6},
                                 std::pair{-0.5, -0.4}, std::pair{0.0, 0.15}}) {
        const shaders::float3 n = normal_of(gx, gz);
        const double nl = (n.x * sun[0]) + (n.y * sun[1]) + (n.z * sun[2]);
        const double nw = (n.x * side[0]) + (n.y * side[1]) + (n.z * side[2]);
        // render/relief.py: f = 1 + k·(max(0, n·L) − L_y), clipped to 0.58 to 1.16; the engine's keeps it below the
        // shoulder and above its own floor
        const double sunny = 1.0 + (0.8 * (std::max(0.0, nl) - sun[1]));
        const float f = shaders::relief_factor(day, n, 1.0F);
        if (sunny <= style.shoulder) {
            CHECK(f == doctest::Approx(std::max(style.floor, sunny)).epsilon(1e-4));
        } else {
            CHECK(f > style.shoulder);
            CHECK(f <= style.most);
        }
        const double grey = 1.0 + (0.55 * (std::max(0.0, nw) - side[1]));
        const float g = shaders::relief_factor(overcast, n, 1.0F);
        if (grey <= style.shoulder) CHECK(g == doctest::Approx(std::max(style.floor, grey)).epsilon(1e-4));
        const double dark = 1.0 + (0.25 * (n.y - 1.0));
        CHECK(shaders::relief_factor(night, n, 1.0F) == doctest::Approx(dark).epsilon(1e-4));
    }
    // a slope full to the sun: past the renders' clip the light still rises with the slope, and never passes its most
    float last = 0.0F;
    for (double g = 0.0; g < 1.2; g += 0.1) {
        const float f = shaders::relief_factor(day, normal_of(-g * sun[0], -g * sun[2]), 1.0F);
        CHECK(f <= static_cast<float>(style.most));
        CHECK(f >= last);
        last = f;
    }
    CHECK(last > 1.15F);
}

TEST_CASE("a hollow darkens with its sky-view, and the shade takes the tint") {
    const fx::ReliefStyle& style = fx::relief_style();
    const shaders::Theme th = theme_of(board_sun(), 0U);
    const shaders::ReliefLight with = fx::relief_light(th, style, true, 1.0);
    const shaders::ReliefLight without = fx::relief_light(th, style, false, 1.0);
    const float open = shaders::relief_factor(with, up, 1.0F);
    const float hollow = shaders::relief_factor(with, up, 0.8F);
    CHECK(hollow < open - 0.1F);
    CHECK(shaders::relief_factor(without, up, 0.8F) == doctest::Approx(open).epsilon(1e-6));
    // the sky's share of the light by the sky-view, and the relief factor over all of it
    const double s = with.sun.w;
    const double expected = ((0.8 + (s * with.sun.y)) / (1.0 + (s * with.sun.y))) * (1.0 + (style.sky_view * -0.2));
    CHECK(hollow == doctest::Approx(expected).epsilon(1e-4));
    // the tint: none in light, the renders' cool by q = (1 − f)·1.6 in shade
    const shaders::float3 lit = shaders::relief_colour(with, 1.05F);
    CHECK(lit.x == doctest::Approx(1.05));
    CHECK(lit.z == doctest::Approx(1.05));
    const shaders::float3 shade = shaders::relief_colour(with, 0.8F);
    CHECK(shade.x == doctest::Approx(0.8 * (1.0 + ((0.93 - 1.0) * 0.32))).epsilon(1e-5));
    CHECK(shade.z == doctest::Approx(0.8 * (1.0 + ((1.07 - 1.0) * 0.32))).epsilon(1e-5));
    // the strength: none of the light at 0, a tile's and the frame's multiplied
    const shaders::float3 lanes = fx::relief_lanes(fx::relief_texel(-0.3, 0.2, 0.7));
    const shaders::float3 none = shaders::relief_at(with, lanes, 0.0F);
    CHECK(none.x == 1.0F);
    CHECK(none.y == 1.0F);
    const shaders::ReliefLight half = fx::relief_light(th, style, true, 0.5);
    const shaders::float3 whole = shaders::relief_at(with, lanes, 1.0F);
    const shaders::float3 halved = shaders::relief_at(half, lanes, 1.0F);
    CHECK(halved.x - 1.0F == doctest::Approx(0.5F * (whole.x - 1.0F)).epsilon(1e-4));
    CHECK(fx::relief_strength(style, 3'000.0) == 1.0);
    CHECK(fx::relief_strength(style, 21'000.0) == doctest::Approx(0.5));
    CHECK(fx::relief_strength(style, 30'000.0) == 0.0);
}

TEST_CASE("a tile's bake: a plane's normal and whole sky, a pit's less, and edges that are the neighbour's own") {
    const fx::ReliefStyle& style = fx::relief_style();
    const tiles::TileKey key(14, 8412, 5384); // the dunes' latitude
    constexpr std::uint16_t side = 65;
    constexpr std::uint16_t apron = 8;
    const double spacing = fx::relief_spacing(key.z, key.y, side, side / 2);
    // 40,075 km of equator over 2^14 tiles of 64 steps, at 52.4 degrees north
    CHECK(
        spacing ==
        doctest::Approx((40'075'016.686 * std::cos(52.4 * std::numbers::pi / 180.0)) / (16384.0 * 64.0)).epsilon(2e-3));

    SUBCASE("flat ground") {
        const Raster flat(key, side, apron, 0.0625, [](std::int32_t, std::int32_t) { return 40; });
        const fx::ReliefBake bake = fx::bake_relief(flat.heights, style);
        REQUIRE(bake.side == side);
        REQUIRE(bake.texels.size() == std::size_t{side} * side);
        for (const std::uint32_t t : bake.texels) REQUIRE(t == fx::relief_texel(0.0, 0.0, 1.0));
    }
    SUBCASE("a plane rising east and south") {
        // a step of height a sample east, two a sample south: the slopes over the spacing
        const double step = 0.5;
        const Raster plane(key, side, apron, step, [](std::int32_t r, std::int32_t c) { return 100 + c + (2 * r); });
        const fx::ReliefBake bake = fx::bake_relief(plane.heights, style);
        const std::uint32_t texel = bake.texels[(32U * side) + 32U];
        const shaders::float3 lanes = fx::relief_lanes(texel);
        const shaders::float3 n = shaders::relief_normal({lanes.x, lanes.y});
        const shaders::float3 expected = normal_of(step / spacing, (2.0 * step) / spacing);
        CHECK(std::abs(n.x - expected.x) < 0.002F);
        CHECK(std::abs(n.y - expected.y) < 0.002F);
        CHECK(std::abs(n.z - expected.z) < 0.002F);
        // a plane's horizons: up the slope on one side, the horizontal on the other; its sky-view under 1, the same
        // at every texel whose reach lies in the raster
        CHECK(lanes.z < 1.0F);
        CHECK(lanes.z > 0.9F);
        CHECK(bake.texels[(32U * side) + 50U] == texel);
        CHECK(bake.texels[32U * side] == texel);
        // the corners' too, to a level (a row's spacing is its latitude's): the apron holds their neighbours
        for (const std::uint32_t corner : {bake.texels.front(), bake.texels.back()}) {
            const shaders::float3 at = fx::relief_lanes(corner);
            CHECK(std::abs(at.x - lanes.x) < 0.003F);
            CHECK(std::abs(at.y - lanes.y) < 0.003F);
            CHECK(std::abs(at.z - lanes.z) < 0.003F);
        }
    }
    SUBCASE("a pit sees less of the sky than its rim") {
        const Raster pit(key, side, apron, 4.0, [](std::int32_t r, std::int32_t c) {
            const std::int32_t d = std::max(std::abs(r - 32), std::abs(c - 32));
            return 4 * std::min(d, 6); // 96 m deep, 6 samples (140 m) a side
        });
        const fx::ReliefBake bake = fx::bake_relief(pit.heights, style);
        const float bottom = fx::relief_lanes(bake.texels[(32U * side) + 32U]).z;
        const float rim = fx::relief_lanes(bake.texels[(32U * side) + 40U]).z;
        const float plain = fx::relief_lanes(bake.texels[(5U * side) + 5U]).z;
        CHECK(plain == 1.0F);
        CHECK(rim == 1.0F);
        CHECK(bottom < 0.9F);
        // its flat bottom's normal is straight up
        const shaders::float3 lanes = fx::relief_lanes(bake.texels[(32U * side) + 32U]);
        CHECK(shaders::relief_normal({lanes.x, lanes.y}).y > 0.999F);
    }
    SUBCASE("two neighbours' shared edge") {
        // one surface over two tiles side by side: the east tile's samples are the west's, 64 columns on
        const auto surface = [](std::int32_t r, std::int32_t c) {
            return 200 + static_cast<std::int32_t>(std::lround(60.0 * std::sin(0.11 * c) * std::cos(0.07 * r)));
        };
        const Raster west(key, side, apron, 0.125, surface);
        const Raster east(tiles::TileKey(key.z, key.x + 1, key.y), side, apron, 0.125,
                          [&surface](std::int32_t r, std::int32_t c) { return surface(r, c + 64); });
        const fx::ReliefBake a = fx::bake_relief(west.heights, style);
        const fx::ReliefBake b = fx::bake_relief(east.heights, style);
        for (std::uint32_t r = 0; r < side; ++r) REQUIRE(a.texels[(r * side) + 64U] == b.texels[r * side]);
    }
    SUBCASE("without an apron the edge's differences are one-sided") {
        const Raster plane(key, side, 0, 0.5, [](std::int32_t r, std::int32_t c) { return 100 + c + (2 * r); });
        const fx::ReliefBake bake = fx::bake_relief(plane.heights, style);
        const shaders::float3 middle = fx::relief_lanes(bake.texels[(32U * side) + 32U]);
        const shaders::float3 corner = fx::relief_lanes(bake.texels[0]);
        CHECK(std::abs(corner.x - middle.x) < 0.003F);
        CHECK(std::abs(corner.y - middle.y) < 0.003F);
    }
}
