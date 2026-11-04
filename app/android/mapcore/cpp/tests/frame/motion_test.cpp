// The window's frame rate as the map moves (frame/Motion.hpp, ENGINE.md gain 11): 120 from the map's second new camera
// since the loop last rested, 60 a second after it rests with the map moved, a move within the second calling the drop
// off, and one camera alone, a tile or a fade neither raising the rate nor holding it longer.
#include "frame/Motion.hpp"

#include <doctest/doctest.h>

#include <cstdint>

using namespace atlas;

namespace {

constexpr std::int64_t ms = 1'000'000;
constexpr std::int64_t s = 1'000 * ms;

} // namespace

TEST_CASE("motion: 120 from the second new camera since the loop rested, 60 a second after it rests") {
    frame::Motion m;
    CHECK_FALSE(m.moving());
    // a tile or a fade drawn at rest, and one camera alone: nothing moves, and no drop is to come
    CHECK_FALSE(m.drew(false));
    CHECK_FALSE(m.drew(true));
    CHECK_FALSE(m.drew(false));
    CHECK_FALSE(m.moving());
    CHECK_FALSE(m.rested(s));
    CHECK(m.drop_ns() == 0);
    CHECK_FALSE(m.drop());
    // a pan: its second camera moves the map, and the cameras after it say nothing more
    CHECK_FALSE(m.drew(true));
    CHECK(m.drew(true));
    CHECK(m.moving());
    CHECK_FALSE(m.drew(true));
    CHECK_FALSE(m.drew(false));
    CHECK(m.drop_ns() == 0);
    // the loop rests at 2 s: 60 at 3 s, once
    CHECK(m.rested(2 * s));
    CHECK(m.drop_ns() == (2 * s) + frame::Motion::hold_ns);
    CHECK(m.moving());
    CHECK(m.drop());
    CHECK_FALSE(m.moving());
    CHECK(m.drop_ns() == 0);
    CHECK_FALSE(m.drop());
    // the next pan's first camera comes as the second is up: 60, then 120 again from its second
    CHECK_FALSE(m.drew(true));
    CHECK(m.drew(true));
    CHECK(m.moving());
    CHECK(m.rested(5 * s));
    CHECK_FALSE(m.drew(true));
    CHECK(m.drop());
    CHECK(m.drew(true));
    CHECK(m.moving());
}

TEST_CASE("motion: pan, lift and pan again within the second keeps 120; one camera within it holds nothing longer") {
    frame::Motion m;
    CHECK_FALSE(m.drew(true));
    CHECK(m.drew(true));
    CHECK(m.rested(2 * s));
    // the map moves again half a second on: the drop is off, the window already told 120
    CHECK_FALSE(m.drew(true));
    CHECK(m.drew(true));
    CHECK(m.drop_ns() == 0);
    CHECK(m.moving());
    CHECK_FALSE(m.drop()); // a timer that ran all the same finds nothing to do
    CHECK(m.moving());
    // the next rest holds a second from itself
    CHECK(m.rested((3 * s) + (200 * ms)));
    CHECK(m.drop_ns() == (4 * s) + (200 * ms));
    // a location fix within the second (one camera) and a tile landing: the drop stays where it was
    CHECK_FALSE(m.drew(true));
    CHECK_FALSE(m.rested((3 * s) + (600 * ms)));
    CHECK_FALSE(m.drew(false));
    CHECK_FALSE(m.rested((3 * s) + (900 * ms)));
    CHECK(m.drop_ns() == (4 * s) + (200 * ms));
    CHECK(m.drop());
    CHECK_FALSE(m.moving());
}
