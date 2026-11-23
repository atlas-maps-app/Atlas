// A park's planted woods (load/Woods.hpp's plant_woods) about a road through the wood whose width no road has (1e20 m,
// past 250 m, below 0, not a number): planted as about a road without a width (woods.py's `r.w or 2.0`), never an end
// of the process, while a width within 0 to 250 m clears its own breadth.
#include "geometry/BigRoad.hpp"
#include "load/Scene.hpp"
#include "load/Woods.hpp"

#include <doctest/doctest.h>

#include <cstddef>
#include <limits>
#include <vector>

namespace {

namespace load = atlas::load;

/// A wood 200 m square about the origin with a road east to west across it, `w` metres wide (0: no width), and the
/// trees planted within 150 m of the origin.
[[nodiscard]] std::vector<load::WoodTree> planted(double w) {
    load::Scene s;
    s.land.push_back({.poly = {{-100, -100}, {100, -100}, {100, 100}, {-100, 100}}, .kind = "wood"});
    s.roads.push_back({.pts = {{-120, 0}, {120, 0}}, .w = w, .hw = "residential"});
    return load::plant_woods(s, 0.0, 0.0, 150.0);
}

[[nodiscard]] bool same(const std::vector<load::WoodTree>& a, const std::vector<load::WoodTree>& b) {
    if (a.size() != b.size()) return false;
    for (std::size_t i = 0; i < a.size(); ++i) {
        if (a[i].x != b[i].x || a[i].z != b[i].z || a[i].h != b[i].h || a[i].id != b[i].id) return false;
    }
    return true;
}

} // namespace

TEST_CASE("load: a road's width no road has clears a wood's trees as a road without one") {
    const std::vector<load::WoodTree> unmapped = planted(0.0);
    REQUIRE(unmapped.size() > 300);
    constexpr double inf = std::numeric_limits<double>::infinity();
    for (const double w :
         {1e20, atlas::geometry::widest_road + 0.5, -12.0, inf, -inf, std::numeric_limits<double>::quiet_NaN()}) {
        INFO("a width of " << w);
        CHECK(same(planted(w), unmapped));
    }
    // within 0 to 250 m, its own: wider clears more
    const std::vector<load::WoodTree> ten = planted(10.0);
    const std::vector<load::WoodTree> widest = planted(atlas::geometry::widest_road);
    CHECK(ten.size() < unmapped.size());
    CHECK(widest.size() < ten.size());
}
