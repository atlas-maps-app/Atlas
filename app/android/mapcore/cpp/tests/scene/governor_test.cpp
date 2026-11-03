// The triangle governor (scene/Governor.hpp, ENGINE.md step 5): under its budget the bias stays 0 and every reach is
// the same metres; over it the bias rises by 0.1 level a frame up to 1.5, holds between 80 % and the budget, and comes
// back by 0.05 a frame under 80 %, never while the camera is still to a bias its plan was over the budget at (the
// still view settles without a level flickering), and freely once the camera moves; a device with heaps under 128 MB
// has two thirds of the budget.
#include "base/pymath/PyMath.hpp"
#include "scene/Governor.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

using namespace atlas;

TEST_CASE("the governor: under its budget the bias stays 0 and a reach the same metres") {
    scene::Governor g;
    for (int i = 0; i < 50; ++i) {
        g.update(19'999'999, 20'000'000, i % 2 == 0);
        CHECK(g.steps() == 0);
        CHECK_FALSE(g.changed());
    }
    CHECK(g.bias() == 0.0);
    CHECK(g.near(4700.0) == 4700.0);
    CHECK(g.near(12'000.0) == 12'000.0);
    CHECK(g.near(1.0) == 1.0);
    // no budget: no governor
    g.update(1'000'000'000, 0, false);
    CHECK(g.steps() == 0);
}

TEST_CASE("the governor: over its budget the bias rises by 0.1 level a frame up to 1.5, then comes back by 0.05") {
    scene::Governor g;
    std::vector<double> rise;
    for (int i = 0; i < 20; ++i) {
        g.update(40'000'000, 20'000'000, true);
        rise.push_back(g.bias());
    }
    for (std::size_t i = 0; i < 15; ++i) CHECK(rise.at(i) == doctest::Approx(0.1 * static_cast<double>(i + 1)));
    CHECK(rise.back() == doctest::Approx(1.5));
    CHECK(g.steps() == scene::governor_most);
    CHECK(g.near(4700.0) == doctest::Approx(4700.0 / (2.0 * pymath::sqrt(2.0))));
    CHECK(g.near(12'000.0) == doctest::Approx(12'000.0 / (2.0 * pymath::sqrt(2.0))));
    // between 80 % of the budget and the budget it holds
    g.update(17'000'000, 20'000'000, true);
    CHECK(g.steps() == scene::governor_most);
    CHECK_FALSE(g.changed());
    // under 80 % it comes back a step a frame, to 0 and no further
    for (int i = 1; i <= 40; ++i) {
        g.update(10'000'000, 20'000'000, true);
        CHECK(g.steps() == std::max(scene::governor_most - i, 0));
        CHECK(g.changed() == (i <= scene::governor_most));
    }
    CHECK(g.near(4700.0) == 4700.0);
}

TEST_CASE("the governor: a still view settles at the least bias under its budget, without a level flickering") {
    // a still view whose plan is over the budget below 0.3 level and well under it from there (a level's tiles
    // coming nearer at once): the bias rises past it, comes back as far as it may, and holds
    const auto plan = [](int steps) -> std::uint64_t { return steps < 6 ? 25'000'000 : 10'000'000; };
    scene::Governor g;
    std::vector<int> seen;
    for (int i = 0; i < 30; ++i) {
        g.update(plan(g.steps()), 20'000'000, i == 0);
        seen.push_back(g.steps());
    }
    // 0, 2, 4, 6 over; 5 (not yet seen) over again; 7, then 6, which holds
    CHECK(seen.at(0) == 2);
    CHECK(seen.at(1) == 4);
    CHECK(seen.at(2) == 6);
    CHECK(seen.at(3) == 5);
    CHECK(seen.at(4) == 7);
    CHECK(seen.at(5) == 6);
    for (std::size_t i = 6; i < seen.size(); ++i) CHECK(seen.at(i) == 6);
    CHECK_FALSE(g.changed());
    // once the camera moves, the view is another: the bias comes back past where the still view's plan was over
    g.update(10'000'000, 20'000'000, true);
    CHECK(g.steps() == 5);
    g.update(10'000'000, 20'000'000, true);
    CHECK(g.steps() == 4);
}

TEST_CASE("the governor: the budget by the device's heaps") {
    CHECK(scene::triangle_budget(std::uint64_t{256} << 20U) == scene::governor_budget);
    CHECK(scene::triangle_budget(std::uint64_t{128} << 20U) == scene::governor_budget);
    CHECK(scene::triangle_budget(std::uint64_t{64} << 20U) == scene::governor_budget / 3 * 2);
}
