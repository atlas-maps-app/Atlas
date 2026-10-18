// The tile reader on real packs (plan-vector K3, L0 (b)): the bundled render packs, when ATLAS_TILES_DIR names a folder
// holding them (Gradle's generateBundledTiles output, or the packs CI pushes to the emulator). Every pack opens, passes
// verify() (every section's CRC-32, every chunk's SHA-256), and every tile decodes and agrees with its chunk (its key,
// its reach); a pack never simplified (the living map) carries no keep codes. Each tile's canonical hash is printed as
// `tiles l0 <pack> <z>/<x>/<y> <sha256>`, the line the JVM's L0 test and the emulator's run compare with the Kotlin
// writer's. With ATLAS_TILES_EXPECT naming a file of such lines, every line must be there and equal.
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Payload.hpp"

#include <doctest/doctest.h>

#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <map>
#include <string>

namespace {

using namespace atlas::tiles;

TEST_CASE("tiles: the bundled packs open, verify and decode, and their canonical hashes are printed") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the real packs are not checked here");
        return;
    }
    std::map<std::string, std::string> expected;
    if (const std::string expect = atlas::test::environment("ATLAS_TILES_EXPECT"); !expect.empty()) {
        std::ifstream in(expect);
        ATLAS_REQUIRE(in.good(), "ATLAS_TILES_EXPECT names no readable file: " << expect);
        for (std::string line; std::getline(in, line);) {
            if (!line.starts_with("tiles l0 ")) continue;
            const std::size_t last = line.rfind(' ');
            expected[line.substr(0, last)] = line.substr(last + 1);
        }
        ATLAS_REQUIRE(!expected.empty());
    }
    std::size_t packs = 0;
    std::size_t matched = 0;
    for (const auto& file : std::filesystem::directory_iterator(dir)) {
        if (file.path().extension() != ".atlas") continue;
        const std::string name = file.path().stem().string();
        auto pack = atlas::test::open_pack(file.path().string());
        ATLAS_REQUIRE(pack, name << ": " << describe(pack.error()));
        if (!pack->is_render()) continue;
        ++packs;
        auto verified = pack->verify();
        ATLAS_REQUIRE(verified, name << ": " << describe(verified.error()));
        Zstd zstd;
        TileDecoder decoder;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        const bool whole = pack->head()->never_simplified();
        for (const ChunkRef& c : pack->chunks().chunks()) {
            atlas::Arena arena(1U << 20U, atlas::Growth::grows, "tile");
            auto tile = pack->decode(c, zstd, decoder, arena, raw, scratch);
            ATLAS_REQUIRE(tile, name << " " << int{c.tile.z} << "/" << c.tile.x << "/" << c.tile.y << ": "
                                     << describe(tile.error()));
            CHECK(tile->feature_count() == c.count);
            for (const Layer& l : tile->layers) {
                for (std::size_t slot = 0; slot < l.columns.size(); ++slot) {
                    const bool keep = l.schema->columns[slot].codec == Codec::keep && l.columns[slot].present;
                    CHECK_MESSAGE(!(whole && keep),
                                  name << ": a pack never simplified carries " << l.schema->label << "'s keep codes");
                }
            }
            const std::string key = std::format("tiles l0 {} {}/{}/{}", name, c.tile.z, c.tile.x, c.tile.y);
            const std::string hash = hex(canonical_hash(*tile));
            std::printf("%s %s\n", key.c_str(), hash.c_str()); // NOLINT: the line CI collects
            if (!expected.empty()) {
                const auto it = expected.find(key);
                CHECK_MESSAGE(it != expected.end(), key << " is not in the expected hashes");
                if (it != expected.end()) {
                    CHECK_MESSAGE(it->second == hash, key << ": " << hash << ", the Kotlin writer's " << it->second);
                    ++matched;
                }
            }
            if (const std::string out = atlas::test::environment("ATLAS_TILES_DUMP"); !out.empty()) {
                std::ofstream(std::format("{}/{}-{}-{}-{}.txt", out, name, c.tile.z, c.tile.x, c.tile.y))
                    << canonical_dump(*tile);
            }
        }
    }
    CHECK(packs > 0);
    if (!expected.empty()) CHECK(matched == expected.size());
}

} // namespace
