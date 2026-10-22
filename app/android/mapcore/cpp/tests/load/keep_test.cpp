// Keep codes (load/Keep.hpp): simplifying a ring at any level of the ladder from its codes gives what regions.py's
// simplify (load/Regions.hpp, held to the port) gives at that level's tolerance, vertex for vertex, over rings of every
// kind: noisy, with straight runs, with repeated points (a chord of length 0) and the canal ring's footprints.
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "load/Keep.hpp"
#include "load/Regions.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tiles/Schema.hpp"

#include <doctest/doctest.h>

#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <numbers>
#include <string>
#include <vector>

namespace {

namespace load = atlas::load;
namespace tiles = atlas::tiles;
using atlas::geometry::V2;

/// The ring simplify gives at ladder `level`, from the codes alone (with simplify's exceptions, as the tile reader
/// applies them).
std::vector<V2> by_codes(const std::vector<V2>& pts, const std::vector<std::uint8_t>& codes, std::size_t level) {
    if (level == 0 || pts.size() < tiles::keep::min_points) return pts;
    std::vector<V2> ring;
    for (std::size_t i = 0; i < pts.size(); ++i) {
        if (codes[i] >= level) ring.push_back(pts[i]);
    }
    return ring.size() >= 3 && std::fabs(atlas::geometry::signed_area(ring)) > 2 ? ring : pts;
}

/// How many of a ring's levels differ between its codes and simplify (0: all alike).
int differing(const std::vector<V2>& pts) {
    const std::vector<std::uint8_t> codes = load::keep_codes(pts);
    int wrong = 0;
    for (std::size_t level = 1; level < tiles::keep::tolerances.size(); ++level) {
        const std::vector<V2> want = load::simplify(pts, tiles::keep::tolerances[level]);
        const std::vector<V2> got = by_codes(pts, codes, level);
        bool same = want.size() == got.size();
        for (std::size_t i = 0; same && i < want.size(); ++i) same = want[i].x == got[i].x && want[i].z == got[i].z;
        if (!same) ++wrong;
    }
    return wrong;
}

} // namespace

TEST_CASE("keep: codes simplify a ring as simplify does, at every level of the ladder") {
    atlas::pymath::PyRandom rng(20261022);
    const auto unit = [&] { return rng.random(); };
    int rings = 0;
    int wrong = 0;
    for (int r = 0; r < 4000; ++r) {
        const auto n = static_cast<std::size_t>(6 + (unit() * 80));
        const double radius = 2 + (unit() * 200);
        const double noise = unit() * unit() * radius * 0.3;
        std::vector<V2> ring;
        for (std::size_t i = 0; i < n; ++i) {
            const double a = 2 * std::numbers::pi * static_cast<double>(i) / static_cast<double>(n);
            double x = (radius * atlas::pymath::cos(a)) + ((unit() - 0.5) * noise);
            double z = (radius * atlas::pymath::sin(a)) + ((unit() - 0.5) * noise);
            if (r % 7 == 0 && i % 3 == 1) { // a straight run: on the chord of its neighbours
                x = std::round(x);
                z = std::round(z);
            }
            ring.emplace_back(x, z);
            if (r % 11 == 0 && i % 5 == 2) ring.emplace_back(x, z); // a repeated point: a chord of length 0
        }
        ++rings;
        wrong += differing(ring);
    }
    CHECK(wrong == 0);
    MESSAGE(rings, " random rings, every level alike");
    // the anchors survive everything; a small ring is kept whole
    const std::vector<V2> square{{0, 0}, {10, 0}, {10, 10}, {0, 10}, {0, 5}, {0.01, 2}};
    const auto codes = load::keep_codes(square);
    CHECK(codes[0] == tiles::keep::all);
    CHECK(codes[2] == tiles::keep::all); // the farthest from the first
    CHECK(load::keep_codes(std::vector<V2>{{0, 0}, {1, 0}, {1, 1}}) == std::vector<std::uint8_t>(3, tiles::keep::all));
    CHECK(load::keep_codes(std::vector<V2>{}).empty());
}

TEST_CASE("keep: the canal ring's footprints' codes simplify them as simplify does (ATLAS_REGIONS_DIR)") {
    const char* dir = std::getenv("ATLAS_REGIONS_DIR"); // NOLINT(concurrency-mt-unsafe): read once, before any thread
    if (dir == nullptr) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: the canal ring's footprints are not read");
        return;
    }
    std::ifstream in(std::filesystem::path(dir) / "grachten" / "raw.json", std::ios::binary);
    ATLAS_REQUIRE(in.good());
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto doc = atlas::osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(doc.has_value());
    const load::RegionConfig region{.name = "grachten",
                                    .frame = {.lat0 = 52.372, .lon0 = 4.891, .kx = 0x1.097c73fb3b404p+16, .kz = 110540},
                                    .bbox = {52.36, 4.872, 52.393, 4.922},
                                    .tradition = nullptr,
                                    .palette = load::amsterdam_palette()};
    const auto scene = load::load_region(*doc, region);
    ATLAS_REQUIRE(scene.has_value());
    int wrong = 0;
    std::size_t rings = 0;
    for (const auto* list : {&scene->buildings, &scene->parts}) {
        for (const load::Building& b : *list) {
            wrong += differing(b.poly);
            ++rings;
        }
    }
    for (const load::Area& a : scene->areas) {
        wrong += differing(a.poly);
        ++rings;
    }
    for (const load::Land& a : scene->land) {
        wrong += differing(a.poly);
        ++rings;
    }
    CHECK(wrong == 0);
    MESSAGE(rings, " of the canal ring's rings, every level alike");
}
