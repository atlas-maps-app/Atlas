// The buildings' synthetic tile (K8's, tests/sun/samples_test.cpp): every roof shape, a houseboat near the quay, a
// raised part and two trees, written by the tile reader's tests' own writer (tests/tiles/support.hpp). Stage T's
// test builds it directly; the frame's test draws it from a synthetic render pack, so CI draws every shape.
#pragma once

#include "tests/tiles/support.hpp"
#include "tiles/layers/Buildings.hpp"

#include <array>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <optional>
#include <string>
#include <string_view>
#include <unistd.h>
#include <vector>

namespace atlas::test::building {

inline constexpr std::int32_t s0 = 523'740'000; // 52.374° N
inline constexpr std::int32_t w0 = 48'840'000;  // 4.884° E

/// A rectangle of `w` × `d` grid units from (south + dy, west + dx), walked counter-clockwise on the map.
[[nodiscard]] inline Ring rect(std::int32_t dx, std::int32_t dy, std::int32_t w, std::int32_t d) {
    return {.lat = {s0 + dy, s0 + dy, s0 + dy + d, s0 + dy + d},
            .lon = {w0 + dx, w0 + dx + w, w0 + dx + w, w0 + dx},
            .keep = {}};
}

/// The tile (14/8414/5384): a flat house, gabled, hipped, pyramidal, skillion (SW) and mansard roofs, a houseboat near
/// the quay, and a raised part; and two trees, so the trees' samples begin after the buildings'.
[[nodiscard]] inline TileBytes tile() {
    namespace tl = atlas::tiles;
    namespace col = atlas::test::columns;
    TileBytes t;
    const std::vector<Ring> rings{
        rect(0, 0, 1470, 1800),     // 0: a house, 10 × 20 m, flat
        rect(3000, 0, 1176, 1260),  // 1: gabled, 8 × 14 m
        rect(6000, 0, 1470, 1080),  // 2: hipped
        rect(9000, 0, 1470, 900),   // 3: pyramidal
        rect(12000, 0, 1764, 1080), // 4: skillion, roof:direction SW
        rect(15000, 0, 2205, 1800), // 5: mansard
        rect(0, 4000, 2940, 450),   // 6: a houseboat, 20 × 5 m, near the quay
    };
    LayerBytes b;
    b.id = static_cast<std::uint8_t>(tl::LayerId::building);
    b.cells = {0, 0, 0, 0, 0, 0, 0};
    const std::uint64_t houseboat = tl::building::flag_houseboat | tl::building::flag_near_water;
    b.columns = {
        {"index", tl::Codec::delta, col::delta({0, 1, 2, 3, 4, 5, 6})},
        {"id", tl::Codec::delta, col::delta({100, 101, 102, 103, 104, 105, 106})},
        {"ring", tl::Codec::geom, col::geom(rings)},
        {"h", tl::Codec::centi, col::centi({12.0, 15.0, 14.0, 11.0, 9.5, 18.0, 2.5})},
        {"min", tl::Codec::centi, col::centi({0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0})},
        {"roof_h", tl::Codec::centi, col::centi({std::nullopt, 5.0, 4.0, 3.5, 2.0, 4.0, std::nullopt})},
        {"levels", tl::Codec::nvarz, col::nvarz({4, 3, 3, 3, 2, 4, 1})},
        {"roof", tl::Codec::str,
         col::str(t, {std::nullopt, "gabled", "hipped", "pyramidal", "skillion", "mansard", std::nullopt})},
        {"wall_rgb", tl::Codec::varu,
         col::varu({0x8E5A46, 0xA27A62, 0x74402F, 0xE6DFD1, 0x7A624C, 0xD8CCB2, 0xFFFFFF})},
        {"roof_rgb", tl::Codec::varu,
         col::varu({0x56585C, 0x9E5038, 0x535C66, 0x737E88, 0x56585C, 0x8C969E, 0x9C988F})},
        {"flags", tl::Codec::varu, col::varu({0, tl::building::flag_modern, 0, 0, 0, 0, houseboat})},
        {"tag:building", tl::Codec::str,
         col::str(t, {"house", "house", "house", "church", "shed", "apartments", "houseboat"})},
        {"tag:roof:direction", tl::Codec::str,
         col::str(t, {std::nullopt, std::nullopt, std::nullopt, std::nullopt, " sw ", std::nullopt, std::nullopt})},
    };
    LayerBytes p;
    p.id = static_cast<std::uint8_t>(tl::LayerId::part);
    p.cells = {0};
    p.columns = {
        {"index", tl::Codec::delta, col::delta({0})},
        {"id", tl::Codec::delta, col::delta({200})},
        {"ring", tl::Codec::geom, col::geom({rect(300, 300, 588, 540)})},
        {"h", tl::Codec::centi, col::centi({30.0})},
        {"min", tl::Codec::centi, col::centi({12.0})},
        {"levels", tl::Codec::nvarz, col::nvarz({6})},
        {"tag:building:part", tl::Codec::str, col::str(t, {"yes"})},
    };
    LayerBytes tr;
    tr.id = static_cast<std::uint8_t>(tl::LayerId::tree);
    tr.cells = {0, 0};
    tr.columns = {
        {"index", tl::Codec::delta, col::delta({0, 1})},
        {"node", tl::Codec::delta, col::delta({5, 9})},
        {"id", tl::Codec::delta, col::delta({900, 901})},
        {"pos", tl::Codec::point, col::point({{s0 + 2500, w0 + 500}, {s0 + 2600, w0 + 2500}})},
        {"h", tl::Codec::centi, col::centi({12.0, 9.0})},
        {"crown", tl::Codec::centi, col::centi({7.0, 5.0})},
    };
    t.layers = {b, p, tr};
    return t;
}

/// The tile's features: its buildings, its part and its trees.
inline constexpr std::uint32_t tile_features = 10;

/// A pair of buildings the renders melt (the same tile): a long low wing, an L whose front bar (40 × 10 m, 20 m high)
/// runs across in front of a tower (10 × 10 m, 60 m high, 10 m behind the bar) and whose arm runs 100 m north, so its
/// sort point (the footprint's centroid at half its height) lies farther north than the tower's. Seen from the south,
/// the bar hides the tower's foot; the renders, ordering whole items by one point each, paint the tower after the wing,
/// over the bar.
[[nodiscard]] inline TileBytes melt_tile() {
    namespace tl = atlas::tiles;
    namespace col = atlas::test::columns;
    TileBytes t;
    // a grid unit is 10^-7 degree: about 90 units a metre north, 147 east, here
    const Ring wing{.lat = {s0, s0, s0 + 900, s0 + 900, s0 + 9000, s0 + 9000},
                    .lon = {w0, w0 + 5880, w0 + 5880, w0 + 1470, w0 + 1470, w0},
                    .keep = {}};
    const std::vector<Ring> rings{wing, rect(3675, 1800, 1470, 900)};
    LayerBytes b;
    b.id = static_cast<std::uint8_t>(tl::LayerId::building);
    b.cells = {0, 0};
    b.columns = {
        {"index", tl::Codec::delta, col::delta({0, 1})},
        {"id", tl::Codec::delta, col::delta({300, 301})},
        {"ring", tl::Codec::geom, col::geom(rings)},
        {"h", tl::Codec::centi, col::centi({20.0, 60.0})},
        {"min", tl::Codec::centi, col::centi({0.0, 0.0})},
        {"roof_h", tl::Codec::centi, col::centi({std::nullopt, std::nullopt})},
        {"levels", tl::Codec::nvarz, col::nvarz({6, 18})},
        {"roof", tl::Codec::str, col::str(t, {std::nullopt, std::nullopt})},
        {"wall_rgb", tl::Codec::varu, col::varu({0x8E5A46, 0xE6DFD1})},
        {"roof_rgb", tl::Codec::varu, col::varu({0x56585C, 0x737E88})},
        {"flags", tl::Codec::varu, col::varu({0, 0})},
        {"tag:building", tl::Codec::str, col::str(t, {"house", "tower"})},
        {"tag:roof:direction", tl::Codec::str, col::str(t, {std::nullopt, std::nullopt})},
    };
    t.layers = {b};
    return t;
}

/// The melting pair's features.
inline constexpr std::uint32_t melt_features = 2;

/// Writes tile `t` (its `features`) as a render pack of 14/8414/5384 (the tile reader's tests' head: the Westerkerk's
/// frame) to a file of its own in the tests' scratch folder, named for `name`; its path.
[[nodiscard]] inline std::string write_pack(TileBytes t, std::uint32_t features, std::string_view name) {
    const std::vector<std::byte> raw = t.encode();
    constexpr std::array<std::uint8_t, 1> levels = {14};
    const std::array<ChunkBytes, 1> chunks{
        ChunkBytes{.tile = {14, 8414, 5384}, .raw = raw, .count = features, .reach = t.reach()}};
    const std::vector<std::byte> bytes = pack_bytes(chunks, levels);
    std::error_code ignored;
    std::filesystem::create_directories(ATLAS_TEST_SCRATCH, ignored);
    const std::filesystem::path path =
        std::filesystem::path(ATLAS_TEST_SCRATCH) / std::format("atlas-{}-{}.atlas", name, ::getpid());
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); // NOLINT
    return path.string();
}

} // namespace atlas::test::building
