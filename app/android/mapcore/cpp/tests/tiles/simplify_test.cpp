// Simplification by keep codes (tiles/Simplify.hpp): simplify's rule and its two exceptions. Parity with the port's
// own simplify, ring by ring over the bundled scenes at every tolerance, is :mapcorecheck's L0 test.
#include "tests/base/require.hpp"
#include "tiles/Simplify.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cmath>
#include <vector>

namespace {

using namespace atlas::tiles;

/// The Westerkerk's frame, as the living map's rhead carries it.
const Frame frame{.lat0 = 52.3746, .lon0 = 4.884, .kx = 67954.56338551514, .kz = 110540.0};

struct TestRing {
    std::vector<std::int32_t> lat;
    std::vector<std::int32_t> lon;
    std::vector<std::uint8_t> keep;

    [[nodiscard]] RingView view(bool has_keep = true) const {
        return {.lat = lat, .lon = lon, .keep = keep, .has_keep = has_keep};
    }
};

/// A ring of `n` points on a circle of `radius` grid units (about 1.1 cm each), around the Westerkerk.
TestRing circle(std::size_t n, std::int32_t radius, std::vector<std::uint8_t> codes) {
    TestRing r;
    for (std::size_t i = 0; i < n; ++i) {
        // integer points on a square path, enough to give the ring an area without trigonometry
        const auto side = static_cast<std::int32_t>(i * 4 / n);
        const auto along = static_cast<std::int32_t>((i * 4 % n) * static_cast<std::size_t>(radius) * 2 / n);
        const std::array<std::pair<std::int32_t, std::int32_t>, 4> corner = {
            {{-radius, -radius}, {-radius, radius}, {radius, radius}, {radius, -radius}}};
        const std::array<std::pair<std::int32_t, std::int32_t>, 4> step = {{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}};
        const auto [a, o] = corner[static_cast<std::size_t>(side)];
        const auto [da, dout] = step[static_cast<std::size_t>(side)];
        r.lat.push_back(523'746'000 + a + (da * along));
        r.lon.push_back(48'840'000 + o + (dout * along));
    }
    r.keep = std::move(codes);
    return r;
}

TEST_CASE("tiles: simplify keeps the vertices whose code reaches the level, in order") {
    const TestRing r = circle(8, 2000, {13, 1, 3, 13, 0, 2, 13, 5});
    std::vector<std::uint32_t> out(8);
    const Simplified s = simplify(r.view(), 2, frame, out);
    CHECK(!s.whole);
    ATLAS_REQUIRE(s.count == 6);
    CHECK(std::vector<std::uint32_t>(out.begin(), out.begin() + 6) == std::vector<std::uint32_t>{0, 2, 3, 5, 6, 7});
    CHECK(simplify(r.view(), 13, frame, out).count == 3);
    CHECK(simplify(r.view(), 0, frame, out).whole);      // tolerance 0: the port does not simplify
    CHECK(simplify(r.view(false), 5, frame, out).whole); // a ring without keep codes
}

TEST_CASE("tiles: simplify's exceptions: small rings stay whole, and so do results of under 3 points or 2 m²") {
    std::vector<std::uint32_t> out(8);
    const TestRing small = circle(5, 2000, {13, 0, 0, 0, 13});
    CHECK(simplify(small.view(), 3, frame, out).whole);
    CHECK(simplify(small.view(), 3, frame, out).count == 5);
    const TestRing two = circle(8, 2000, {13, 0, 0, 0, 13, 0, 0, 0});
    CHECK(simplify(two.view(), 1, frame, out).whole);
    // a ring of 400 grid units a side, about 4.4 m north to south and 2.7 m east to west: simplified to its corners
    const TestRing tiny = circle(8, 200, {13, 0, 13, 0, 13, 0, 13, 0});
    const Simplified kept = simplify(tiny.view(), 1, frame, out);
    CHECK(!kept.whole);
    CHECK(kept.count == 4);
    const std::vector<std::uint32_t> four = {0, 2, 4, 6};
    CHECK(std::abs(signed_area(tiny.view(), four, frame)) > 2.0);
    // the same shape at a tenth of the size: under 2 m² once simplified, so whole
    const TestRing speck = circle(8, 20, {13, 0, 13, 0, 13, 0, 13, 0});
    CHECK(std::abs(signed_area(speck.view(), four, frame)) <= 2.0);
    CHECK(simplify(speck.view(), 1, frame, out).whole);
}

TEST_CASE("tiles: the frame's metres are the port's: (lon/1e7 − lon0)·kx east, −(lat/1e7 − lat0)·kz south") {
    CHECK(frame.x(48'840'000) == 0.0);
    CHECK(frame.z(523'746'000) == 0.0);
    CHECK(frame.x(48'840'001) == (48'840'001 / 1e7 - 4.884) * 67954.56338551514);
    CHECK(frame.z(523'746'001) == -(523'746'001 / 1e7 - 52.3746) * 110540.0);
    CHECK(frame.z(523'746'001) < 0.0); // north is negative z
}

} // namespace
