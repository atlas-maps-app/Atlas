// The living map's scene cut into render tiles (load/Cut.hpp) against the packer's: the Westerkerk's raw.json (the
// repository's data/ams/raw.json, which loads to the scene the bundled pack was made from: AmsterdamParityTest) loaded
// and cut by the core, each tile written (tiles/TileWriter.hpp), and every tile of the bundled render pack
// (ATLAS_TILES_DIR) must be the core's, byte for byte. A tile that differs is decoded on both sides, and the first line
// of the canonical dumps that differs is named.
#include "base/arena.hpp"
#include "load/Amsterdam.hpp"
#include "load/Cut.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Directory.hpp"
#include "tiles/Dump.hpp"
#include "tiles/PackWriter.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileWriter.hpp"

#include <doctest/doctest.h>

#include <chrono>
#include <fstream>
#include <iterator>
#include <memory>
#include <optional>
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

TEST_CASE("load: the Westerkerk's pack written from its raw.json opens, checks and holds the packer's chunks") {
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
    // the bundled pack's rhead as it is and its style's tables, which a pack of the living map carries
    auto pack = atlas::test::open_pack(dir + "/amsterdam.atlas");
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    ATLAS_REQUIRE(pack->head() != nullptr);
    const tl::Section* head = tl::find_section(pack->sections(), tl::section_head);
    const tl::Section* style = tl::find_section(pack->sections(), tl::section_style);
    ATLAS_REQUIRE(head != nullptr && style != nullptr);
    std::vector<std::byte> head_scratch;
    std::vector<std::byte> style_scratch;
    const auto head_bytes = pack->source().bytes(head->offset, head->stored, head_scratch);
    const auto style_stored = pack->source().bytes(style->offset, style->stored, style_scratch);
    ATLAS_REQUIRE(head_bytes.has_value() && style_stored.has_value());
    tl::Zstd zstd;
    std::vector<std::byte> style_tables(style->raw);
    ATLAS_REQUIRE(zstd.frame(*style_stored, style_tables, tl::Site::style).has_value());
    const std::string manifest = R"({"layout":"render-tiles","payload":1,"builder":"cut_test"})";
    const auto bytes = tl::write_pack(
        tiles, {.levels = pack->head()->levels, .manifest = manifest, .head = *head_bytes, .style = style_tables});
    ATLAS_REQUIRE(bytes.has_value(), tl::describe(bytes.error()));
    auto mine = tl::Pack::open(std::make_unique<tl::MemorySource>(*bytes));
    ATLAS_REQUIRE(mine.has_value(), tl::describe(mine.error()));
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
