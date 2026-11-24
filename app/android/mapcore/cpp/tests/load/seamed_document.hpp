// A document made by hand whose buildings the cleaning gives seams (load/Clean.hpp), in the Westerkerk's window's
// neighbour 14/8414/5384, for the tests of the seams' marks (tests/load/marks_test.cpp; mapcorecheck's SeamMarksTest
// writes the same document) and of their drawing (tests/building/buildings_vk_test.cpp):
//   - a block of 60 m by 60 m round a courtyard of 20 m by 20 m, mapped as a relation: a seam to a courtyard;
//   - an outline of 90 m by 30 m with a part across its middle third: the part cuts it in two, a seam between pieces;
//   - an outline of 40 m by 30 m and, past its part, an annex of 2 m by 2 m: two pieces, the lesser one over the
//     cleaning's least piece (3 m²) and under the least face of level 12 (tiles/LeastFace.hpp).
// Each outline has corners a few decimetres off its walls' lines, which the keep ladder drops one by one.
#pragma once

#include "tiles/Grid.hpp"
#include "tiles/Schema.hpp"

#include <cstdint>
#include <format>
#include <initializer_list>
#include <string>
#include <string_view>

namespace atlas::test::seamed {

/// The document's origin, and a metre in degrees there.
inline constexpr double lat0 = 52.3680;
inline constexpr double lon0 = 4.8890;
inline constexpr double north = 1.0 / 111'320.0;
inline constexpr double east = 1.0 / 67'950.0;

inline constexpr std::int64_t block_id = 20; // a relation: 60 m by 60 m round a courtyard of 20 m by 20 m
inline constexpr std::int64_t cut_id = 30;   // an outline of 90 m by 30 m, its part across its middle third
inline constexpr std::int64_t annex_id = 40; // an outline of 40 m by 30 m and, past its part, 4 m² more

/// The window that holds it.
[[nodiscard]] inline tiles::TileKey window() noexcept { return tiles::tile_of(14, 523'680'000, 48'890'000); }

/// A corner, metres east and north of the document's origin.
struct M {
    double east;
    double north;
};

/// A closed way's geometry: its corners and its first again.
[[nodiscard]] inline std::string geometry(std::initializer_list<M> corners) {
    std::string out = "[";
    const auto put = [&](const M& c) {
        out += std::format(R"({{"lat": {:.7f}, "lon": {:.7f}}})", lat0 + (c.north * north), lon0 + (c.east * east));
    };
    for (const M& c : corners) {
        put(c);
        out += ", ";
    }
    put(*corners.begin());
    return out + "]";
}

[[nodiscard]] inline std::string way(std::int64_t id, std::string_view tags, std::initializer_list<M> corners) {
    return std::format(R"({{"type": "way", "id": {}, "tags": {{{}}}, "geometry": {}}})", id, tags, geometry(corners));
}

/// The document, as Overpass answers.
[[nodiscard]] inline std::string document() {
    const std::string block = std::format(
        R"({{"type": "relation", "id": {}, "tags": {{"type": "multipolygon", "building": "apartments", "height": "15"}},
 "members": [{{"type": "way", "ref": 21, "role": "outer", "geometry": {}}},
             {{"type": "way", "ref": 22, "role": "inner", "geometry": {}}}]}})",
        block_id, geometry({{0, 0}, {20, 0.2}, {40, -0.6}, {60, 0}, {60, 60}, {30, 62.5}, {0, 60}}),
        geometry({{20, 20}, {40, 20}, {40, 40}, {20, 40}}));
    return R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
)" + block +
           ",\n" +
           way(cut_id, R"("building": "yes", "height": "12")",
               {{100, 0}, {115, 0.25}, {190, 0}, {190, 30}, {175, 30.7}, {100, 30}}) +
           ",\n" + way(31, R"("building:part": "yes", "height": "20")", {{130, -2}, {160, -2}, {160, 32}, {130, 32}}) +
           ",\n" +
           way(annex_id, R"("building": "yes", "height": "9")",
               {{200, 0}, {220, 0.3}, {240, 0}, {240, 5}, {247, 5}, {247, 7}, {240, 7}, {240, 30}, {200, 30}}) +
           ",\n" + way(41, R"("building:part": "yes", "height": "14")", {{238, 3}, {245, 3}, {245, 9}, {238, 9}}) +
           "]}";
}

} // namespace atlas::test::seamed
