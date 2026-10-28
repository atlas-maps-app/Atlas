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

/// The same rectangle walked clockwise, as the loader leaves every building's ring (load/Amsterdam.cpp's clean: a
/// positive area in x east, z south): the renders' gable normals (an edge's right hand) then face out of it.
[[nodiscard]] inline Ring rect_cw(std::int32_t dx, std::int32_t dy, std::int32_t w, std::int32_t d) {
    return {.lat = {s0 + dy, s0 + dy + d, s0 + dy + d, s0 + dy},
            .lon = {w0 + dx, w0 + dx, w0 + dx + w, w0 + dx + w},
            .keep = {}};
}

/// The tile (14/8414/5384): a flat house, gabled, hipped, pyramidal, skillion (SW) and mansard roofs, a houseboat near
/// the quay, and a raised part standing in the flat house (its outline); and two trees, so the trees' samples begin
/// after the buildings'.
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
        {"parent_id", tl::Codec::nvarz, col::nvarz({100})},
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

/// A tile of the material faces (render/amsterdam.py's material_face): a glass office with a shop floor (its glazing
/// under its glass), a metal shed, a stone home of 7 storeys (its balconies) and a parking garage of 4 decks; and a
/// canal house of 1680, gabled, walked clockwise, so the hoist beam of the gable turned to the eye stands out of it.
[[nodiscard]] inline TileBytes materials_tile() {
    namespace tl = atlas::tiles;
    namespace col = atlas::test::columns;
    TileBytes t;
    const std::vector<Ring> rings{
        rect(0, 0, 1470, 1800),          // 0: a glass office, 10 × 20 m
        rect(3000, 0, 2940, 1800),       // 1: a metal shed east of it, 20 × 20 m
        rect(-3500, 0, 2205, 1080),      // 2: a stone home west of it, 15 × 12 m
        rect(-7500, 0, 2940, 1800),      // 3: a parking garage farther west, 20 × 20 m
        rect_cw(1650, -2000, 882, 1260), // 4: a canal house of 1680 south of the office, 6 × 14 m, gabled
    };
    LayerBytes b;
    b.id = static_cast<std::uint8_t>(tl::LayerId::building);
    b.cells = {0, 0, 0, 0, 0};
    b.columns = {
        {"index", tl::Codec::delta, col::delta({0, 1, 2, 3, 4})},
        {"id", tl::Codec::delta, col::delta({400, 401, 402, 403, 404})},
        {"ring", tl::Codec::geom, col::geom(rings)},
        {"h", tl::Codec::centi, col::centi({30.0, 9.0, 21.0, 12.0, 16.0})},
        {"min", tl::Codec::centi, col::centi({0.0, 0.0, 0.0, 0.0, 0.0})},
        {"roof_h", tl::Codec::centi, col::centi({std::nullopt, std::nullopt, std::nullopt, std::nullopt, 5.0})},
        {"year", tl::Codec::nvarz, col::nvarz({std::nullopt, std::nullopt, std::nullopt, std::nullopt, 1680})},
        {"levels", tl::Codec::nvarz, col::nvarz({8, 2, 7, 4, 4})},
        {"roof", tl::Codec::str, col::str(t, {std::nullopt, std::nullopt, std::nullopt, std::nullopt, "gabled"})},
        {"wall_rgb", tl::Codec::varu, col::varu({0x9AA6AE, 0x8A9096, 0xD8CCB2, 0xA49C90, 0x7A4A3A})},
        {"roof_rgb", tl::Codec::varu, col::varu({0x56585C, 0x737E88, 0x8C969E, 0x56585C, 0x4E4E52})},
        {"mat_draw", tl::Codec::str, col::str(t, {"glass", "metal", "stone", "concrete", "brick"})},
        {"kind", tl::Codec::str, col::str(t, {"office", "industrial", "home", "parking", "home"})},
        {"no_win", tl::Codec::boolean, col::boolean({true, false, false, false, false})},
        {"flags", tl::Codec::varu, col::varu({tl::building::flag_shop_floor, 0, 0, 0, 0})},
        {"tag:building", tl::Codec::str, col::str(t, {"office", "industrial", "apartments", "parking", "house"})},
    };
    t.layers = {b};
    return t;
}

/// The material faces' tile's features, with an old canal house's gables and their hoist beams.
inline constexpr std::uint32_t materials_features = 5;

/// A tile of a tradition's kit (render/amsterdam.py's trad_roof and trad_wall, in a pack whose tradition is
/// kit_pack()'s): a gabled house in the kit with its hoist beams and a roof of tiles, a hipped one in it with slates,
/// and a gabled house of 1700 outside it (the plain roof and its old gables' beams); a brick terrace house in the kit
/// with its stucco ground floor and its cornice, gabled, between the first two; and a wooden house in it with its
/// timber boards, hipped, between the hipped one and the old house; each walked clockwise as the loader leaves rings.
/// Every wall of a kit's gabled or hipped roof takes the eaves' shadow where it runs along the ridge or under a hip.
[[nodiscard]] inline TileBytes kit_tile() {
    namespace tl = atlas::tiles;
    namespace col = atlas::test::columns;
    TileBytes t;
    const std::vector<Ring> rings{
        rect_cw(0, 0, 1176, 1260),     // 0: gabled in the kit, 8 × 14 m
        rect_cw(-3500, 0, 1470, 1080), // 1: hipped in the kit, 10 × 12 m, west of it
        rect_cw(-7000, 0, 1176, 1260), // 2: gabled, outside the kit, of 1700, farther west
        rect_cw(-1900, 0, 1323, 1260), // 3: gabled in the kit, its band and cornice, 9 × 14 m, between 1 and 0
        rect_cw(-5600, 0, 1764, 900),  // 4: hipped in the kit, of timber boards, 12 × 10 m, between 2 and 1
    };
    LayerBytes b;
    b.id = static_cast<std::uint8_t>(tl::LayerId::building);
    b.cells = {0, 0, 0, 0, 0};
    const std::uint64_t kit = tl::building::flag_kit;
    b.columns = {
        {"index", tl::Codec::delta, col::delta({0, 1, 2, 3, 4})},
        {"id", tl::Codec::delta, col::delta({300, 301, 302, 303, 304})},
        {"ring", tl::Codec::geom, col::geom(rings)},
        {"h", tl::Codec::centi, col::centi({15.0, 14.0, 15.0, 13.0, 9.0})},
        {"min", tl::Codec::centi, col::centi({0.0, 0.0, 0.0, 0.0, 0.0})},
        {"roof_h", tl::Codec::centi, col::centi({5.0, 4.0, 5.0, 4.0, 3.0})},
        {"year", tl::Codec::nvarz, col::nvarz({std::nullopt, std::nullopt, 1700, 1860, std::nullopt})},
        {"levels", tl::Codec::nvarz, col::nvarz({3, 3, 3, 3, 2})},
        {"roof", tl::Codec::str, col::str(t, {"gabled", "hipped", "gabled", "gabled", "hipped"})},
        {"wall_rgb", tl::Codec::varu, col::varu({0x9C5B43, 0xC9B79A, 0x8E5A46, 0x8A4B36, 0xB8432F})},
        {"roof_rgb", tl::Codec::varu, col::varu({0x7E4A3A, 0x5A5E66, 0x9E5038, 0x5C6068, 0x4A4E54})},
        {"mat_draw", tl::Codec::str, col::str(t, {"brick", "plaster", "brick", "brick", "wood"})},
        {"rmat_draw", tl::Codec::str, col::str(t, {"roof_tiles", "slate", "roof_tiles", "slate", "roof_tiles"})},
        {"kind", tl::Codec::str, col::str(t, {"home", "home", "home", "home", "home"})},
        {"flags", tl::Codec::varu,
         col::varu({kit | tl::building::flag_kit_hoist, kit, 0,
                    kit | tl::building::flag_kit_band | tl::building::flag_kit_cornice,
                    kit | tl::building::flag_kit_boards | tl::building::flag_wall_drawn})},
        {"tag:building", tl::Codec::str, col::str(t, {"house", "house", "house", "terrace", "house"})},
    };
    t.layers = {b};
    return t;
}

/// The kit tile's features, and its tradition: its eaves, its stucco ground floor's height and colour, its timber
/// boards' spacing and its corner boards' colour (gb_terrace's and no_timber's).
inline constexpr std::uint32_t kit_features = 5;
inline constexpr double kit_eaves = 0.45;
inline constexpr double kit_band_h = 3.3;
inline constexpr std::uint32_t kit_band_rgb = 0xECE6DA;
inline constexpr double kit_spacing = 0.2;
inline constexpr std::uint32_t kit_corner_rgb = 0xF1EEE6;

/// A pack's options for the kit tile: its style's `tradition`, with the kit's eaves, band and boards.
[[nodiscard]] inline PackOptions kit_pack() {
    namespace v = atlas::test::values;
    PackOptions o;
    o.style_tables = {{"tradition", v::map({{"eaves", v::real(kit_eaves)},
                                            {"base_band", v::map({{"materials", v::list({v::text("brick")})},
                                                                  {"h", v::real(kit_band_h)},
                                                                  {"colour", v::text("#ECE6DA")}})},
                                            {"boards", v::map({{"materials", v::list({v::text("wood")})},
                                                               {"spacing", v::real(kit_spacing)},
                                                               {"corner", v::text("#F1EEE6")}})}})}};
    return o;
}

/// Writes tile `t` (its `features`) as a render pack of 14/8414/5384 (the tile reader's tests' head: the Westerkerk's
/// frame) to a file of its own in the tests' scratch folder, named for `name`; its path.
[[nodiscard]] inline std::string write_pack(TileBytes t, std::uint32_t features, std::string_view name,
                                            const PackOptions& options = {}) {
    const std::vector<std::byte> raw = t.encode();
    constexpr std::array<std::uint8_t, 1> levels = {14};
    const std::array<ChunkBytes, 1> chunks{
        ChunkBytes{.tile = {14, 8414, 5384}, .raw = raw, .count = features, .reach = t.reach()}};
    const std::vector<std::byte> bytes = pack_bytes(chunks, levels, atlas::tiles::format_render, options);
    std::error_code ignored;
    std::filesystem::create_directories(ATLAS_TEST_SCRATCH, ignored);
    const std::filesystem::path path =
        std::filesystem::path(ATLAS_TEST_SCRATCH) / std::format("atlas-{}-{}.atlas", name, ::getpid());
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); // NOLINT
    return path.string();
}

} // namespace atlas::test::building
