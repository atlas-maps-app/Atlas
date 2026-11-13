// Where a water's name stands when its own point does not show (ENGINE.md step 5, "Names"; scene/WaterStand.hpp): a
// tile's coarse water mask and the frame's water by it, the stand's rules, and the Markermeer from 100 km over
// Amsterdam on a phone, whose point lies past the frame's side while its water lies along the frame's east.
#include "base/pymath/PyMath.hpp"
#include "scene/WaterStand.hpp"
#include "scene/WorldNames.hpp"
#include "scene/WorldTiles.hpp"
#include "tests/base/require.hpp"
#include "view/Camera.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <format>
#include <numbers>
#include <optional>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace {

using namespace atlas;
using scene::NameKind;
using scene::WorldName;

constexpr double radian = std::numbers::pi / 180.0;

/// A vertex word of a tile's geometry: `u` east and `v` south of its box's north-west, 0 to 65,535.
[[nodiscard]] constexpr std::uint32_t at(std::uint32_t u, std::uint32_t v) { return u | (v << 16U); }

/// A tile's geometry of quads, each (west, north, east, south, colour) in vertex units.
[[nodiscard]] scene::WorldGeometry quads(std::span<const std::array<std::uint32_t, 5>> boxes) {
    scene::WorldGeometry g;
    std::vector<std::uint32_t> looks;
    for (const auto& b : boxes) {
        for (const std::uint32_t word :
             {at(b[0], b[1]), at(b[2], b[1]), at(b[2], b[3]), at(b[0], b[1]), at(b[2], b[3]), at(b[0], b[3])}) {
            g.words.push_back(word);
        }
        looks.push_back(b[4]);
        looks.push_back(b[4]);
    }
    g.triangles = static_cast<std::uint32_t>(looks.size());
    g.words.insert(g.words.end(), looks.begin(), looks.end());
    return g;
}

[[nodiscard]] bool bit(const scene::WaterMask& mask, std::size_t row, unsigned column) {
    return ((mask.at(row) >> column) & 1U) != 0;
}

[[nodiscard]] WorldName lake(std::string text, double area, double lat, double lon) {
    return {.kind = NameKind::lake, .lat = lat, .lon = lon, .text = std::move(text), .extent = area};
}

/// Whether (`lat`, `lon`) lies in the ring `ring` of (lat, lon) corners.
[[nodiscard]] bool inside(std::span<const std::pair<double, double>> ring, double lat, double lon) {
    bool in = false;
    for (std::size_t i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
        const auto& [lat0, lon0] = ring[i];
        const auto& [lat1, lon1] = ring[j];
        if ((lat0 > lat) != (lat1 > lat) && lon < lon0 + ((lon1 - lon0) * (lat - lat0) / (lat1 - lat0))) in = !in;
    }
    return in;
}

} // namespace

TEST_CASE("scene: a tile's water mask is set where its fills leave the water's colour at a cell's middle, and the "
          "frame's water at a place is its finest drawn tile's there") {
    using scene::world_palette::land;
    using scene::world_palette::water;
    // land over the whole tile; water over its east half; ice (a later fill) over the water's north-east quarter
    const std::array<std::array<std::uint32_t, 5>, 3> boxes{{{0, 0, 65'535, 65'535, land},
                                                             {32'768, 0, 65'535, 65'535, water},
                                                             {49'152, 0, 65'535, 16'384, scene::world_palette::ice}}};
    const scene::WaterMask mask = scene::world_water_mask(quads(boxes));
    CHECK_FALSE(bit(mask, 0, 0));
    CHECK_FALSE(bit(mask, 63, 31));
    CHECK(bit(mask, 0, 32));
    CHECK(bit(mask, 63, 63));
    CHECK(bit(mask, 15, 47));
    CHECK_FALSE(bit(mask, 0, 48)); // the ice's
    CHECK_FALSE(bit(mask, 15, 63));
    CHECK(bit(mask, 16, 48));
    std::size_t set = 0;
    for (const std::uint64_t row : mask) set += static_cast<std::size_t>(std::popcount(row));
    CHECK(set == (64U * 32U) - (16U * 16U));
    // a triangle smaller than a cell that holds no cell's middle sets none; no triangles, none
    const std::array<std::array<std::uint32_t, 5>, 1> speck{{{100, 100, 400, 400, water}}};
    for (const std::uint64_t row : scene::world_water_mask(quads(speck))) CHECK(row == 0);
    for (const std::uint64_t row : scene::world_water_mask({})) CHECK(row == 0);

    // a tile of 4° by 4° at (52 N, 4 E), drawn whole; a finer tile over its north-west cell, all land
    scene::WorldTile coarse;
    coarse.key = tiles::TileKey(6, 0, 0);
    coarse.box = {500'000'000, 40'000'000, 540'000'000, 80'000'000};
    coarse.water = mask;
    scene::WorldTile fine;
    fine.key = tiles::TileKey(8, 0, 0);
    fine.box = {530'000'000, 40'000'000, 540'000'000, 50'000'000};
    scene::WorldFrame frame;
    frame.draws.push_back({.tile = &coarse, .cells = 0xFFFEULL, .fade = 1.0F});
    CHECK(scene::world_water_at(frame, 51.0, 7.0) == std::optional(true));
    CHECK(scene::world_water_at(frame, 51.0, 5.0) == std::optional(false));
    CHECK(scene::world_water_at(frame, 53.9, 7.9) == std::optional(false)); // the ice
    CHECK_FALSE(scene::world_water_at(frame, 53.5, 4.5).has_value());       // its first cell is not drawn
    CHECK_FALSE(scene::world_water_at(frame, 49.0, 5.0).has_value());       // off the tile
    CHECK_FALSE(scene::world_water_at(frame, 51.0, 9.0).has_value());
    frame.draws.push_back({.tile = &fine, .cells = 0xFFFFULL, .fade = 1.0F});
    CHECK(scene::world_water_at(frame, 53.5, 4.5) == std::optional(false));
    fine.water.fill(~0ULL);
    CHECK(scene::world_water_at(frame, 53.5, 4.5) == std::optional(true));
    CHECK(scene::world_water_at(frame, 51.0, 7.0 + 360.0) == std::optional(true)); // a longitude a turn on
    // a pole's cap and the plain land say nothing
    scene::WorldTile cap;
    cap.cap = 1;
    cap.box = coarse.box;
    scene::WorldFrame caps;
    caps.draws.push_back({.tile = &cap, .cells = 1, .fade = 1.0F});
    CHECK_FALSE(scene::world_water_at(caps, 51.0, 7.0).has_value());
}

TEST_CASE("scene: a water's stand is the nearest point of its water to its own that the frame shows clear of its "
          "edges by the name's measures, joined to its point by water") {
    // A frame of 1,000 by 2,000 px straight down on (0, 0), 100 m a px; a line's step 800 m (8 px).
    const scene::StandFrame frame{
        .width = 1'000.0, .height = 2'000.0, .step = 800.0, .px = 0.01, .lat = 0.0, .lon = 0.0};
    const scene::WaterStands::Point point = [](double lat, double lon) -> std::optional<view::Vec2> {
        return view::Vec2{500.0 + (lon * 111'320.0 / 100.0), 1'000.0 - (lat * 110'540.0 / 100.0)};
    };
    // a lake east of 20 km east of the target (x from 700 px on), from 60 km south to 60 km north; its point lies
    // 70 km east, 200 px past the frame's side
    const double shore = 20'000.0 / 111'320.0;
    bool dike = false;
    const scene::WaterStands::Water water = [&](double lat, double lon) -> std::optional<bool> {
        if (std::abs(lat) > 60'000.0 / 110'540.0) return false;
        // (a dike across it at 45 km east, when built)
        if (dike && std::abs(lon - (45'000.0 / 111'320.0)) < 0.01) return false;
        return lon >= shore;
    };
    const WorldName big = lake("Groot Meer", 6.0e9, 0.0, 70'000.0 / 111'320.0);
    scene::WaterStands stands;
    stands.begin(frame, &point, &water);
    CHECK_FALSE(stands.on_frame(big.lat, big.lon, 0.0));
    CHECK(stands.on_frame(0.0, 0.0, 400.0));
    CHECK_FALSE(stands.on_frame(0.0, 0.0, 501.0));
    // a name 100 px to each side, 50 px high: on the way from the lake's point to the camera's target, at the
    // line's first step that leaves the name its half from the frame's side (x up to 900 px)
    const std::optional<scene::NamePlace> stand = stands.find(big, 100.0, 50.0);
    ATLAS_REQUIRE(stand.has_value());
    CHECK(stand->stand);
    const view::Vec2 px = point(stand->lat, stand->lon).value_or(view::Vec2{});
    CHECK(px.x <= 900.0);
    CHECK(px.x > 892.0);
    CHECK(px.y == doctest::Approx(1'000.0));
    CHECK(water(stand->lat, stand->lon) == std::optional(true));
    const std::uint64_t cost = stands.samples();
    CHECK(cost > 0);
    CHECK(cost <= static_cast<std::uint64_t>(scene::WaterStands::rays) * scene::WaterStands::most);
    // a name wider than the water's strip on the frame stands with its middle on the water still, its end over the
    // shore; one whose middle would have to stand on the land, or wider than the frame, has no stand
    const std::optional<scene::NamePlace> wide = stands.find(big, 290.0, 50.0);
    ATLAS_REQUIRE(wide.has_value());
    CHECK(point(wide->lat, wide->lon).value_or(view::Vec2{}).x <= 710.0);
    CHECK(point(wide->lat, wide->lon).value_or(view::Vec2{}).x >= 700.0);
    CHECK_FALSE(stands.find(big, 301.0, 50.0).has_value());
    CHECK_FALSE(stands.find(big, 501.0, 50.0).has_value());
    // a pond of the same point is too far from the frame (three radii of its area's disc): not looked at
    const std::uint64_t before = stands.samples();
    CHECK_FALSE(stands.find(lake("Plas", 1.0e6, big.lat, big.lon), 100.0, 50.0).has_value());
    CHECK(stands.samples() == before);
    // a node, which says no size, has none
    CHECK_FALSE(stands.find(lake("Punt", 0.0, big.lat, big.lon), 100.0, 50.0).has_value());
    // a dike between the frame's water and the lake's point: another water, no stand
    dike = true;
    stands.begin(frame, &point, &water);
    CHECK_FALSE(stands.find(big, 100.0, 50.0).has_value());
    // no water on the frame: none, at a sample a line
    dike = false;
    const scene::WaterStands::Water dry = [](double, double) -> std::optional<bool> { return false; };
    stands.begin(frame, &point, &dry);
    const std::uint64_t dry_before = stands.samples();
    CHECK_FALSE(stands.find(big, 100.0, 50.0).has_value());
    CHECK(stands.samples() - dry_before == static_cast<std::uint64_t>(scene::WaterStands::rays));
    // where the frame draws no tile nothing is known, and nothing is taken for water
    const scene::WaterStands::Water none = [](double, double) -> std::optional<bool> { return std::nullopt; };
    stands.begin(frame, &point, &none);
    CHECK_FALSE(stands.find(big, 100.0, 50.0).has_value());
    // a lake whose point lies north-east of the frame: the nearest end of its lines, not the first line's
    const scene::WaterStands::Water north = [&](double lat, double lon) -> std::optional<bool> {
        return lon >= shore && lat >= 0.0;
    };
    const WorldName corner = lake("Hoek", 6.0e9, 110'000.0 / 110'540.0, 70'000.0 / 111'320.0);
    stands.begin(frame, &point, &north);
    const std::optional<scene::NamePlace> at = stands.find(corner, 100.0, 50.0);
    ATLAS_REQUIRE(at.has_value());
    const view::Vec2 c = point(at->lat, at->lon).value_or(view::Vec2{});
    CHECK(c.x <= 900.0);
    CHECK(c.x >= 700.0);
    CHECK(c.y >= 50.0);
    CHECK(c.y <= 1'000.0);
    CHECK(north(at->lat, at->lon) == std::optional(true));
    // (the frame's north-east corner region: within 150 px of the nearest place a name's middle can take)
    CHECK(pymath::sqrt(((c.x - 900.0) * (c.x - 900.0)) + ((c.y - 50.0) * (c.y - 50.0))) <= 150.0);
}

TEST_CASE("scene: from 100 km over Amsterdam on a phone the Markermeer's point lies past the frame's side, and its "
          "name stands on its water in the frame's north-east") {
    // the Markermeer with the Hoornse Hop and the IJmeer, roughly: Enkhuizen, Lelystad, Almere, Muiderberg,
    // Durgerdam, Marken, Volendam, Edam, the Hoornse Hop, Hoorn, Wijdenes, Venhuizen
    const std::array<std::pair<double, double>, 14> shore{{{52.70, 5.29},
                                                           {52.53, 5.43},
                                                           {52.45, 5.30},
                                                           {52.33, 5.22},
                                                           {52.33, 5.12},
                                                           {52.38, 5.00},
                                                           {52.42, 5.07},
                                                           {52.46, 5.10},
                                                           {52.50, 5.07},
                                                           {52.52, 5.06},
                                                           {52.57, 5.015},
                                                           {52.62, 5.015},
                                                           {52.64, 5.07},
                                                           {52.66, 5.21}}};
    const WorldName meer = lake("Markermeer", 5.93e8, 52.53, 5.25);
    ATLAS_REQUIRE(inside(shore, meer.lat, meer.lon));
    const double lat = 52.3746;
    const double lon = 4.884;
    constexpr view::Device device{.density = 2.625, .width_px = 1079, .height_px = 2399, .quarter_turns = 0};
    const view::GroundFrame ground{
        .lat0 = lat, .lon0 = lon, .kx = 111'320.0 * pymath::cos(lat * radian), .kz = 110'540.0};
    const view::View view(
        {.target = {0, 0, 0}, .yaw = 0.0, .pitch = 90.0, .dist = 100'000.0, .width = 411.0, .height = 914.0}, device);
    const scene::WorldBend bend = scene::world_bend(view, ground);
    const scene::WaterStands::Point point = [&](double a, double o) {
        return scene::name_point(bend, ground, view, a, o, 0.1);
    };
    const scene::WaterStands::Water water = [&](double a, double o) -> std::optional<bool> {
        return inside(shore, a, o);
    };
    const double metres_a_dp = 100'000.0 / view.camera().focal();
    scene::WaterStands stands;
    stands.begin({.width = 1'079.0,
                  .height = 2'399.0,
                  .step = scene::stand_step_dp * metres_a_dp,
                  .px = device.density / metres_a_dp,
                  .lat = lat,
                  .lon = lon},
                 &point, &water);
    // its own point: past the frame's east side
    const std::optional<view::Vec2> own = point(meer.lat, meer.lon);
    ATLAS_REQUIRE(own.has_value());
    CHECK(own->x > 1'079.0);
    CHECK_FALSE(stands.on_frame(meer.lat, meer.lon, 0.0));
    // its name: ten letters of the board's 21 dp, as the labels pass measures it before its sprite is drawn
    const double half = ((0.26 * 10.0 * 21.0) + 8.0) * device.density;
    const double tall = 21.0 * device.density;
    const std::optional<scene::NamePlace> stand = stands.find(meer, half, tall);
    ATLAS_REQUIRE(stand.has_value());
    const std::optional<view::Vec2> px = point(stand->lat, stand->lon);
    ATLAS_REQUIRE(px.has_value());
    MESSAGE(
        std::format("the Markermeer from 100 km over Amsterdam ({:.0f} m a dp): its point at ({:.0f}, {:.0f}) px of "
                    "1079 by 2399, its stand at {:.3f} N {:.3f} E, ({:.0f}, {:.0f}) px",
                    metres_a_dp, own->x, own->y, stand->lat, stand->lon, px->x, px->y));
    CHECK(inside(shore, stand->lat, stand->lon));
    CHECK(px->x >= half);
    CHECK(px->x <= 1'079.0 - half);
    CHECK(px->y >= tall);
    CHECK(px->y <= 2'399.0 - tall);
    CHECK(px->x > 1'079.0 / 2.0); // the frame's east
    CHECK(px->y < 2'399.0 / 2.0); // and north
    CHECK(stands.on_frame(stand->lat, stand->lon, tall));
    // from 400 km the whole lake shows about its point: the name is at home, and the pass asks for no stand
    const view::View far(
        {.target = {0, 0, 0}, .yaw = 0.0, .pitch = 90.0, .dist = 400'000.0, .width = 411.0, .height = 914.0}, device);
    const scene::WorldBend far_bend = scene::world_bend(far, ground);
    const scene::WaterStands::Point far_point = [&](double a, double o) {
        return scene::name_point(far_bend, ground, far, a, o, 0.1);
    };
    stands.begin({.width = 1'079.0,
                  .height = 2'399.0,
                  .step = 4.0 * scene::stand_step_dp * metres_a_dp,
                  .px = device.density / (4.0 * metres_a_dp),
                  .lat = lat,
                  .lon = lon},
                 &far_point, &water);
    CHECK(stands.on_frame(meer.lat, meer.lon, 2.0 * tall));
}
