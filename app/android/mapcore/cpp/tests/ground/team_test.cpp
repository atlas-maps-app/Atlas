// The team the ground decides its tiles on (passes/Team.hpp): each item of a run taken once, by the caller or a
// helper, and every one done when the run returns; run after run, so that a helper late for one takes nothing of the
// next; a team of none, and a run of one item, the caller's alone.
#include "passes/Team.hpp"

#include <doctest/doctest.h>

#include <cstddef>
#include <cstdint>
#include <vector>

namespace atlas::test {

namespace {

/// Some work for an item, more for some than for others, so that the helpers take items beside the caller.
[[nodiscard]] std::uint64_t work(std::size_t item) {
    std::uint64_t h = 0xCBF2'9CE4'8422'2325ULL;
    for (std::size_t k = 0; k < 2000 * ((item % 7) + 1); ++k) {
        h ^= k;
        h *= 0x100'0000'01B3ULL;
    }
    return h;
}

} // namespace

TEST_CASE("team: each item of a run once, every one done when it returns, run after run") {
    passes::Team team(3);
    CHECK(team.takers() == 4);
    std::vector<unsigned> takers_seen(team.takers(), 0);
    for (std::size_t round = 0; round < 300; ++round) {
        const std::size_t n = (round % 40) + 1; // a run of one item to forty
        std::vector<int> done(n, 0);
        std::vector<std::uint64_t> results(n, 0);
        std::vector<unsigned> by(n, team.takers());
        const double helped = team.run(n, [&](std::size_t i, unsigned taker) {
            ++done[i];
            results[i] = work(i);
            by[i] = taker;
        });
        CHECK(helped >= 0.0);
        for (std::size_t i = 0; i < n; ++i) {
            REQUIRE(done[i] == 1);
            REQUIRE(results[i] == work(i));
            REQUIRE(by[i] < team.takers());
            ++takers_seen[by[i]];
        }
    }
    CHECK(takers_seen[0] > 0); // the caller takes items too
}

TEST_CASE("team: runs of two items back to back, fewer than its helpers") {
    passes::Team team(3);
    for (std::size_t round = 0; round < 2000; ++round) {
        std::vector<int> done(2, 0);
        (void)team.run(2, [&](std::size_t i, unsigned) { ++done[i]; });
        REQUIRE(done[0] == 1);
        REQUIRE(done[1] == 1);
    }
}

TEST_CASE("team: without helpers, or for one item, the caller takes every item in order") {
    passes::Team alone(0);
    CHECK(alone.takers() == 1);
    std::vector<std::size_t> order;
    CHECK(alone.run(5, [&](std::size_t i, unsigned taker) {
        CHECK(taker == 0);
        order.push_back(i);
    }) == 0.0);
    CHECK(order == std::vector<std::size_t>{0, 1, 2, 3, 4});
    passes::Team team(2);
    unsigned by = 99;
    CHECK(team.run(1, [&](std::size_t, unsigned taker) { by = taker; }) == 0.0);
    CHECK(by == 0);
    (void)team.run(0, [&](std::size_t, unsigned) { FAIL("no item to take"); });
}

TEST_CASE("team: made and let go without a run") {
    for (int i = 0; i < 20; ++i) {
        const passes::Team team(3);
        CHECK(team.takers() == 4);
    }
}

} // namespace atlas::test
