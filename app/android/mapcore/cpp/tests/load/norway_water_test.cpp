// A window of Norway's coast the core refuses (the countries builder's gates, 5: notes/countries/norway-water.md): its
// sea, closed from the coastline, is kept at tolerance 0 and dropped at another (or the other way round), so the cut's
// variants refuse the window ("at <t> m the water differs from tolerance 0's", load/Cut.cpp's variants_of) and 59 of
// Norway's windows are not built. Red until the fix: it stays on branch norwaycut until then.
//   - tests/load/data/norway-14-8492-4734.json: OpenStreetMap's data (© OpenStreetMap contributors, ODbL; README.md
//     beside it): the window's answer, unzipped from the packs' run that left it out.
#include "load/Window.hpp"
#include "tests/base/require.hpp"
#include "tiles/Chunks.hpp"

#include <doctest/doctest.h>

#include <filesystem>
#include <fstream>
#include <iterator>
#include <span>
#include <string>

#ifndef ATLAS_TESTS_DIR
#error "the window's data needs ATLAS_TESTS_DIR, the tests' folder (CMakeLists.txt)"
#endif

TEST_CASE("load: a window on Norway's coast builds, its sea the same at every tolerance of the live map's bands "
          "(14/8492/4734, by Sørfjorden)") {
    const std::filesystem::path file =
        std::filesystem::path(ATLAS_TESTS_DIR) / "load" / "data" / "norway-14-8492-4734.json";
    std::ifstream in(file, std::ios::binary);
    ATLAS_REQUIRE(in.good(), file.string());
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const atlas::tiles::TileKey tile(14, 8492, 4734);
    const auto built = atlas::load::build_window(std::as_bytes(std::span(text.data(), text.size())), tile,
                                                 atlas::load::place_of(tile));
    ATLAS_REQUIRE(built.has_value(), built.error().what);
}
