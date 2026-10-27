// A church's own windows (geometry/ChurchWindows.hpp): render/closeup.py's bays, arches, glass and leading.
#include "base/pymath/PyMath.hpp"
#include "geometry/ChurchWindows.hpp"

#include <doctest/doctest.h>

using namespace atlas;
using geometry::V2;
using geometry::V3;

TEST_CASE("church windows: a bay of about six metres each, round-arched, the glass and the leading set into the wall") {
    // a wall 20 m long facing north (out of the building toward -z), 15 m tall
    const auto w = geometry::church_windows(V2(0.0, 0.0), V2(20.0, 0.0), 0.0, 15.0, V2(0.0, -1.0));
    // int(20 / 6.2) = 3 bays of 6.67 m; half a window 1.1 m (a fifth of a bay, at most 1.1); the sill at 3.2 m (22 % of
    // the wall, at most 3.2), the arch's spring at 15 - 2.1 (14 %, at most 2.4) - 1.1
    REQUIRE(w.size() == 3);
    const double uc = 0.5 * (20.0 / 3.0);
    const geometry::ChurchWindow& first = w.front();
    CHECK(first.reveal[0] == V3(uc - 1.1, 3.2, 0.0));
    CHECK(first.reveal[1] == V3(uc + 1.1, 3.2, 0.0));
    CHECK(first.reveal[2] == V3(uc + 1.1, 11.8, 0.0));       // the half circle from the right
    CHECK(first.reveal[10].x == doctest::Approx(uc - 1.1));  // to the left
    CHECK(first.reveal[6].y == doctest::Approx(11.8 + 1.1)); // its top
    // the stone 0.22 m wider all round, below the sill too; the glass 0.38 m into the building (+z here)
    CHECK(first.stone[0] == V3(uc - 1.32, 3.2 - 0.22, 0.0));
    CHECK(first.stone[6].y == doctest::Approx(11.8 + 1.32));
    CHECK(first.glass[0] == V3(uc - 1.1, 3.2, 0.38));
    // the leading 0.37 m in: the mullion from the sill to the arch's top, then a transom every 1.5 m below the spring
    REQUIRE(first.lead.size() == 6);
    CHECK(first.lead[0][0] == V3(uc, 3.2, 0.37));
    CHECK(first.lead[0][1] == V3(uc, 11.8 + 1.1, 0.37));
    for (std::size_t k = 1; k < first.lead.size(); ++k) {
        CHECK(first.lead[k][0].y == doctest::Approx(3.2 + (1.5 * static_cast<double>(k))));
        CHECK(first.lead[k][0].x == doctest::Approx(uc - 1.1));
        CHECK(first.lead[k][1].x == doctest::Approx(uc + 1.1));
    }
    CHECK(w.back().reveal[0].x == doctest::Approx((2.5 * (20.0 / 3.0)) - 1.1));
    // none on a wall under 6 m tall or 3 m long, nor where the sill and the spring stand under 2 m apart
    CHECK(geometry::church_windows(V2(0.0, 0.0), V2(20.0, 0.0), 0.0, 5.9, V2(0.0, -1.0)).empty());
    CHECK(geometry::church_windows(V2(0.0, 0.0), V2(2.9, 0.0), 0.0, 15.0, V2(0.0, -1.0)).empty());
    // a short wall still takes one bay, its window as wide as a fifth of it
    const auto one = geometry::church_windows(V2(0.0, 0.0), V2(4.0, 0.0), 0.0, 12.0, V2(0.0, -1.0));
    REQUIRE(one.size() == 1);
    CHECK(one.front().reveal[1].x - one.front().reveal[0].x == doctest::Approx(2 * 0.8));
}
