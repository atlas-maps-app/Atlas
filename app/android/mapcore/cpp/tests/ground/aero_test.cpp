// draw_over's runways, taxiways, stands and park edges in the ground's stage T (geometry/Ground.cpp; render/regions.py
// draw_over): a window's runway as its pavement, its centre line's dashes and its ends' threshold bars, its taxiways'
// pavements and lines, a stand's lead-in line and a national park's dashed edge, each in the over group's order; and
// the line stage's dashes along the screen (shaders/ground/lines.slangh's in_dash) as Skia dashes a contour.
#include "base/arena.hpp"
#include "geometry/Ground.hpp"
#include "load/Window.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"
#include "twin/ground/LineShader.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <filesystem>
#include <format>
#include <fstream>
#include <span>
#include <string>
#include <vector>

namespace geo = atlas::geometry;
namespace tl = atlas::tiles;

namespace {

/// A window about the Westerkerk with an airfield's ways: a runway (18/36, 45 m), a taxiway and a taxilane beside it, a
/// stand's lead-in line and a national park's edge around them; its pack written to the tests' scratch folder.
[[nodiscard]] std::string airfield_window() {
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
{"type": "way", "id": 30, "tags": {"aeroway": "runway", "ref": "18/36", "width": "45"}, "geometry": [
  {"lat": 52.3750, "lon": 4.8840}, {"lat": 52.3720, "lon": 4.8840}]},
{"type": "way", "id": 31, "tags": {"aeroway": "taxiway"}, "geometry": [
  {"lat": 52.3749, "lon": 4.8848}, {"lat": 52.3734, "lon": 4.8849}, {"lat": 52.3721, "lon": 4.8848}]},
{"type": "way", "id": 32, "tags": {"aeroway": "taxilane"}, "geometry": [
  {"lat": 52.3735, "lon": 4.8849}, {"lat": 52.3735, "lon": 4.8856}]},
{"type": "way", "id": 33, "tags": {"aeroway": "parking_position"}, "geometry": [
  {"lat": 52.3736, "lon": 4.8856}, {"lat": 52.3738, "lon": 4.8859}]},
{"type": "way", "id": 34, "tags": {"boundary": "national_park", "name": "Westerpark"}, "geometry": [
  {"lat": 52.3712, "lon": 4.8830}, {"lat": 52.3712, "lon": 4.8866}, {"lat": 52.3752, "lon": 4.8866},
  {"lat": 52.3752, "lon": 4.8830}, {"lat": 52.3712, "lon": 4.8830}]}
]})";
    const auto built =
        atlas::load::build_window(std::as_bytes(std::span(data.data(), data.size())), k, atlas::load::place_of(k));
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "aero-window";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "window.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

[[nodiscard]] std::size_t count_of(const geo::GroundTile& g, geo::FillKind kind) {
    return static_cast<std::size_t>(std::ranges::count(g.fills, kind, &geo::Fill::kind));
}

[[nodiscard]] std::vector<const geo::Stroke*> strokes_of(const geo::GroundTile& g, geo::StrokeKind kind) {
    std::vector<const geo::Stroke*> out;
    for (const geo::Stroke& s : g.strokes) {
        if (s.kind == kind) out.push_back(&s);
    }
    return out;
}

} // namespace

TEST_CASE("ground: a window's runway, taxiways, stand and park edge build as draw_over draws them") {
    const std::string path = airfield_window();
    ATLAS_REQUIRE(!path.empty());
    auto pack = atlas::test::open_pack(path);
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const tl::RenderHead& head = *pack->head();
    ATLAS_REQUIRE(pack->style() != nullptr);
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "aero test");
    const auto chunks = pack->chunks().level(head.levels.front());
    ATLAS_REQUIRE(!chunks.empty());
    auto tile = pack->decode(chunks.front(), zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    const geo::P2 origin{.x = head.frame.x(tile->header.west), .z = head.frame.z(tile->header.south)};
    const geo::GroundTile g = geo::build_ground(*tile, head, *pack->style(), 0, origin);
    constexpr std::uint16_t aero = geo::group::over + 2;
    // the runway: its pavement, and at each end 16 bars with it, the end's at its own slot
    REQUIRE(count_of(g, geo::FillKind::runway) == 1);
    const auto runway = static_cast<std::uint32_t>(std::ranges::find(g.fills, geo::FillKind::runway, &geo::Fill::kind) -
                                                   g.fills.begin());
    const geo::Fill& pavement = g.fills[runway];
    CHECK(pavement.group == aero);
    CHECK(pavement.sub == geo::aero_sub::pavement);
    CHECK(pavement.y == 0.07);
    CHECK(pavement.line_count == 2);
    CHECK(count_of(g, geo::FillKind::bars) == 32);
    std::size_t first_end = 0;
    for (const geo::Fill& f : g.fills) {
        if (f.kind != geo::FillKind::bars) continue;
        CHECK(f.with == runway);
        CHECK(f.order == pavement.order);
        CHECK(f.count == 4);
        CHECK(f.margin == 200);
        CHECK(f.y == 0.09);
        CHECK((f.sub == geo::aero_sub::bars || f.sub == geo::aero_sub::bars + 2));
        first_end += f.sub == geo::aero_sub::bars ? 1U : 0U;
    }
    CHECK(first_end == 16);
    // its centre line: dashes 30 on 20 off along its 332 m, with its pavement
    const auto centre = strokes_of(g, geo::StrokeKind::centre);
    REQUIRE(centre.size() == 1);
    CHECK(centre[0]->fill == runway);
    CHECK(centre[0]->sub == geo::aero_sub::centre);
    CHECK(centre[0]->count == 7);
    CHECK(centre[0]->width.metres == 0.9);
    for (std::uint32_t p = centre[0]->first; p < centre[0]->first + centre[0]->count; ++p) {
        CHECK(g.polylines[p].count == 2);
        CHECK(g.polylines[p].y == 0.09);
    }
    REQUIRE(g.runways.size() == 1);
    CHECK(g.runways[0].ref == "18/36");
    CHECK(g.runways[0].pavement == runway);
    // the taxiway (23 m) and the taxilane (15 m): their pavements and their lines, the lines without their pavements
    CHECK(count_of(g, geo::FillKind::taxiway) == 2);
    const auto taxi = strokes_of(g, geo::StrokeKind::taxi);
    REQUIRE(taxi.size() == 2);
    for (const geo::Stroke* s : taxi) {
        CHECK(!s->fill);
        CHECK(s->width.metres == 0.3);
        CHECK(s->sub == geo::aero_sub::centre);
        CHECK(s->order < pavement.order); // ORDER: taxiways before runways
    }
    // the stand's lead-in: px(0.25) at its first point, after the runways
    const auto lead = strokes_of(g, geo::StrokeKind::lead_in);
    REQUIRE(lead.size() == 1);
    CHECK(lead[0]->width.metres == 0.25);
    CHECK(lead[0]->order > pavement.order);
    CHECK(lead[0]->width.at.x == g.points[lead[0]->line].x);
    CHECK(lead[0]->width.at.z == g.points[lead[0]->line].z);
    // the park's edge: 2.2 wide, dashed 7 on 5 off at .85, no radius cut
    const auto park = strokes_of(g, geo::StrokeKind::park);
    REQUIRE(park.size() == 1);
    CHECK(park[0]->group == geo::group::over + 5);
    CHECK(park[0]->width.css == 2.2);
    CHECK(park[0]->dash == std::array<double, 2>{7.0, 5.0});
    CHECK(park[0]->opacity == 0.85);
    CHECK(park[0]->line_count == 0);
    CHECK(g.polylines[park[0]->first].y == 0.15);
    CHECK(g.polylines[park[0]->first].count >= 4);
}

TEST_CASE("ground: a dashed line's samples lie in its dashes, each segment from its start's place in the pattern") {
    namespace sh = atlas::shaders;
    // a segment 40 px long along x, two neighbours bending away; dashes of 7 in 12, at 1x
    const sh::Seg g = sh::seg_make({0.0F, 0.0F}, {40.0F, 0.0F}, {-10.0F, -10.0F}, {50.0F, 10.0F},
                                   sh::LINE_HAS_PREV | sh::LINE_HAS_NEXT, 1.1F, sh::LINE_DASH);
    const sh::float4 from_start{0.0F, 7.0F, 12.0F, 1.0F};
    CHECK(sh::in_dash(g, from_start, {3.0F, 0.5F}));
    CHECK(!sh::in_dash(g, from_start, {8.0F, -0.5F}));
    CHECK(sh::in_dash(g, from_start, {13.0F, 0.0F}));
    CHECK(!sh::in_dash(g, from_start, {35.5F, 0.0F}));
    // the segment starting 6 px into the pattern: its first px in the dash, its second in the gap
    const sh::float4 into{6.0F, 7.0F, 12.0F, 1.0F};
    CHECK(sh::in_dash(g, into, {0.5F, 0.0F}));
    CHECK(!sh::in_dash(g, into, {1.5F, 0.0F}));
    // the join's wedge beyond its end takes the end's place: 40 + 6 = 46, 10 into its period, in the gap
    CHECK(!sh::in_dash(g, into, {40.5F, 0.8F}));
    CHECK(sh::in_dash(g, from_start, {40.5F, 0.8F})); // 40 = 4 into its period
    // a solid run is never cut
    CHECK(sh::in_dash(g, {0.0F, 0.0F, 0.0F, 0.0F}, {8.0F, 0.0F}));
}

namespace {

/// The big roads' tags, way 40 on: untagged; a lanes or a width past any road's, or that cannot be read, each drawn
/// as untagged; then values in range drawn as mapped, at the bounds (64 lanes, 250 m) and within them.
[[nodiscard]] std::vector<std::string> motorway_tags() {
    const std::string past_doubles(400, '9'); // past the doubles: an infinity, which to_int cannot take
    return {
        "",
        std::format(R"(, "lanes": "{}")", past_doubles),
        R"(, "lanes": "100000")", // 99,999 lane lines, 350 km wide
        R"(, "lanes": "-1")",
        R"(, "width": "500000000")", // a 500,000 km road
        R"(, "width": "-12")",
        R"(, "width": "NaN")",
        R"(, "width": "inf")",
        std::format(R"(, "width": "{}")", past_doubles),
        R"(, "lanes": "64")",  // the most a road is drawn with
        R"(, "width": "250")", // the widest
        R"(, "lanes": "21")",  // Germany's most, 16 November 2026
        R"(, "lanes": "0")",
        R"(, "lanes": "3;2")", // a list: its first number
    };
}

/// A window about the Westerkerk with a big road for each of motorway_tags(), each a motorway with one bend (300 m
/// east, then 200 m north), its pack written to the tests' scratch folder.
[[nodiscard]] std::string motorway_window(const std::vector<std::string>& tags) {
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [)";
    for (std::size_t i = 0; i < tags.size(); ++i) {
        const double lat = 52.3640 + (0.0008 * static_cast<double>(i)); // the bend, the middle vertex, in the tile
        data += std::format(R"({}{{"type": "way", "id": {}, "tags": {{"highway": "motorway"{}}}, "geometry": [)"
                            R"({{"lat": {:.4f}, "lon": 4.8800}}, {{"lat": {:.4f}, "lon": 4.8844}}, )"
                            R"({{"lat": {:.4f}, "lon": 4.8844}}]}})",
                            i == 0 ? "" : ",", 40 + i, tags.at(i), lat, lat, lat + 0.0018);
    }
    data += "]}";
    const auto built =
        atlas::load::build_window(std::as_bytes(std::span(data.data(), data.size())), k, atlas::load::place_of(k));
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "motorway-window";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "window.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

/// What a big road drew: its lane lines' dashes and its edge lines' distance from its centre line at its first point.
struct BigRoad {
    std::size_t dashes = 0;
    double edge = 0.0;
};

} // namespace

TEST_CASE("ground: a big road's lanes and width that no road has, or that cannot be read, draw as unmapped") {
    const std::vector<std::string> tags = motorway_tags();
    const std::string path = motorway_window(tags);
    ATLAS_REQUIRE(!path.empty());
    auto pack = atlas::test::open_pack(path);
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const tl::RenderHead& head = *pack->head();
    ATLAS_REQUIRE(pack->style() != nullptr);
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "motorway test");
    const auto chunks = pack->chunks().level(head.levels.front());
    ATLAS_REQUIRE(!chunks.empty());
    auto tile = pack->decode(chunks.front(), zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    const geo::P2 origin{.x = head.frame.x(tile->header.west), .z = head.frame.z(tile->header.south)};
    const geo::GroundTile g = geo::build_ground(*tile, head, *pack->style(), 0, origin);
    std::vector<BigRoad> roads(tags.size());
    std::vector<bool> seen(tags.size(), false);
    for (const geo::Stroke& s : g.strokes) {
        if (s.kind != geo::StrokeKind::lanes && s.kind != geo::StrokeKind::edges) continue;
        const std::uint64_t id = (s.key.element >> 18U) & ((std::uint64_t{1} << 44U) - 1);
        REQUIRE((id >= 40 && id < 40 + tags.size()));
        BigRoad& r = roads.at(id - 40);
        seen.at(id - 40) = true;
        if (s.kind == geo::StrokeKind::edges) {
            const geo::P2 c = g.points[s.line];
            const geo::P2 e = g.points[g.polylines[s.first].first];
            r.edge = std::hypot(e.x - c.x, e.z - c.z);
            continue;
        }
        r.dashes = s.count;
    }
    for (std::size_t i = 0; i < seen.size(); ++i) {
        INFO("way " << 40 + i);
        CHECK(seen[i]);
    }
    // unmapped: two lanes, 2 · 3.5 + 3 m wide, its edge lines 0.5 m in, one lane line between them; each value past a
    // road's or that cannot be read draws the same, its work as bounded (before: the abort, 99,999 lane lines 350 km
    // wide, a negative road, a 500,000 km one that dashed without end, a negative width, an infinity's abort)
    const BigRoad& unmapped = roads[0];
    CHECK(unmapped.edge == doctest::Approx(4.5));
    CHECK(unmapped.dashes > 0);
    for (std::size_t i = 1; i <= 8; ++i) {
        INFO("way " << 40 + i << tags.at(i).substr(0, 40));
        CHECK(roads.at(i).edge == doctest::Approx(unmapped.edge));
        CHECK(roads.at(i).dashes == unmapped.dashes);
    }
    // in range, as mapped: 64 lanes, 64 · 3.5 + 3 m and 63 lane lines; 250 m with two lanes, its lane line where the
    // unmapped road's is (-125 + 2 + 247 / 2 = -5 + 2 + 7 / 2); 21 lanes; no lanes, 3 m and no lane line; a list's 3
    CHECK(roads[9].edge == doctest::Approx((((64 * 3.5) + 3) / 2) - 0.5));
    CHECK(roads[9].dashes > 62 * unmapped.dashes);
    CHECK(roads[10].edge == doctest::Approx((250.0 / 2) - 0.5));
    CHECK(roads[10].dashes == unmapped.dashes);
    CHECK(roads[11].edge == doctest::Approx((((21 * 3.5) + 3) / 2) - 0.5));
    CHECK(roads[11].dashes > 19 * unmapped.dashes);
    CHECK(roads[12].edge == doctest::Approx((3.0 / 2) - 0.5));
    CHECK(roads[12].dashes == 0);
    CHECK(roads[13].edge == doctest::Approx((((3 * 3.5) + 3) / 2) - 0.5));
    CHECK(roads[13].dashes > unmapped.dashes);
}
