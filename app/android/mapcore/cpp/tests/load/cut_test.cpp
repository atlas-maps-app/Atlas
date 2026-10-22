// The living map's scene cut into render tiles (load/Cut.hpp) against the packer's: the Westerkerk's raw.json (the
// repository's data/ams/raw.json, which loads to the scene the bundled pack was made from: AmsterdamParityTest) loaded
// and cut by the core, each tile written (tiles/TileWriter.hpp), and every tile of the bundled render pack
// (ATLAS_TILES_DIR) must be the core's, byte for byte. A tile that differs is decoded on both sides, and the first line
// of the canonical dumps that differs is named. Where ATLAS_REGIONS_DIR names the renderer's data/regions, the canal
// ring's raw.json is loaded as a region (whole, and at each tolerance its pack's views and bands load it at, for the
// variants) and cut, and every tile of its render pack must be the core's too.
#include "base/arena.hpp"
#include "load/Amsterdam.hpp"
#include "load/Cut.hpp"
#include "load/PackStyle.hpp"
#include "load/Regions.hpp"
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

#include <doctest/doctest.h>

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

namespace load = atlas::load;
namespace tl = atlas::tiles;

/// The first line where two texts differ, both sides, cut to 400 characters each.
std::string first_difference(const std::string& core, const std::string& packer) {
    std::istringstream a(core);
    std::istringstream b(packer);
    std::string la;
    std::string lb;
    for (std::size_t line = 1;; ++line) {
        const bool ha = static_cast<bool>(std::getline(a, la));
        const bool hb = static_cast<bool>(std::getline(b, lb));
        if (!ha && !hb) return "no line differs";
        if (!ha || !hb || la != lb) {
            return "line " + std::to_string(line) + "\n  core:   " + (ha ? la.substr(0, 400) : "(none)") +
                   "\n  packer: " + (hb ? lb.substr(0, 400) : "(none)");
        }
    }
}

/// How many of the pack's tiles the core's `cut` matches byte for byte (each other one failed, the first few with the
/// first line of the canonical dumps that differs), and the bytes the core wrote.
struct Compared {
    std::size_t tiles = 0;
    std::size_t wrong = 0;
    std::size_t bytes = 0;
};

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
            ++out.wrong;
            FAIL_CHECK("tile ", int{chunk.tile.z}, "/", chunk.tile.x, "/", chunk.tile.y, ": the core cut no such tile");
            continue;
        }
        const auto written = tl::encode_tile(*it->second);
        ATLAS_REQUIRE_OR_RETURN(out, written.has_value(), tl::describe(written.error()));
        out.bytes += written->size();
        if (*written == raw) continue;
        if (out.wrong++ >= 4) continue;
        const std::string packer = tl::canonical_dump(*theirs);
        tl::TileDecoder again;
        atlas::Arena arena2(8U << 20U, atlas::Growth::grows, "cut test, the core's tile");
        const auto ours = again.decode(*written, arena2);
        ATLAS_REQUIRE_OR_RETURN(out, ours.has_value(), tl::describe(ours.error()));
        FAIL_CHECK("tile ", int{chunk.tile.z}, "/", chunk.tile.x, "/", chunk.tile.y, ": ", written->size(),
                   " bytes for the packer's ", raw.size(), "; the dumps differ at ",
                   first_difference(tl::canonical_dump(*ours), packer));
    }
    return out;
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

TEST_CASE("load: the Westerkerk's tiles cut from its raw.json are the packer's, byte for byte (ATLAS_TILES_DIR)") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled pack is not compared here");
        return;
    }
    const auto w = westerkerk();
    ATLAS_REQUIRE(w != nullptr);
    const std::vector<tl::TileFill>& cut = w->tiles;
    std::unordered_map<std::uint64_t, const tl::TileFill*> mine;
    for (const tl::TileFill& t : cut) mine[tl::TileKey(t.level(), t.x(), t.y()).packed()] = &t;

    auto pack = atlas::test::open_pack(dir + "/amsterdam.atlas");
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "cut test");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    std::size_t tiles = 0;
    std::size_t wrong = 0;
    std::size_t bytes = 0;
    for (const tl::ChunkRef& chunk : pack->chunks().chunks()) {
        ++tiles;
        arena.reset();
        auto theirs = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(theirs.has_value(), tl::describe(theirs.error()));
        const auto it = mine.find(chunk.tile.packed());
        if (it == mine.end()) {
            ++wrong;
            FAIL_CHECK("tile ", int{chunk.tile.z}, "/", chunk.tile.x, "/", chunk.tile.y, ": the core cut no such tile");
            continue;
        }
        const auto written = tl::encode_tile(*it->second);
        ATLAS_REQUIRE(written.has_value(), tl::describe(written.error()));
        bytes += written->size();
        if (*written == raw) continue;
        if (wrong++ >= 3) continue;
        const std::string packer = tl::canonical_dump(*theirs);
        tl::TileDecoder again;
        atlas::Arena arena2(8U << 20U, atlas::Growth::grows, "cut test, the core's tile");
        const auto ours = again.decode(*written, arena2);
        ATLAS_REQUIRE(ours.has_value(), tl::describe(ours.error()));
        FAIL_CHECK("tile ", int{chunk.tile.z}, "/", chunk.tile.x, "/", chunk.tile.y, ": ", written->size(),
                   " bytes for the packer's ", raw.size(), "; the dumps differ at ",
                   first_difference(tl::canonical_dump(*ours), packer));
    }
    MESSAGE("the Westerkerk: ", cut.size(), " tiles cut (", bytes, " bytes) for the pack's ", tiles, "; ", wrong,
            " not the packer's; read and loaded in ", w->load_ms, " ms, cut in ", w->cut_ms, " ms");
    CHECK(cut.size() == tiles);
    CHECK(wrong == 0);
}

TEST_CASE(
    "load: the Westerkerk's pack written from its raw.json, with the core's own head and style, is the packer's") {
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
    // the packer's chunks in the packer's order: each tile's hash, size, feature count and reach
    const std::span<const tl::ChunkRef> ours = mine->chunks().chunks();
    const std::span<const tl::ChunkRef> theirs = pack->chunks().chunks();
    ATLAS_REQUIRE(ours.size() == theirs.size());
    for (std::size_t i = 0; i < ours.size(); ++i) {
        CHECK(ours[i].tile == theirs[i].tile);
        CHECK(ours[i].sha256 == theirs[i].sha256);
        CHECK(ours[i].raw == theirs[i].raw);
        CHECK(ours[i].count == theirs[i].count);
        CHECK(ours[i].reach == theirs[i].reach);
    }
    MESSAGE("the Westerkerk's pack from its raw.json: ", bytes->size(), " bytes, ", ours.size(),
            " tiles (the packer's: ", pack->source().size(), " bytes)");
}

TEST_CASE("load: the canal ring's tiles cut from its raw.json are the packer's (ATLAS_REGIONS_DIR, ATLAS_TILES_DIR)") {
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
    MESSAGE("the canal ring: ", cut->size(), " tiles cut (", c.bytes, " bytes) for the pack's ", c.tiles, "; ", c.wrong,
            " not the packer's; loaded at ", levels.size() + 1, " tolerances in ", ms(t0, t1), " ms, cut in ",
            ms(t1, t2), " ms");
    CHECK(cut->size() == c.tiles);
    CHECK(c.wrong == 0);
}
