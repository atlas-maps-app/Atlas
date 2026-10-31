// Stage P's tag tables as the world's profile reads them (load/Tags.hpp): mapcore/style/tags.json is the tables as
// `atlas_stagep tags` prints them, so the profile chooses a feature's kind and class as stage P does (ENGINE.md step
// 5).
#include "load/Tables.hpp"
#include "load/Tags.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cstddef>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>

namespace {

using namespace atlas;

TEST_CASE("mapcore/style/tags.json is stage P's tag tables") {
    std::ifstream in(ATLAS_STYLE_DIR "/tags.json", std::ios::binary);
    ATLAS_REQUIRE(in.good());
    const std::string file((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    // a difference: write the file again with `atlas_stagep tags > mapcore/style/tags.json`
    CHECK(file == load::tag_tables_json());
}

TEST_CASE("the tag tables hold each value once") {
    const auto once = [](const auto& table, auto key) {
        for (std::size_t i = 0; i < table.size(); ++i) {
            for (std::size_t j = i + 1; j < table.size(); ++j) {
                if (key(table[i]) == key(table[j])) return false;
            }
        }
        return true;
    };
    const auto self = [](std::string_view v) { return v; };
    CHECK(once(load::land_kinds, [](const load::LandKind& k) { return k.kind; }));
    CHECK(once(load::road_classes, [](const load::RoadClass& r) { return r.hw; }));
    CHECK(once(load::land_leisure, self));
    CHECK(once(load::big_roads, self));
    CHECK(once(load::rail_kinds, self));
    CHECK(once(load::aero_kinds, self));
    CHECK(once(load::water_ways, self));
    CHECK(once(load::tunnels, self));
    // the aeroways the regions take are the ones draw_over orders
    for (const std::string_view kind : load::aero_kinds) {
        CHECK(std::ranges::find(load::aero_order, kind, &load::AeroOrder::kind) != load::aero_order.end());
    }
}

} // namespace
