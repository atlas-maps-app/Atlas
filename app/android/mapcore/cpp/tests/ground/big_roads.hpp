// A motorway's lanes and width tags, each with the lanes and width the ground draws it with (geometry/BigRoad.hpp):
// the one table the ground's test draws (tests/ground/aero_test.cpp) and the packer's pads a road's box by
// (tests/load/big_pad_test.cpp), so that what stage T draws and what stage P files a road by cannot part.
#pragma once

#include <cstdint>
#include <format>
#include <optional>
#include <string>
#include <vector>

namespace atlas::test {

/// A motorway's tags and how it is drawn.
struct BigRoadCase {
    std::string what;
    std::optional<std::string> lanes;
    std::optional<std::string> width;
    std::int64_t drawn_lanes = 2; ///< unmapped: two lanes
    double drawn_width = 10.0;    ///< and 2 × 3.5 + 3 m
};

/// Untagged first; then a lanes or a width past any road's, or that cannot be read, each drawn as untagged; then values
/// in range drawn as mapped, at the bounds (64 lanes, 250 m) and within them.
[[nodiscard]] inline std::vector<BigRoadCase> big_road_cases() {
    const std::string past_doubles(400, '9'); // past the doubles: an infinity, which to_int cannot take
    return {
        {.what = "untagged"},
        {.what = "lanes past the doubles", .lanes = past_doubles},
        {.what = "lanes 100000", .lanes = "100000"}, // 99,999 lane lines, 350 km wide
        {.what = "lanes -1", .lanes = "-1"},
        {.what = "width 5e8", .width = "500000000"}, // a 500,000 km road
        {.what = "width -12", .width = "-12"},
        {.what = "width NaN", .width = "NaN"},
        {.what = "width inf", .width = "inf"},
        {.what = "width past the doubles", .width = past_doubles},
        {.what = "lanes 64", .lanes = "64", .drawn_lanes = 64, .drawn_width = (64 * 3.5) + 3}, // the most drawn
        {.what = "width 250", .width = "250", .drawn_width = 250},                             // the widest
        {.what = "lanes 21", .lanes = "21", .drawn_lanes = 21, .drawn_width = (21 * 3.5) + 3}, // Germany's most
        {.what = "lanes 0", .lanes = "0", .drawn_lanes = 0, .drawn_width = 3},
        {.what = "lanes 3;2", .lanes = "3;2", .drawn_lanes = 3, .drawn_width = (3 * 3.5) + 3}, // a list's first number
    };
}

/// A case's tags as an Overpass element's after its highway: `, "lanes": "…", "width": "…"`.
[[nodiscard]] inline std::string overpass_tags(const BigRoadCase& c) {
    std::string out;
    if (c.lanes) out += std::format(R"(, "lanes": "{}")", *c.lanes);
    if (c.width) out += std::format(R"(, "width": "{}")", *c.width);
    return out;
}

} // namespace atlas::test
