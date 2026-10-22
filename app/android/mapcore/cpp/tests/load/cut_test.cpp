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
#include "tiles/Dump.hpp"
#include "tiles/Payload.hpp"
#include "tiles/TileWriter.hpp"

#include <doctest/doctest.h>

#include <chrono>
#include <fstream>
#include <iterator>
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

} // namespace

TEST_CASE("load: the Westerkerk's tiles cut from its raw.json are the packer's, byte for byte (ATLAS_TILES_DIR)") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled pack is not compared here");
        return;
    }
    std::ifstream in(std::string(ATLAS_TESTS_DIR) + "/../../../../../data/ams/raw.json", std::ios::binary);
    ATLAS_REQUIRE(in.good(), "data/ams/raw.json is not in the repository");
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto t0 = std::chrono::steady_clock::now();
    const auto doc = atlas::osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(doc.has_value());
    const auto scene = load::load_amsterdam(*doc, load::westerkerk_frame(), load::amsterdam_palette());
    ATLAS_REQUIRE(scene.has_value());
    const auto t1 = std::chrono::steady_clock::now();
    const auto cut = load::cut_living(*scene, load::westerkerk_frame());
    ATLAS_REQUIRE(cut.has_value(), cut.error().what);
    const auto t2 = std::chrono::steady_clock::now();
    std::unordered_map<std::uint64_t, const tl::TileFill*> mine;
    for (const tl::TileFill& t : *cut) mine[tl::TileKey(t.level(), t.x(), t.y()).packed()] = &t;

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
    const auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    MESSAGE("the Westerkerk: ", cut->size(), " tiles cut (", bytes, " bytes) for the pack's ", tiles, "; ", wrong,
            " not the packer's; read and loaded in ", ms(t0, t1), " ms, cut in ", ms(t1, t2), " ms");
    CHECK(cut->size() == tiles);
    CHECK(wrong == 0);
}
