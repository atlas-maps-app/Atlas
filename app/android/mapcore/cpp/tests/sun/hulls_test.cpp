// Ground shadows (sun/Hulls.hpp): hull2 as the port's (render/city.py:80) on cases whose answer is known, its rounding
// to millimetres and the first of equal points kept, and a tile's shadows: a prism's hull, a raised part's moved top,
// a tree's disc, none with the sun down. MT/sun/SunParityTest.kt holds every shadow of the boards' scenes to the port's
// hull2 and the sun's check holds hull2 on every host.
#include "base/pymath/PyMath.hpp"
#include "sun/Hulls.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <cmath>
#include <vector>

namespace {

using namespace atlas::sun;

struct Hull {
    std::vector<double> x;
    std::vector<double> z;
};

Hull hull_of(const std::vector<double>& x, const std::vector<double>& z) {
    Hull h;
    hull2(x, z, h.x, h.z);
    return h;
}

} // namespace

TEST_CASE("sun: hull2 of a square and its offset copy, counter-clockwise from the lowest x") {
    // the square (0..10) and the same moved by (+4, +3): the hull drops the inner corners
    const Hull h = hull_of({0, 10, 10, 0, 4, 14, 14, 4}, {0, 0, 10, 10, 3, 3, 13, 13});
    CHECK(h.x == std::vector<double>{0, 10, 14, 14, 4, 0});
    CHECK(h.z == std::vector<double>{0, 0, 3, 13, 13, 10});
}

TEST_CASE("sun: hull2 rounds to millimetres, keeps the first of equal points and drops collinear ones") {
    // 0.0004 rounds to 0.0 (the first such point, -0.0, keeps its sign); the midpoint of a side is dropped
    const Hull h = hull_of({-0.0, 0.0004, 5, 10, 10, 0}, {0, 0, 0, 0, 10, 10});
    ATLAS_REQUIRE(h.x.size() == 4);
    CHECK(std::signbit(h.x[0]));
    CHECK(h.x == std::vector<double>{-0.0, 10, 10, 0});
    CHECK(h.z == std::vector<double>{0, 0, 10, 10});
    const Hull r = hull_of({1.23456}, {2.34567});
    CHECK(r.x == std::vector<double>{1.235});
    CHECK(r.z == std::vector<double>{2.346});
    // two distinct points come back sorted; a point repeated is one
    const Hull two = hull_of({5, 1, 5}, {5, 1, 5});
    CHECK(two.x == std::vector<double>{1, 5});
    CHECK(two.z == std::vector<double>{1, 5});
}

TEST_CASE("sun: a tile's ground shadows") {
    SunTile t;
    // a 10 m square standing on the ground, 20 m high; a part raised 5 m; a tree
    for (const double v : {0.0, 10.0, 10.0, 0.0}) t.solids.x.push_back(v);
    for (const double v : {0.0, 0.0, 10.0, 10.0}) t.solids.z.push_back(v);
    t.solids.first.push_back(4);
    t.solids.h.push_back(20.0);
    t.solids.min.push_back(0.0);
    t.solids.source.push_back(Source::building);
    t.solids.record.push_back(0);
    for (const double v : {20.0, 24.0, 24.0, 20.0}) t.solids.x.push_back(v);
    for (const double v : {0.0, 0.0, 4.0, 4.0}) t.solids.z.push_back(v);
    t.solids.first.push_back(8);
    t.solids.h.push_back(12.0);
    t.solids.min.push_back(5.0);
    t.solids.source.push_back(Source::part);
    t.solids.record.push_back(0);
    t.trees.x.push_back(50.0);
    t.trees.z.push_back(50.0);
    t.trees.h.push_back(10.0);
    t.trees.crown.push_back(6.0);
    t.trees.gy.push_back(0.0);
    const Region region;
    CHECK(shadows_of(t, Sun{.x = 0.0, .y = -1.0, .z = 0.0}, region).size() == 0);
    Region none;
    none.no_shadows = true;
    CHECK(shadows_of(t, Sun{.x = 0.6, .y = 0.8, .z = 0.0}, none).size() == 0);
    // the sun at (0.6, 0.8, 0): a shadow falls -0.75 m in x per metre of height
    const TileShadows s = shadows_of(t, Sun{.x = 0.6, .y = 0.8, .z = 0.0}, region);
    ATLAS_REQUIRE(s.size() == 3);
    CHECK(s.kind[0] == ShadowKind::prism);
    const double dx = (-0.6 / 0.8) * 20.0; // the hull of the square and its copy 15 m west: a rectangle
    CHECK(s.xs(0).size() == 4);
    CHECK(s.xs(0)[0] == atlas::pymath::round(dx, 3)); // hull2 rounds to millimetres
    CHECK(s.zs(0)[0] == 0.0);
    CHECK(s.kind[1] == ShadowKind::raised);
    CHECK(s.xs(1).size() == 4);
    CHECK(s.xs(1)[0] == 20.0 + ((-0.6 / 0.8) * 12.0));
    CHECK(s.kind[2] == ShadowKind::tree);
    CHECK(s.xs(2).size() == 16);
    CHECK(s.y[2] == 0.1);
    CHECK(s.xs(2)[0] == (50.0 + ((-0.6 / 0.8) * (10.0 * 0.7))) + 3.0);
}
