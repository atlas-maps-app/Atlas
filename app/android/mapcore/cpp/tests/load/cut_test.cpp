// The living map's scene cut into render tiles (load/Cut.hpp), beside the packer's: the Westerkerk's raw.json (the
// repository's data/ams/raw.json, from which the bundled pack was made) loaded and cut by the core, each tile written
// (tiles/TileWriter.hpp). Since 16 Nov 2026 (core-p14) the core's buildings are its own, cleaned (load/Clean.hpp), and
// no longer the port's, so the core cuts every tile the bundled render pack (ATLAS_TILES_DIR, the port's) holds, the
// same bytes each time, with nothing that cannot be there, and its head and style are still the packer's. Where
// ATLAS_REGIONS_DIR names the renderer's data/regions, the canal ring's raw.json is loaded as a region (whole, and at
// each tolerance its pack's views and bands load it at, for the variants) and cut, and every tile of its render pack
// is cut too, with nothing that cannot be there. A tower mapped with a height alone takes its levels from it, at most
// load::max_levels, the most stage T draws.
#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "load/Amsterdam.hpp"
#include "load/Cut.hpp"
#include "load/PackStyle.hpp"
#include "load/Regions.hpp"
#include "load/Tradition.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Directory.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Json.hpp"
#include "tiles/PackWriter.hpp"
#include "tiles/Payload.hpp"
#include "tiles/SectionWriter.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileWriter.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Points.hpp"
#include "tiles/layers/Water.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <iterator>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

namespace load = atlas::load;
namespace tl = atlas::tiles;

/// How many of the pack's tiles the core's `cut` holds (each one it lacks failed) and how many of those it cut
/// otherwise (the port cut the pack: the buildings differ, for the record), and the bytes the core wrote.
struct Compared {
    std::size_t tiles = 0;
    std::size_t missing = 0;
    std::size_t differ = 0;
    std::size_t bytes = 0;
    std::vector<tl::LayerLines> layers; ///< how the pack's tiles and the core's compare, layer by layer
};

/// The layers the buildings' cleaning touches: the buildings and parts themselves and their variants, and what stage P
/// derives from their rings: the things on their walls (a door's and a shopfront's wall), and the quays they line.
constexpr std::array<std::string_view, 5> touched_layers = {"building", "part", "variant", "thing", "quay"};

/// Holds every layer the buildings' cleaning does not touch to the port's, line for line (until cut 2 makes the test
/// packs with the core), and says how the touched ones moved.
void check_layers(const Compared& c, std::string_view what) {
    std::string moved;
    for (const tl::LayerLines& l : c.layers) {
        if (std::ranges::find(touched_layers, l.layer) != touched_layers.end()) {
            moved += std::format(" {} {}/{}", l.layer, l.differ, l.lines);
            continue;
        }
        CHECK_MESSAGE(l.differ == 0, what, ": layer ", l.layer,
                      ", which the buildings' cleaning does not touch: ", l.differ, " of ", l.lines,
                      " lines are not the port's");
    }
    MESSAGE(what, ": lines not the port's, of the layers the cleaning touches:", moved);
}

Compared compare_with_pack(const std::string& path, const std::vector<tl::TileFill>& cut) {
    Compared out;
    std::unordered_map<std::uint64_t, const tl::TileFill*> mine;
    for (const tl::TileFill& t : cut) mine[tl::TileKey(t.level(), t.x(), t.y()).packed()] = &t;
    auto pack = atlas::test::open_pack(path);
    ATLAS_REQUIRE_OR_RETURN(out, pack.has_value(), tl::describe(pack.error()));
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "cut test");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    for (const tl::ChunkRef& chunk : pack->chunks().chunks()) {
        ++out.tiles;
        arena.reset();
        auto theirs = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE_OR_RETURN(out, theirs.has_value(), tl::describe(theirs.error()));
        const auto it = mine.find(chunk.tile.packed());
        if (it == mine.end()) {
            ++out.missing;
            FAIL_CHECK("tile ", int{chunk.tile.z}, "/", chunk.tile.x, "/", chunk.tile.y, ": the core cut no such tile");
            continue;
        }
        const auto written = tl::encode_tile(*it->second);
        ATLAS_REQUIRE_OR_RETURN(out, written.has_value(), tl::describe(written.error()));
        out.bytes += written->size();
        if (*written == raw) {
            tl::compare_layers(*theirs, *theirs, out.layers); // the same tile: its lines counted, none differing
            continue;
        }
        ++out.differ;
        tl::TileDecoder again;
        atlas::Arena arena2(8U << 20U, atlas::Growth::grows, "cut test, the core's tile");
        const auto ours = again.decode(*written, arena2);
        ATLAS_REQUIRE_OR_RETURN(out, ours.has_value(), tl::describe(ours.error()));
        tl::compare_layers(*ours, *theirs, out.layers);
    }
    return out;
}

/// Whether two cuts are the same tiles, byte for byte.
bool same_tiles(const std::vector<tl::TileFill>& a, const std::vector<tl::TileFill>& b) {
    if (a.size() != b.size()) return false;
    for (std::size_t i = 0; i < a.size(); ++i) {
        const auto x = tl::encode_tile(a[i]);
        const auto y = tl::encode_tile(b[i]);
        if (!x || !y || *x != *y) return false;
    }
    return true;
}

/// The Westerkerk's raw.json, its document and scene (they view the text), and the core's tiles of it.
struct Westerkerk {
    std::string text;
    std::optional<atlas::osm::Document> doc;
    std::optional<load::Scene> scene;
    std::vector<tl::TileFill> tiles;
    double load_ms = 0.0;
    double cut_ms = 0.0;
};

/// Reads, loads and cuts the repository's data/ams/raw.json; nothing (the test case failed) where a step cannot.
std::unique_ptr<Westerkerk> westerkerk() {
    auto w = std::make_unique<Westerkerk>();
    std::ifstream in(std::string(ATLAS_TESTS_DIR) + "/../../../../../data/ams/raw.json", std::ios::binary);
    ATLAS_REQUIRE_OR_RETURN(nullptr, in.good(), "data/ams/raw.json is not in the repository");
    w->text.assign((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto t0 = std::chrono::steady_clock::now();
    auto doc = atlas::osm::read_overpass(std::as_bytes(std::span(w->text.data(), w->text.size())));
    ATLAS_REQUIRE_OR_RETURN(nullptr, doc.has_value());
    w->doc = std::move(*doc);
    auto scene = load::load_amsterdam(*w->doc, load::westerkerk_frame(), load::amsterdam_palette());
    ATLAS_REQUIRE_OR_RETURN(nullptr, scene.has_value());
    w->scene = std::move(*scene);
    const auto t1 = std::chrono::steady_clock::now();
    auto cut = load::cut_living(*w->scene, load::westerkerk_frame());
    ATLAS_REQUIRE_OR_RETURN(nullptr, cut.has_value(), cut.error().what);
    w->tiles = std::move(*cut);
    const auto t2 = std::chrono::steady_clock::now();
    w->load_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    w->cut_ms = std::chrono::duration<double, std::milli>(t2 - t1).count();
    return w;
}

} // namespace

TEST_CASE("load: the Westerkerk's tiles cut from its raw.json: the bundled pack's tiles, the same each time, with "
          "nothing that cannot be there (ATLAS_TILES_DIR)") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled pack is not compared here");
        return;
    }
    const auto w = westerkerk();
    ATLAS_REQUIRE(w != nullptr);
    const std::optional<load::Scene>& scene = w->scene;
    ATLAS_REQUIRE(scene.has_value());
    const std::vector<tl::TileFill>& cut = w->tiles;
    const load::BuildingCounts& counts = scene->counts;
    CHECK(counts.overlapping_pairs == 0);
    CHECK(counts.uncovered_outlines == 0);
    CHECK(counts.unsettled == 0);
    CHECK(counts.unmeasured == 0);
    const auto again = westerkerk();
    ATLAS_REQUIRE(again != nullptr);
    CHECK(same_tiles(cut, again->tiles));
    const Compared c = compare_with_pack(dir + "/amsterdam.atlas", cut);
    MESSAGE("the Westerkerk: ", cut.size(), " tiles cut (", c.bytes, " bytes) for the pack's ", c.tiles, ", ", c.differ,
            " cut otherwise than the port; ", counts.covered, " outlines drawn less their parts, ", counts.hidden,
            " covered whole, ", counts.clipped, " footprints clipped, ", counts.underground, " below the ground, ",
            counts.flat_roofs, " pitched roofs drawn flat; read and loaded in ", w->load_ms, " ms, cut in ", w->cut_ms,
            " ms");
    CHECK(cut.size() == c.tiles);
    CHECK(c.missing == 0);
    check_layers(c, "the Westerkerk");
}

TEST_CASE("load: the Westerkerk's pack written from its raw.json, with the core's own head and style, has the "
          "packer's tiles, head and style") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled pack is not compared here");
        return;
    }
    const auto w = westerkerk();
    ATLAS_REQUIRE(w != nullptr);
    std::vector<tl::PackTile> tiles;
    for (const tl::TileFill& t : w->tiles) {
        auto raw = tl::encode_tile(t);
        ATLAS_REQUIRE(raw.has_value(), tl::describe(raw.error()));
        const auto reach = tl::reach_of(t);
        ATLAS_REQUIRE(reach.has_value(), tl::describe(reach.error()));
        std::uint32_t count = 0;
        for (const tl::LayerFill& l : t.layers()) count += l.count();
        tiles.push_back(
            {.key = tl::TileKey(t.level(), t.x(), t.y()), .raw = std::move(*raw), .count = count, .reach = *reach});
    }
    // the core's own rhead and style: the head of the bundled pack's values (the packer's views, source and engine
    // version) with the core's style version, the style the core composes for the living map
    auto pack = atlas::test::open_pack(dir + "/amsterdam.atlas");
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    ATLAS_REQUIRE(pack->head() != nullptr);
    const tl::RenderHead& bundled = *pack->head();
    std::vector<tl::HeadViewOut> views;
    for (const tl::HeadView& v : bundled.views()) {
        views.push_back({.name = v.name, .camera = v.camera, .tolerance = v.tolerance});
    }
    const std::string version = load::style_version();
    const auto head = tl::encode_head({.profile = bundled.profile,
                                       .levels = bundled.levels,
                                       .frame = bundled.frame,
                                       .region = bundled.region,
                                       .engine_version = bundled.engine_version,
                                       .style_version = version,
                                       .source = bundled.source,
                                       .views = views,
                                       .bands = bundled.bands()});
    ATLAS_REQUIRE(head.has_value(), tl::describe(head.error()));
    const auto style = load::style_section({.palette = load::amsterdam_palette(), .state = {}});
    ATLAS_REQUIRE(style.has_value());
    const std::string manifest = R"({"layout":"render-tiles","payload":1,"builder":"cut_test"})";
    const auto bytes =
        tl::write_pack(tiles, {.levels = bundled.levels, .manifest = manifest, .head = *head, .style = *style});
    ATLAS_REQUIRE(bytes.has_value(), tl::describe(bytes.error()));
    auto mine = tl::Pack::open(std::make_unique<tl::MemorySource>(*bytes));
    ATLAS_REQUIRE(mine.has_value(), tl::describe(mine.error()));
    // the sections the packer wrote: the same head (stored as it is), the same style tables (each compressed its own
    // way)
    const tl::Section* head_ours = tl::find_section(mine->sections(), tl::section_head);
    const tl::Section* head_theirs = tl::find_section(pack->sections(), tl::section_head);
    const tl::Section* style_theirs = tl::find_section(pack->sections(), tl::section_style);
    ATLAS_REQUIRE(head_ours != nullptr && head_theirs != nullptr && style_theirs != nullptr);
    CHECK(head_ours->raw == head_theirs->raw);
    CHECK(head_ours->crc == head_theirs->crc);
    std::vector<std::byte> scratch;
    const auto stored = pack->source().bytes(style_theirs->offset, style_theirs->stored, scratch);
    ATLAS_REQUIRE(stored.has_value());
    std::vector<std::byte> theirs_style(style_theirs->raw);
    tl::Zstd zstd;
    ATLAS_REQUIRE(zstd.frame(*stored, theirs_style, tl::Site::style).has_value());
    CHECK(theirs_style == *style);
    const auto verified = mine->verify();
    CHECK_MESSAGE(verified.has_value(), (verified ? std::string() : tl::describe(verified.error())));
    // the packer's tiles in the packer's order (their bytes the core's own: its buildings cleaned)
    const std::span<const tl::ChunkRef> ours = mine->chunks().chunks();
    const std::span<const tl::ChunkRef> theirs = pack->chunks().chunks();
    ATLAS_REQUIRE(ours.size() == theirs.size());
    for (std::size_t i = 0; i < ours.size(); ++i) CHECK(ours[i].tile == theirs[i].tile);
    MESSAGE("the Westerkerk's pack from its raw.json: ", bytes->size(), " bytes, ", ours.size(),
            " tiles (the packer's: ", pack->source().size(), " bytes)");
}

TEST_CASE("load: the canal ring's tiles cut from its raw.json: the pack's tiles, with nothing that cannot be there "
          "(ATLAS_REGIONS_DIR, ATLAS_TILES_DIR)") {
    const std::string regions = atlas::test::environment("ATLAS_REGIONS_DIR");
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (regions.empty() || dir.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR or ATLAS_TILES_DIR is not set: the canal ring is not cut here");
        return;
    }
    const auto read_text = [](const std::filesystem::path& path) {
        std::ifstream in(path, std::ios::binary);
        return std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    };
    const std::string text = read_text(std::filesystem::path(regions) / "grachten" / "raw.json");
    const std::string region_json = read_text(std::filesystem::path(regions) / "grachten" / "region.json");
    ATLAS_REQUIRE(!text.empty() && !region_json.empty());
    const auto doc = atlas::osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(doc.has_value());
    const auto region = tl::read_json(std::as_bytes(std::span(region_json.data(), region_json.size())));
    ATLAS_REQUIRE(region.has_value());
    const tl::Value bbox = region->root().find("bbox").value_or(tl::Value());
    ATLAS_REQUIRE(bbox.size() == 4);
    auto pack = atlas::test::open_pack(dir + "/grachten.atlas");
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    ATLAS_REQUIRE(pack->head() != nullptr);
    const tl::RenderHead& head = *pack->head();
    const load::RegionConfig config{.name = "grachten",
                                    .frame = head.frame,
                                    .bbox = {bbox.child(0).number().value_or(0), bbox.child(1).number().value_or(0),
                                             bbox.child(2).number().value_or(0), bbox.child(3).number().value_or(0)},
                                    .tradition = nullptr,
                                    .palette = load::region_palette("grachten")};
    const auto t0 = std::chrono::steady_clock::now();
    const auto base = load::load_region(*doc, config);
    ATLAS_REQUIRE(base.has_value());
    // the variants: the region loaded at each tolerance its views and the live map's bands load it at
    std::set<std::uint8_t> levels;
    for (const tl::HeadView& v : head.views()) levels.insert(v.tolerance);
    for (const tl::HeadBand& b : head.bands()) levels.insert(b.tolerance);
    levels.erase(0);
    std::vector<load::Scene> others;
    others.reserve(levels.size());
    for (const std::uint8_t l : levels) {
        auto other = load::load_region(*doc, config, tl::keep::tolerances.at(l));
        ATLAS_REQUIRE(other.has_value());
        others.push_back(std::move(*other));
    }
    std::vector<load::CutVariant> variants;
    variants.reserve(levels.size());
    std::size_t k = 0;
    for (const std::uint8_t l : levels)
        variants.push_back({.tolerance = tl::keep::tolerances.at(l), .scene = &others[k++]});
    const auto t1 = std::chrono::steady_clock::now();
    const auto cut = load::cut_region(*base, head.frame, variants);
    ATLAS_REQUIRE(cut.has_value(), cut.error().what);
    const auto t2 = std::chrono::steady_clock::now();
    const Compared c = compare_with_pack(dir + "/grachten.atlas", *cut);
    // what the region's own parts made, so that a layer both sides leave empty cannot pass unnoticed
    std::unordered_map<std::string_view, std::size_t> made;
    for (const tl::TileFill& t : *cut) {
        for (const tl::LayerFill& lf : t.layers()) made[lf.schema().label] += lf.count();
    }
    MESSAGE("features: ", made["variant"], " variants, ", made["item"], " items, ", made["line"], " lines, ",
            made["ground"], " ground, ", made["water"], " water pieces, ", made["quay"], " quays");
    CHECK(made["variant"] > 0);
    CHECK(made["item"] > 0);
    const auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    const load::BuildingCounts& counts = base->counts;
    MESSAGE("the canal ring: ", cut->size(), " tiles cut (", c.bytes, " bytes) for the pack's ", c.tiles, ", ",
            c.differ, " cut otherwise than the port; ", counts.overlapping_pairs, " pairs overlapping, ",
            counts.uncovered_outlines, " outlines uncovered, ", counts.flat_roofs,
            " pitched roofs drawn flat; loaded at ", levels.size() + 1, " tolerances in ", ms(t0, t1), " ms, cut in ",
            ms(t1, t2), " ms");
    CHECK(cut->size() == c.tiles);
    CHECK(c.missing == 0);
    CHECK(counts.overlapping_pairs == 0);
    CHECK(counts.uncovered_outlines == 0);
    check_layers(c, "the canal ring");
}

namespace {

/// What a cut gives the water and the facades: its quay edges (each edge's ends on the grid), their length within 250 m
/// of the Westerkerk, where the raw.json maps every building about the water, and the doors and shopfronts filed on a
/// building.
struct Waterside {
    std::set<std::array<std::int64_t, 4>> edges;
    double near = 0.0;
    std::size_t doors = 0;
};

Waterside waterside_of(const std::vector<tl::TileFill>& tiles) {
    Waterside out;
    const tl::Frame frame = load::westerkerk_frame();
    const load::V2 church = load::xz(frame, 52.3746, 4.8840);
    for (const tl::TileFill& t : tiles) {
        auto raw = tl::encode_tile(t);
        ATLAS_REQUIRE_OR_RETURN(out, raw.has_value(), tl::describe(raw.error()));
        tl::TileDecoder decoder;
        atlas::Arena arena(8U << 20U, atlas::Growth::grows, "cut test, the waterside");
        const auto tile = decoder.decode(*raw, arena);
        ATLAS_REQUIRE_OR_RETURN(out, tile.has_value(), tl::describe(tile.error()));
        const tl::Layer* quays = tile->layer(tl::LayerId::quay);
        for (std::uint32_t i = 0; quays != nullptr && i < quays->count; ++i) {
            const tl::RingView e = quays->ring(tl::quay::edge, i);
            if (e.size() < 2) continue;
            out.edges.insert({e.lat[0], e.lon[0], e.lat[1], e.lon[1]});
            const double ax = frame.x(e.lon[0]);
            const double az = frame.z(e.lat[0]);
            const double bx = frame.x(e.lon[1]);
            const double bz = frame.z(e.lat[1]);
            if (atlas::pymath::hypot(((ax + bx) / 2) - church.x, ((az + bz) / 2) - church.z) < 250.0) {
                out.near += atlas::pymath::hypot(bx - ax, bz - az);
            }
        }
        const tl::Layer* things = tile->layer(tl::LayerId::thing);
        for (std::uint32_t i = 0; things != nullptr && i < things->count; ++i) {
            if (things->nullable_integer(tl::thing::building, i)) ++out.doors;
        }
    }
    return out;
}

} // namespace

// The engine's close-up cut (RegionConfig::close_up: the phone's windows and the app's canal ring, which draws the
// Westerkerk since alpha 20) against the renders' two cuts of the same place, the Westerkerk's raw.json. The living map
// takes every water edge for a quay and puts the doors on the facades; the renders' region rules, which the canal ring
// was cut by for a view from afar, give the canals natural shores and the facades no doors, so its water had no quay
// walls and mirrored nothing (the user, 29 October: "I cannot see any reflections on the water", "Make sure no quality
// is lost"). Close up, the quays are the edges a building lines: nine tenths of the living map's about the Westerkerk,
// the rest where two water rings meet (water on both sides) or the raw.json maps no building; and the doors are the
// living map's.
TEST_CASE("load: the close-up cut gives the canals the quays their buildings line and the facades their doors, as the "
          "living map's cut does where the renders' region rules give neither") {
    const auto w = westerkerk();
    ATLAS_REQUIRE(w != nullptr);
    const Waterside living = waterside_of(w->tiles);
    load::RegionConfig config{.name = "window",
                              .frame = load::westerkerk_frame(),
                              .bbox = {52.36, 4.87, 52.39, 4.92},
                              .tradition = nullptr,
                              .palette = load::amsterdam_palette()};
    const auto cut_as = [&](bool close_up) -> std::optional<Waterside> {
        config.close_up = close_up;
        const auto scene = load::load_region(*w->doc, config);
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, scene.has_value());
        CHECK(scene->close_up == close_up);
        const auto cut = load::cut_region(*scene, load::westerkerk_frame(), {});
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, cut.has_value(), cut.error().what);
        return waterside_of(*cut);
    };
    const std::optional<Waterside> afar = cut_as(false);
    const std::optional<Waterside> close = cut_as(true);
    ATLAS_REQUIRE(afar.has_value() && close.has_value());
    MESSAGE("quay edges: the living map's ", living.edges.size(), " (", living.near,
            " m within 250 m of the Westerkerk), the region rules' ", afar->edges.size(), ", close up ",
            close->edges.size(), " (", close->near, " m); doors and shopfronts: ", living.doors, ", ", afar->doors,
            ", ", close->doors);
    CHECK(!living.edges.empty());
    CHECK(living.doors > 0);
    CHECK(afar->edges.empty());
    CHECK(afar->doors == 0);
    // close up, the living map's edges that buildings line: the rest are where two water rings meet (water on both
    // sides) or the raw.json maps no building
    CHECK(std::ranges::includes(living.edges, close->edges));
    CHECK(close->near >= 0.9 * living.near);
    CHECK(close->doors == living.doors);
}

TEST_CASE("load: the input whose road grid ran CI's fuzz_load out of memory cuts the living map, its long roads kept "
          "out of the grid (fuzz/corpus/fuzz_load/oom-road-grid)") {
    const std::filesystem::path file =
        std::filesystem::path(ATLAS_TESTS_DIR).parent_path() / "fuzz" / "corpus" / "fuzz_load" / "oom-road-grid";
    std::ifstream in(file, std::ios::binary);
    ATLAS_REQUIRE(in.good(), file.string());
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto doc = atlas::osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(doc.has_value(), "the input reads");
    // fuzz_load's two ways: the New looks, and the region's own (the input's size chose the second)
    const load::Looks looks{
        .region = "ams", .tradition = nullptr, .landuse = load::LandUseFrom::areas, .keep_homes = true};
    for (const load::Looks* l : {&looks, static_cast<const load::Looks*>(nullptr)}) {
        const auto scene = load::load_amsterdam(*doc, load::westerkerk_frame(), load::amsterdam_palette(), l);
        if (!scene) continue;
        const auto started = std::chrono::steady_clock::now();
        const auto tiles = load::cut_living(*scene, load::westerkerk_frame());
        const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
        MESSAGE(std::format("{}: cut in {:.2f} s, {}", l != nullptr ? "the New looks" : "the region's looks", seconds,
                            tiles ? std::format("{} tiles", tiles->size()) : tiles.error().what));
        CHECK(seconds < 30.0);
    }
}

TEST_CASE("load: a building mapped with a height alone takes its levels from it, at most load::max_levels, the most "
          "stage T draws") {
    // a tradition's look (guess_tags: levels = round((h - roof_h) / floor_h) where only a height is mapped), its roofs
    // flat and its storeys 3 m: 450 m is 150 levels, 900 m would be 300, past what a building holds
    // (tl::building::most_levels, where stage T reads a record's levels as unmapped and draws no windows)
    const auto tradition = load::read_tradition(std::as_bytes(std::span(std::string_view(R"(
{"name": "n", "says": "s", "floor_h": 3.0, "levels": {"default": 2}, "walls": {"default": [["brick", "#8E5A46", 1]]},
 "roof": {"default": {"shapes": [["flat", 1]], "material": "metal", "colours": ["#737E88"]}}})"))));
    ATLAS_REQUIRE(tradition.has_value());
    std::string data = R"({"elements": [)";
    const std::array<std::pair<std::string_view, std::int64_t>, 2> towers = {{{"450", 150}, {"900", 200}}};
    for (std::size_t i = 0; i < towers.size(); ++i) {
        // a 24 m square near the Westerkerk, each a little east of the last
        const double lon = 4.8800 + (0.0010 * static_cast<double>(i));
        data += std::format(
            R"({}{{"type": "way", "id": {}, "tags": {{"building": "office", "height": "{}"}}, "geometry": [)"
            R"({{"lat": 52.3700, "lon": {:.4f}}}, {{"lat": 52.3700, "lon": {:.4f}}}, {{"lat": 52.37022, "lon": {:.4f}}},)"
            R"( {{"lat": 52.37022, "lon": {:.4f}}}, {{"lat": 52.3700, "lon": {:.4f}}}]}})",
            i == 0 ? "" : ",\n", 900 + i, towers.at(i).first, lon, lon + 0.00035, lon + 0.00035, lon, lon);
    }
    data += "]}";
    const auto doc = atlas::osm::read_overpass(std::as_bytes(std::span(data.data(), data.size())));
    ATLAS_REQUIRE(doc.has_value());
    const load::RegionConfig config{.name = "test",
                                    .frame = load::westerkerk_frame(),
                                    .bbox = {52.36, 4.87, 52.39, 4.92},
                                    .tradition = *tradition,
                                    .palette = load::amsterdam_palette()};
    const auto scene = load::load_region(*doc, config);
    ATLAS_REQUIRE(scene.has_value());
    REQUIRE(scene->buildings.size() == towers.size());
    std::map<std::int64_t, std::int64_t> want;
    for (std::size_t i = 0; i < towers.size(); ++i) want[900 + static_cast<std::int64_t>(i)] = towers.at(i).second;
    for (const load::Building& b : scene->buildings) {
        CAPTURE(b.id);
        CHECK(b.levels == want[b.id]);
    }
    // and the cut writes them so: within what stage T draws
    const auto cut = load::cut_region(*scene, config.frame, {});
    ATLAS_REQUIRE(cut.has_value(), cut.error().what);
    std::size_t seen = 0;
    for (const tl::TileFill& t : *cut) {
        auto raw = tl::encode_tile(t);
        ATLAS_REQUIRE(raw.has_value(), tl::describe(raw.error()));
        tl::TileDecoder decoder;
        atlas::Arena arena(8U << 20U, atlas::Growth::grows, "cut test, the towers");
        const auto tile = decoder.decode(*raw, arena);
        ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
        const tl::Layer* buildings = tile->layer(tl::LayerId::building);
        for (std::uint32_t i = 0; buildings != nullptr && i < buildings->count; ++i) {
            const std::int64_t id = buildings->integer(tl::building::id, i);
            CAPTURE(id);
            const std::optional<std::int64_t> levels = buildings->nullable_integer(tl::building::levels, i);
            ATLAS_REQUIRE(levels.has_value(), "a tower without its levels");
            CHECK(*levels == want[id]);
            CHECK(*levels <= tl::building::most_levels);
            ++seen;
        }
    }
    CHECK(seen >= towers.size());
}
