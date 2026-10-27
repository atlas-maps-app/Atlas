// A window of the map the phone builds (load/Window.hpp): where a place is (load/Countries.hpp, the port's Countries),
// a window's box, frame and place, and windows built from a region's data:
//   - every L14 tile of the canal ring built as a window, from the elements of the region's raw.json that the renders'
//     Overpass query returns for the window's box (each built in the region's frame, by its rules), is the packer's
//     tile of the whole region feature for feature: their canonical dumps are the same once the places in the scene's
//     lists are taken out (a window's lists hold its own data, so its indexes and the ground's ranks are its own), and
//     the variants of the region's home view, which a window has no views for, are left out;
//   - a window in Paris, anywhere's place (its country's tradition, its frame cell's frame) builds, with fr_paris's
//     looks, and its pack opens and says what it is;
//   - a window carries a sign gantry and a goods conveyor, each a line of its own (sources 7 and 8, with their ids and
//     heights), as a region's cut does;
//   - a window by the sea builds: the sea regions.py closes from a coastline, whose points along its box are computed,
//     lies on the grid at their nearest grid points;
//   - a window that holds nothing to draw is a pack without a tile, whose manifest says so, and joins no frame cell;
//   - a water ring that reaches too far (the fuzzer's) refuses a region's cut, and a window cuts its own tile of it.
#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "load/Countries.hpp"
#include "load/Cut.hpp"
#include "load/PackStyle.hpp"
#include "load/Regions.hpp"
#include "load/Window.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/load/query.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Json.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "tiles/Store.hpp"
#include "tiles/layers/Lines.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <format>
#include <fstream>
#include <iterator>
#include <limits>
#include <span>
#include <sstream>
#include <string>
#include <vector>

namespace {

namespace load = atlas::load;
namespace osm = atlas::osm;
namespace tl = atlas::tiles;
using atlas::test::query;

std::string read_text(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

/// A pack's JSON manifest, after its 20-byte header (whose bytes 12 to 15 give its length, big-endian).
std::expected<tl::JsonValue, tl::JsonError> manifest_of(std::span<const std::byte> pack) {
    const auto length = (std::to_integer<std::uint32_t>(pack[12]) << 24U) |
                        (std::to_integer<std::uint32_t>(pack[13]) << 16U) |
                        (std::to_integer<std::uint32_t>(pack[14]) << 8U) | std::to_integer<std::uint32_t>(pack[15]);
    return tl::read_json(pack.subspan(20, length));
}

/// Writes a pack into the tests' scratch folder, under `name`; returns its path.
std::string written(const std::vector<std::byte>& pack, const std::string& name) {
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "windows";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / name;
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(pack.size()));
    return file.string();
}

/// A canonical dump without the places in the scene's lists (a feature's index, the ground's ranks, a tree's node, a
/// quay's ring and owner, a hole's water), without the variants at ladder index `skip` and the cells' variant counts.
std::vector<std::string> masked(const std::string& dump, int skip) {
    static constexpr std::array<std::string_view, 8> fields = {
        " index=", " ground_key=", " marks_key=", " node=", " ring=", " owner=", " hole_of=", " variant="};
    std::vector<std::string> out;
    std::istringstream in(dump);
    std::string line;
    const std::string skipped = " tolerance=" + std::to_string(skip);
    while (std::getline(in, line)) {
        if (line.starts_with("variant ") &&
            (line.find(skipped + " ") != std::string::npos || line.ends_with(skipped))) {
            continue;
        }
        // a window's ground and lines carry their element (id, otype, part), the packer's do not
        if (line.starts_with("ground ") || line.starts_with("line ")) {
            for (const std::string_view f : {" id=", " otype=", " part="}) {
                for (std::size_t at = line.find(f); at != std::string::npos; at = line.find(f, at)) {
                    std::size_t end = at + f.size();
                    if (end < line.size() && line[end] == '"') end = line.find('"', end + 1) + 1;
                    while (end < line.size() && line[end] != ' ') ++end;
                    line.erase(at, end - at);
                }
            }
        }
        for (const std::string_view f : fields) {
            // a quay's ring is its index in S['water'], an integer; a ring of points starts with '['
            for (std::size_t at = line.find(f); at != std::string::npos; at = line.find(f, at)) {
                const std::size_t value = at + f.size();
                if (value < line.size() && line[value] == '[') {
                    at = value;
                    continue;
                }
                std::size_t end = value;
                while (end < line.size() && line[end] != ' ') ++end;
                line.erase(at, end - at);
            }
        }
        out.push_back(line);
    }
    return out;
}

} // namespace

TEST_CASE("load: a window carries gantries and conveyors, each a line of its own, as a region's cut does") {
    // a house by the Westerkerk, a sign gantry over the road beside it and a goods conveyor (until 24 October the tiles
    // had no column for them: a window left them out and a region's cut refused them)
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const std::string house = R"({"type": "way", "id": 10, "tags": {"building": "house"}, "geometry": [
    {"lat": 52.3745, "lon": 4.8840}, {"lat": 52.3745, "lon": 4.8842}, {"lat": 52.3747, "lon": 4.8842},
    {"lat": 52.3747, "lon": 4.8840}, {"lat": 52.3745, "lon": 4.8840}]})";
    const std::string carried = R"({"type": "way", "id": 11, "tags": {"man_made": "gantry", "height": "7.5"},
    "geometry": [{"lat": 52.3744, "lon": 4.8835}, {"lat": 52.3744, "lon": 4.8838}]},
{"type": "way", "id": 12, "tags": {"man_made": "goods_conveyor"},
    "geometry": [{"lat": 52.3748, "lon": 4.8835}, {"lat": 52.3749, "lon": 4.8845}]})";
    const auto answer = [](const std::string& elements) {
        return R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
)" + elements + "]}";
    };
    const std::string with = answer(house + ",\n" + carried);
    const std::string without = answer(house);
    const auto bytes = [](const std::string& s) { return std::as_bytes(std::span(s.data(), s.size())); };
    const load::WindowPlace place = load::place_of(k);
    // a window's one tile's lines: each one's source, id and height (nan for none)
    struct Line {
        std::int64_t source = 0;
        std::int64_t id = 0;
        double h = 0;
    };
    const auto lines_of = [](const std::vector<std::byte>& pack) -> std::vector<Line> {
        auto opened = tl::Pack::open(std::make_unique<tl::MemorySource>(pack));
        if (!opened || opened->chunks().chunks().size() != 1) return {};
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        atlas::Arena arena(1U << 20U, atlas::Growth::grows, "window test, a tile's lines");
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        const auto tile = opened->decode(opened->chunks().chunks()[0], zstd, decoder, arena, raw, scratch);
        if (!tile) return {};
        std::vector<Line> out;
        if (const tl::Layer* l = tile->layer(tl::LayerId::line)) {
            for (std::uint32_t i = 0; i < l->count; ++i) {
                out.push_back({.source = l->integer(tl::line::source, i),
                               .id = l->integer(tl::line::id, i),
                               .h = l->decimal(tl::line::h, i).value_or(std::numeric_limits<double>::quiet_NaN())});
            }
        }
        return out;
    };
    const auto built = load::build_window(bytes(with), k, place);
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    const auto plain = load::build_window(bytes(without), k, place);
    ATLAS_REQUIRE(plain.has_value(), plain.error().what);
    // the window's tile holds them beside the house: the gantry a line of source 7 with its height, the conveyor one of
    // source 8 without
    CHECK(built->features == plain->features + 2);
    CHECK(lines_of(plain->pack).empty());
    const std::vector<Line> lines = lines_of(built->pack);
    REQUIRE(lines.size() == 2);
    CHECK(lines[0].source == 7);
    CHECK(lines[0].id == 11);
    CHECK(lines[0].h == 7.5);
    CHECK(lines[1].source == 8);
    CHECK(lines[1].id == 12);
    CHECK(std::isnan(lines[1].h));
    // a region's cut of the same scene carries them too (without the place's tradition, whose details a region's cut
    // refuses still)
    const auto doc = osm::read_overpass(bytes(with));
    ATLAS_REQUIRE(doc.has_value());
    const load::RegionConfig config{.name = place.region,
                                    .frame = place.frame,
                                    .bbox = load::window_box(k),
                                    .tradition = nullptr,
                                    .palette = load::region_palette(place.region)};
    const auto scene = load::load_region(*doc, config);
    ATLAS_REQUIRE(scene.has_value());
    CHECK(scene->gantries.size() == 1);
    CHECK(scene->conveyors.size() == 1);
    const auto cut = load::cut_region(*scene, place.frame, {});
    ATLAS_REQUIRE(cut.has_value(), cut.error().what);
    std::size_t region_lines = 0;
    for (const tl::TileFill& t : *cut) {
        if (t.level() != 14) continue;
        for (const tl::LayerFill& lf : t.layers()) {
            if (lf.schema().label == std::string_view("line")) region_lines += lf.count();
        }
    }
    CHECK(region_lines == 2);
}

TEST_CASE("load: a window by the sea builds, the sea's computed points at their nearest grid points") {
    // a coastline through the Westerkerk's window (land on its left, the sea to the north-east): regions.py closes the
    // sea along a box 0.06° and 0.1° beyond the window's, through points it computes, which lie on no grid point; until
    // 24 October such a window was refused ("water 0: z = ... is not on the grid of the frame"), and so was a region
    // with a coast
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
{"type": "way", "id": 20, "tags": {"natural": "coastline"}, "geometry": [
    {"lat": 52.3700, "lon": 4.8700}, {"lat": 52.3750, "lon": 4.8850}, {"lat": 52.3800, "lon": 4.9000}]},
{"type": "way", "id": 10, "tags": {"building": "house"}, "geometry": [
    {"lat": 52.3735, "lon": 4.8840}, {"lat": 52.3735, "lon": 4.8842}, {"lat": 52.3737, "lon": 4.8842},
    {"lat": 52.3737, "lon": 4.8840}, {"lat": 52.3735, "lon": 4.8840}]}]})";
    const auto bytes = std::as_bytes(std::span(data.data(), data.size()));
    const load::WindowPlace place = load::place_of(k);
    const auto doc = osm::read_overpass(bytes);
    ATLAS_REQUIRE(doc.has_value());
    const load::RegionConfig config{.name = place.region,
                                    .frame = place.frame,
                                    .bbox = load::window_box(k),
                                    .tradition = nullptr,
                                    .palette = load::region_palette(place.region)};
    const auto scene = load::load_region(*doc, config);
    ATLAS_REQUIRE(scene.has_value());
    REQUIRE(scene->water.size() == 1); // the sea, closed from the coastline
    // its box's points lie on no grid point
    std::size_t off_grid = 0;
    for (const auto& p : scene->water[0]) {
        if (!tl::grid_lat(place.frame, p.z) || !tl::grid_lon(place.frame, p.x)) ++off_grid;
    }
    CHECK(off_grid > 0);
    // a region's cut and the window build, the sea in the window's tile
    const auto cut = load::cut_region(*scene, place.frame, {});
    ATLAS_REQUIRE(cut.has_value(), cut.error().what);
    const auto built = load::build_window(bytes, k, place);
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    auto opened = tl::Pack::open(std::make_unique<tl::MemorySource>(built->pack));
    ATLAS_REQUIRE(opened.has_value());
    REQUIRE(opened->chunks().chunks().size() == 1);
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "window test, a window by the sea");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    const auto tile = opened->decode(opened->chunks().chunks()[0], zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE(tile.has_value());
    const tl::Layer* water = tile->layer(tl::LayerId::water);
    REQUIRE(water != nullptr);
    CHECK(water->count > 0);
}

TEST_CASE("load: a window that holds nothing to draw is a pack without a tile, which says so and joins no cell") {
    // The Westerkerk's window: a house in it; no data at all (the open sea, where no coastline is near); a house in its
    // margin alone, which the window beside it draws. Until 22 October such a window wrote no tile, so the core kept
    // asking for its place and the app fetched its data again every half second while it was in view.
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const std::array<std::int32_t, 4> tb = tl::tile_box(k);
    const auto ring = [](double lat, double lon) {
        return std::format(R"([{{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}},
    {{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}}])",
                           lat, lon, lat, lon + 0.0002, lat + 0.0002, lon + 0.0002, lat + 0.0002, lon, lat, lon);
    };
    const auto house = [&ring](int id, double lat, double lon) {
        return std::format(R"({{"type": "way", "id": {}, "tags": {{"building": "house"}}, "geometry": {}}})", id,
                           ring(lat, lon));
    };
    const auto answer = [](const std::string& elements) {
        return R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
)" + elements + "]}";
    };
    const auto bytes = [](const std::string& s) { return std::as_bytes(std::span(s.data(), s.size())); };
    const double north = static_cast<double>(tb[2]) / 1e7;
    const double middle = static_cast<double>(tb[1] + tb[3]) / 2e7;
    const std::string with = answer(house(10, 52.3745, 4.8840));
    const std::string none = answer("");
    const std::string margin = answer(house(11, north + 0.0004, middle)); // about 45 m north of the tile
    const load::WindowPlace place = load::place_of(k);
    const auto built = load::build_window(bytes(with), k, place);
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    const auto empty = load::build_window(bytes(none), k, place);
    ATLAS_REQUIRE(empty.has_value(), empty.error().what);
    const auto beside = load::build_window(bytes(margin), k, place);
    ATLAS_REQUIRE(beside.has_value(), beside.error().what);
    CHECK(built->features > 0);
    CHECK(empty->features == 0);
    CHECK(beside->features == 0);
    // each says in its manifest how many tiles it holds, and the versions it was built with, as the packer's say them
    for (const auto* w : {&*built, &*empty, &*beside}) {
        auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(w->pack));
        ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
        const auto manifest = manifest_of(w->pack);
        ATLAS_REQUIRE(manifest.has_value());
        const tl::Value m = manifest->root();
        const std::size_t tiles = w == &*built ? 1 : 0;
        CHECK(pack->chunks().chunks().size() == tiles);
        CHECK(m.find("chunks").value_or(tl::Value()).integer() == static_cast<std::int64_t>(tiles));
        CHECK(m.find("engine_version").value_or(tl::Value()).string() == load::stage_p_version);
        CHECK(m.find("style_version").value_or(tl::Value()).string() == load::style_version());
        // the version the app holds a window to (load::window_version): one of another is built again first
        CHECK(std::format("{} {}", m.find("engine_version").value_or(tl::Value()).string().value_or(""),
                          m.find("style_version").value_or(tl::Value()).string().value_or("")) ==
              load::window_version());
        CHECK(m.find("window").value_or(tl::Value()).string() == place.name);
    }
    // in the store: a window without a tile joins no cell; with its house, its frame cell's pack; built again without
    // it (the house gone), it leaves the cell, whose pack goes with its last window
    tl::Store store;
    const std::string id = "window/" + place.name;
    const auto add = [&store, &id](const std::vector<std::byte>& pack, const std::string& name) {
        return store.add({.id = id, .kind = tl::PackKind::window, .path = written(pack, name)});
    };
    ATLAS_REQUIRE(add(empty->pack, "nothing.atlas").has_value());
    CHECK(store.packs().empty());
    CHECK(store.find(k).empty());
    ATLAS_REQUIRE(add(built->pack, "house.atlas").has_value());
    CHECK(store.find(k).size() == 1);
    CHECK(store.holder_of(id) == tl::Store::cell_id(tl::TileKey(10, k.x >> 4U, k.y >> 4U)));
    ATLAS_REQUIRE(add(beside->pack, "margin.atlas").has_value());
    CHECK(store.packs().empty());
    CHECK(store.find(k).empty());
    CHECK(store.holder_of(id) == id);
}

TEST_CASE("load: a water ring that reaches far: a region's cut refuses it, a window cuts its own tile") {
    // A lake from the Westerkerk 260 km south, 70 km wide at its foot: about 8,000 tiles of L14, more than a ring may
    // reach. The nightly fuzzer flung a vertex so (23 October): the region's cut made a piece of the ring for every
    // tile until it ran out of memory.
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const std::string lake = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-23T03:00:00Z"}, "elements": [
{"type": "way", "id": 20, "tags": {"natural": "water"}, "geometry": [
    {"lat": 52.3745, "lon": 4.8840}, {"lat": 50.0, "lon": 4.8840}, {"lat": 50.0, "lon": 5.8840},
    {"lat": 52.3745, "lon": 4.8840}]}]})";
    const auto bytes = std::as_bytes(std::span(lake.data(), lake.size()));
    const load::WindowPlace place = load::place_of(k);
    const auto built = load::build_window(bytes, k, place);
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    CHECK(built->features == 1);
    const auto doc = osm::read_overpass(bytes);
    ATLAS_REQUIRE(doc.has_value());
    const load::RegionConfig config{.name = place.region,
                                    .frame = place.frame,
                                    .bbox = load::window_box(k),
                                    .tradition = nullptr,
                                    .palette = load::region_palette(place.region)};
    const auto scene = load::load_region(*doc, config);
    ATLAS_REQUIRE(scene.has_value());
    REQUIRE(scene->water.size() == 1);
    const auto refused = load::cut_region(*scene, place.frame, {});
    ATLAS_REQUIRE(!refused.has_value());
    CHECK(refused.error().what.starts_with("water 0 reaches "));
    CHECK(refused.error().what.ends_with(" tiles of L14, more than a ring may (4096)"));
    CHECK(!refused.error().uncarried);
}

TEST_CASE("load: where a window is, by the port's Countries") {
    CHECK(load::country_of(52.3746, 4.8840) == "NL");
    CHECK(load::country_of(48.8580, 2.3440) == "FR");
    CHECK(load::country_of(60.3972, 5.3240) == "NO");
    CHECK(load::country_of(51.5150, -0.2040) == "GB");
    CHECK(load::country_of(43.4677, 11.0432) == "IT");
    CHECK(load::country_of(40.7128, -74.0060) == "US");
    CHECK(load::country_of(49.6116, 6.1319) == "LU"); // inside BE's, DE's and FR's boxes too: the smallest wins
    CHECK(load::country_of(0.0, -160.0) == std::nullopt);
    CHECK(load::timezone_of("NL", 52.37, 4.88) == "Europe/Amsterdam");
    CHECK(load::timezone_of("US", 40.71, -74.0) == "America/New_York");
    CHECK(load::timezone_of("US", 34.05, -118.24) == "America/Los_Angeles");
    CHECK(load::timezone_of("AU", -33.87, 151.21) == "Australia/Sydney");
    CHECK(load::timezone_of("ZZ", 0, 0) == "UTC");
    CHECK(load::timezone_of(std::nullopt, 0, 0) == "UTC");
    CHECK(load::tz_hours("Europe/Amsterdam") == 2); // summer time on 1 October 2026
    CHECK(load::tz_hours("Europe/London") == 1);
    CHECK(load::tz_hours("America/New_York") == -4);
    CHECK(load::tz_hours("Asia/Kolkata") == 5); // +5:30, rounded down
    CHECK(load::tz_hours("Asia/Tehran") == 3);
    CHECK(load::tz_hours("Pacific/Auckland") == 13);
    CHECK(load::tz_hours("UTC") == 0);
    CHECK(load::tz_hours("Nowhere/Else") == 0);
    CHECK(load::tradition_of("NL") == "nl_amsterdam");
    CHECK(load::tradition_of("GB") == "gb_terrace");
    CHECK(load::tradition_of("US") == std::nullopt);
    for (const std::string_view name : {"fr_paris", "gb_terrace", "it_tuscan", "nl_amsterdam", "no_timber"}) {
        CHECK_MESSAGE(load::tradition_named(name) != nullptr, name);
    }
    CHECK(load::tradition_named("xx") == nullptr);
}

TEST_CASE("load: a window's box, frame and place") {
    const tl::TileKey tile = tl::tile_of(14, 523'746'000, 48'840'000); // the Westerkerk's
    const std::array<std::int32_t, 4> tb = tl::tile_box(tile);
    const std::array<double, 4> box = load::window_box(tile);
    // at least the margin beyond the tile's edges, and not much more
    const double north = (box[2] - (tb[2] / 1e7)) * 110540.0;
    const double east = (box[3] - (tb[3] / 1e7)) * 111320.0 * atlas::pymath::cos(atlas::pymath::radians(box[2]));
    CHECK(north == doctest::Approx(200.0).epsilon(1e-9));
    CHECK(east >= 199.9);
    CHECK(east <= 201.0);
    // the frame of its L10 cell: shared by every window in the cell
    const tl::Frame f = load::frame_of(tile);
    const tl::Frame g = load::frame_of(tl::TileKey(14, (tile.x & ~15U) + 15, (tile.y & ~15U)));
    CHECK(f.lat0 == g.lat0);
    CHECK(f.lon0 == g.lon0);
    CHECK(f.kx == g.kx);
    CHECK(f.lat0 * 1e4 == std::round(f.lat0 * 1e4));
    CHECK(f.kz == 110540.0);
    CHECK(f.kx == 111320.0 * atlas::pymath::cos(atlas::pymath::radians(f.lat0)));
    const load::WindowPlace p = load::place_of(tile);
    CHECK(p.name == std::to_string(tile.z) + "/" + std::to_string(tile.x) + "/" + std::to_string(tile.y));
    CHECK(p.country == "NL");
    // the Netherlands draw the renders' New looks, as the bundled Westerkerk does, not the Dutch kit
    CHECK(p.new_looks);
    CHECK_FALSE(p.tradition.has_value());
    CHECK(p.zone == "Europe/Amsterdam");
    CHECK(p.tz_h == 2);
    CHECK(p.region == "window");
    CHECK(p.frame.lat0 == f.lat0);
}

TEST_CASE("load: the canal ring built window by window is the packer's, tile for tile (ATLAS_REGIONS_DIR)") {
    const std::string regions = atlas::test::environment("ATLAS_REGIONS_DIR");
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (regions.empty() || dir.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR or ATLAS_TILES_DIR is not set: no window is built from the canal ring here");
        return;
    }
    const std::string text = read_text(std::filesystem::path(regions) / "grachten" / "raw.json");
    ATLAS_REQUIRE(!text.empty());
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(doc.has_value());
    auto pack = atlas::test::open_pack(dir + "/grachten.atlas");
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const tl::Frame frame = pack->head()->frame;
    const load::WindowPlace place{
        .name = "grachten", .frame = frame, .region = "grachten", .zone = "Europe/Amsterdam", .tz_h = 2};
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "window test, the packer's tile");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    std::size_t windows = 0;
    std::size_t lines = 0;
    std::size_t differing = 0;
    std::size_t features = 0;
    double load_ms = 0;
    double cut_ms = 0;
    std::size_t elements = 0;
    std::size_t identified = 0;
    std::size_t unidentified = 0;
    for (const tl::ChunkRef& chunk : pack->chunks().chunks()) {
        if (chunk.tile.z != load::window_level) continue;
        ++windows;
        arena.reset();
        const auto theirs = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(theirs.has_value(), tl::describe(theirs.error()));
        const osm::Document mine = query(*doc, load::window_box(chunk.tile));
        elements += mine.elements().size();
        const auto built = load::build_window(mine, tl::Sha256Digest{}, chunk.tile, place);
        ATLAS_REQUIRE(built.has_value(), built.error().what);
        load_ms += built->load_ms;
        cut_ms += built->cut_ms;
        features += built->features;
        auto window = tl::Pack::open(std::make_unique<tl::MemorySource>(built->pack));
        ATLAS_REQUIRE(window.has_value(), tl::describe(window.error()));
        ATLAS_REQUIRE(window->chunks().chunks().size() == 1);
        tl::TileDecoder decoder2;
        atlas::Arena arena2(8U << 20U, atlas::Growth::grows, "window test, the window's tile");
        std::vector<std::byte> raw2;
        std::vector<std::byte> scratch2;
        const auto ours = window->decode(window->chunks().chunks()[0], zstd, decoder2, arena2, raw2, scratch2);
        ATLAS_REQUIRE(ours.has_value(), tl::describe(ours.error()));
        // every ground feature and line of a window carries its element's id
        {
            std::istringstream dumped(tl::canonical_dump(*ours));
            std::string l;
            while (std::getline(dumped, l)) {
                if (l.starts_with("ground ") || l.starts_with("line ")) {
                    ++identified;
                    if (l.find(" id=") == std::string::npos) ++unidentified;
                }
            }
        }
        const std::vector<std::string> a = masked(tl::canonical_dump(*ours), -1);
        const std::vector<std::string> b = masked(tl::canonical_dump(*theirs), 7); // the home view's variants
        lines += b.size();
        std::size_t wrong = 0;
        for (std::size_t i = 0; i < std::max(a.size(), b.size()); ++i) {
            const std::string none = "(none)";
            const std::string& x = i < a.size() ? a[i] : none;
            const std::string& y = i < b.size() ? b[i] : none;
            if (x == y) continue;
            if (wrong++ < 2) {
                FAIL_CHECK("window ", chunk.tile.x, "/", chunk.tile.y, " line ", i, "\n  window: ", x.substr(0, 300),
                           "\n  packer: ", y.substr(0, 300));
            }
        }
        differing += wrong;
    }
    MESSAGE("the canal ring's ", windows, " windows: ", elements, " elements asked for in all, ", features,
            " features; ", lines, " lines of the packer's, ", differing, " not the window's; loaded in ", load_ms,
            " ms, cut in ", cut_ms, " ms");
    CHECK(windows > 0);
    CHECK(differing == 0);
    MESSAGE(identified, " ground features and lines in the windows, ", unidentified, " without their element");
    CHECK(identified > 0);
    CHECK(unidentified == 0);
}

TEST_CASE("load: a window anywhere: Paris, with its country's tradition (ATLAS_REGIONS_DIR)") {
    const std::string regions = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (regions.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: no window is built in Paris here");
        return;
    }
    const std::string text = read_text(std::filesystem::path(regions) / "paris" / "raw.json");
    ATLAS_REQUIRE(!text.empty());
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(doc.has_value());
    // the window around the Louvre's courtyard
    const tl::TileKey tile = tl::tile_of(14, 488'610'000, 23'360'000);
    const load::WindowPlace place = load::place_of(tile);
    CHECK(place.country == "FR");
    CHECK(place.tradition == "fr_paris");
    const osm::Document mine = query(*doc, load::window_box(tile));
    const auto built = load::build_window(mine, tl::Sha256Digest{}, tile, place);
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    auto window = tl::Pack::open(std::make_unique<tl::MemorySource>(built->pack));
    ATLAS_REQUIRE(window.has_value(), tl::describe(window.error()));
    const tl::RenderHead& head = *window->head();
    CHECK(head.region == place.name);
    CHECK(head.levels == std::vector<std::uint8_t>{14});
    CHECK(head.frame.lat0 == place.frame.lat0);
    CHECK(head.engine_version == load::stage_p_version);
    CHECK(head.views().empty());
    CHECK(head.bands().size() == 5);
    const tl::StyleTables* style = window->style_tables();
    ATLAS_REQUIRE(style != nullptr);
    CHECK(style->table("tradition").has_value());
    const auto scene = style->table("scene");
    ATLAS_REQUIRE(scene.has_value());
    CHECK(scene->find("tradition").value_or(tl::Value()).string() == "fr_paris");
    ATLAS_REQUIRE(window->style() != nullptr);
    CHECK(window->style()->tone);
    ATLAS_REQUIRE(window->chunks().chunks().size() == 1);
    CHECK(window->chunks().chunks()[0].tile.packed() == tile.packed());
    CHECK(built->features > 1000);
    // the manifest the app reads a render pack by: its box (the tile's), its origin (the frame's), profile, time zone
    const std::span<const std::byte> bytes(built->pack);
    const auto manifest_bytes =
        (std::to_integer<std::uint32_t>(bytes[12]) << 24U) | (std::to_integer<std::uint32_t>(bytes[13]) << 16U) |
        (std::to_integer<std::uint32_t>(bytes[14]) << 8U) | std::to_integer<std::uint32_t>(bytes[15]);
    const auto manifest = tl::read_json(bytes.subspan(20, manifest_bytes));
    ATLAS_REQUIRE(manifest.has_value());
    const tl::Value m = manifest->root();
    CHECK(m.find("layout").value_or(tl::Value()).string() == "render-tiles");
    CHECK(m.find("profile").value_or(tl::Value()).string() == "region");
    CHECK(m.find("tz").value_or(tl::Value()).string() == "Europe/Paris");
    const tl::Value origin = m.find("origin").value_or(tl::Value());
    CHECK(origin.find("lat").value_or(tl::Value()).real() == place.frame.lat0);
    CHECK(origin.find("lon").value_or(tl::Value()).real() == place.frame.lon0);
    const std::array<std::int32_t, 4> tb = tl::tile_box(tile);
    const tl::Value box = m.find("bbox").value_or(tl::Value());
    CHECK(box.find("south").value_or(tl::Value()).real() == static_cast<double>(tb[0]) / 1e7);
    CHECK(box.find("east").value_or(tl::Value()).real() == static_cast<double>(tb[3]) / 1e7);
    CHECK(m.find("config").value_or(tl::Value()).find("views").value_or(tl::Value()).size() == 0);
    MESSAGE("Paris's window ", place.name, ": ", mine.elements().size(), " elements, ", built->features, " features, ",
            built->pack.size(), " bytes; loaded at five tolerances in ", built->load_ms, " ms, cut in ", built->cut_ms,
            " ms, written in ", built->write_ms, " ms");
}

TEST_CASE(
    "load: the windows of a frame cell are one pack in the store, composed from their files (ATLAS_REGIONS_DIR)") {
    const std::string regions = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (regions.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: no windows are built here");
        return;
    }
    const std::string text = read_text(std::filesystem::path(regions) / "grachten" / "raw.json");
    ATLAS_REQUIRE(!text.empty());
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(doc.has_value());
    // three windows of the canal ring, anywhere's way: their frame cell's frame, the Netherlands' tradition
    const tl::TileKey a = tl::tile_of(14, 523'746'000, 48'840'000); // the Westerkerk's
    const std::array<tl::TileKey, 3> keys = {a, tl::TileKey(14, a.x + 1, a.y), tl::TileKey(14, a.x, a.y + 1)};
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "windows";
    std::filesystem::create_directories(folder);
    tl::Store store;
    std::vector<std::vector<std::byte>> tiles;
    for (const tl::TileKey& k : keys) {
        ATLAS_REQUIRE((k.x >> 4U) == (a.x >> 4U) && (k.y >> 4U) == (a.y >> 4U)); // one frame cell
        const auto built =
            load::build_window(query(*doc, load::window_box(k)), tl::Sha256Digest{}, k, load::place_of(k));
        ATLAS_REQUIRE(built.has_value(), built.error().what);
        const std::filesystem::path file = folder / (std::to_string(k.x) + "-" + std::to_string(k.y) + ".atlas");
        {
            std::ofstream out(file, std::ios::binary | std::ios::trunc);
            out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
                      static_cast<std::streamsize>(built->pack.size()));
        }
        // each window's own tile, to hold the cell's to
        auto own = tl::Pack::open(std::make_unique<tl::MemorySource>(built->pack));
        ATLAS_REQUIRE(own.has_value(), tl::describe(own.error()));
        tl::Zstd zstd;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        ATLAS_REQUIRE(own->unpack(own->chunks().chunks()[0], zstd, raw, scratch).has_value());
        tiles.push_back(raw);
        const auto added =
            store.add({.id = "window/" + load::place_of(k).name, .kind = tl::PackKind::window, .path = file.string()});
        ATLAS_REQUIRE(added.has_value(), tl::describe(added.error()));
    }
    const tl::TileKey cell(10, a.x >> 4U, a.y >> 4U);
    auto packs = store.packs();
    ATLAS_REQUIRE(packs.size() == 1);
    CHECK(packs[0]->id == tl::Store::cell_id(cell));
    CHECK(packs[0]->kind == tl::PackKind::window);
    ATLAS_REQUIRE(packs[0]->pack.head() != nullptr);
    CHECK(packs[0]->pack.head()->region == tl::Store::cell_id(cell));
    CHECK(packs[0]->pack.head()->frame.lat0 == load::frame_of(a).lat0);
    CHECK(packs[0]->pack.chunks().chunks().size() == 3);
    // every window's tile comes out of the cell's pack as it went in, checked against its SHA-256
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "window test, a cell's tile");
    for (std::size_t i = 0; i < keys.size(); ++i) {
        const std::vector<tl::StoreHit> hits = store.find(keys[i]);
        ATLAS_REQUIRE(hits.size() == 1);
        std::vector<std::byte> raw;
        arena.reset();
        const auto tile = store.decode(hits[0], zstd, decoder, arena, raw);
        ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
        CHECK(raw == tiles[i]);
    }
    // a window built again replaces itself; one removed leaves the others; the last removed, the cell's pack
    CHECK(
        store
            .add({.id = "window/" + load::place_of(keys[1]).name,
                  .kind = tl::PackKind::window,
                  .path = (folder / (std::to_string(keys[1].x) + "-" + std::to_string(keys[1].y) + ".atlas")).string()})
            .has_value());
    CHECK(store.packs()[0]->pack.chunks().chunks().size() == 3);
    CHECK(store.remove("window/" + load::place_of(keys[0]).name));
    packs = store.packs();
    ATLAS_REQUIRE(packs.size() == 1);
    CHECK(packs[0]->pack.chunks().chunks().size() == 2);
    CHECK(store.find(keys[0]).empty());
    CHECK(store.find(keys[2]).size() == 1);
    CHECK(store.remove("window/" + load::place_of(keys[1]).name));
    CHECK(store.remove("window/" + load::place_of(keys[2]).name));
    CHECK(store.packs().empty());
    CHECK_FALSE(store.remove("window/none"));
}
