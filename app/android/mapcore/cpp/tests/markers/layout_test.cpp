// The markers placed as mapview's MarkerLayout places them (markers/Layout.hpp): mapview's MarkerLayoutTest, case for
// case (apart they all show; the higher priority wins and is drawn last; the chosen one wins whatever its priority;
// dots fold into a cluster of their counts and a pin hides what it meets; hidden ones take no room; the boxes follow
// the styles), and the counts' keys as MarkerPainter.countLabel writes them, a cluster's bubble growing with its count,
// and a pin's name on the side MarkerPainter writes it with the box the labels keep clear of.
#include "markers/Layout.hpp"
#include "markers/Markers.hpp"

#include <doctest/doctest.h>

#include <array>
#include <vector>

using namespace atlas::markers;
namespace labels = atlas::labels;

namespace {

/// MarkerLayoutTest's metrics, view and chip text (40 px).
const Metrics metrics{.density = 2.0F};
constexpr float view_width = 824.0F;
constexpr float view_height = 1830.0F;

Marker marker(Style style = Style::pin, std::int32_t priority = 0, bool selected = false, std::uint32_t count = 1) {
    return {.priority = priority, .style = style, .selected = selected, .count = count, .chip_text = 40.0F};
}

void run(Layout& layout, const std::vector<Marker>& markers, const std::vector<float>& xs, const std::vector<float>& ys,
         std::vector<std::uint8_t> visible = {}) {
    if (visible.empty()) visible.assign(markers.size(), 1);
    layout.place(markers, xs, ys, visible, metrics, view_width, view_height);
}

std::vector<std::uint32_t> placed(const Layout& layout) { return {layout.placed().begin(), layout.placed().end()}; }

void check_box(const labels::Box& b, std::array<float, 4> want) {
    CHECK(b.l == doctest::Approx(want[0]).epsilon(1e-6));
    CHECK(b.t == doctest::Approx(want[1]).epsilon(1e-6));
    CHECK(b.r == doctest::Approx(want[2]).epsilon(1e-6));
    CHECK(b.b == doctest::Approx(want[3]).epsilon(1e-6));
}

} // namespace

TEST_CASE("markers: apart they all show") {
    Layout layout;
    run(layout, {marker(), marker(), marker()}, {100.0F, 400.0F, 700.0F}, {300.0F, 300.0F, 300.0F});
    CHECK(layout.placed().size() == 3);
}

TEST_CASE("markers: the higher priority wins and is drawn last") {
    Layout layout;
    run(layout, {marker(Style::pin, 1), marker(Style::pin, 5), marker(Style::pin, 3)}, {400.0F, 410.0F, 700.0F},
        {500.0F, 505.0F, 500.0F});
    // draw order: the lowest first, so the highest ends on top
    CHECK(placed(layout) == std::vector<std::uint32_t>{2, 1});
}

TEST_CASE("markers: the chosen one wins whatever its priority") {
    Layout layout;
    run(layout, {marker(Style::pin, 100), marker(Style::pin, -5, true)}, {400.0F, 405.0F}, {500.0F, 500.0F});
    CHECK(placed(layout) == std::vector<std::uint32_t>{1});
}

TEST_CASE("markers: dots fold into a cluster with their count") {
    Layout layout;
    run(layout,
        {marker(Style::dot, 3), marker(Style::dot), marker(Style::cluster, 0, false, 4), marker(Style::pin, 2),
         marker(Style::dot)},
        {400.0F, 405.0F, 410.0F, 700.0F, 702.0F}, {500.0F, 500.0F, 502.0F, 900.0F, 880.0F});
    REQUIRE(layout.placed().size() == 2);
    for (std::size_t j = 0; j < 2; ++j) {
        // "a" won and took "b" (1) and the cluster "c" (4): a bubble of six; "d" is a pin: it hides "e" and stays one
        if (layout.placed()[j] == 0) CHECK(layout.clusters()[j] == 6);
        if (layout.placed()[j] == 3) CHECK(layout.clusters()[j] == 0);
    }
}

TEST_CASE("markers: hidden ones take no room") {
    Layout layout;
    run(layout, {marker(Style::pin, 9), marker()}, {400.0F, 402.0F}, {500.0F, 500.0F}, {0, 1});
    CHECK(placed(layout) == std::vector<std::uint32_t>{1});
}

TEST_CASE("markers: the boxes follow the styles") {
    // a pin sits on its place, as the Main board's: a 30 px disc centred on it
    check_box(metrics.box(marker(), 100.0F, 200.0F), {70.0F, 170.0F, 130.0F, 230.0F});
    // the chosen pin is the board's reservation pin: a 44 px disc on a 10 px stem over a 4 px shadow
    check_box(metrics.box(marker(Style::pin, 0, true), 100.0F, 200.0F),
              {56.0F, 200.0F - 4.0F - 20.0F - 88.0F, 144.0F, 204.0F});
    check_box(metrics.box(marker(Style::dot), 100.0F, 200.0F), {80.0F, 180.0F, 120.0F, 220.0F});
    // the charging point's 32 px pill, centred on its place
    Marker chip = marker(Style::chip);
    chip.chip_text = 30.0F;
    const labels::Box c = metrics.box(chip, 100.0F, 200.0F);
    CHECK(c.r - c.l == doctest::Approx(metrics.chip_width(false, 30.0F)).epsilon(1e-6));
    CHECK(c.b == 232.0F);
    // the value chip hangs below the place
    Marker value = marker();
    value.value = true;
    value.chip_text = 30.0F;
    CHECK(metrics.box(value, 100.0F, 200.0F).b > 200.0F);
}

TEST_CASE("markers: a cluster's count as MarkerPainter writes it, and its bubble growing with it") {
    // countLabel: "7", "1.2k", "15k", "9.9k" (never rounded up past what is there), "1k", "999k"
    CHECK(count_key(7) == 7);
    CHECK(count_key(1234) == 1200);
    CHECK(count_key(15'400) == 15'000);
    CHECK(count_key(9'999) == 9'900);
    CHECK(count_key(1'000) == 1'000);
    CHECK(count_key(5'000'000) == 999'000);
    // 28 dp, 6 more for each tenfold, at most 46
    CHECK(metrics.cluster_diameter(1) == 56.0F);
    CHECK(metrics.cluster_diameter(10) == doctest::Approx(68.0F).epsilon(1e-6));
    CHECK(metrics.cluster_diameter(1'000) == doctest::Approx(92.0F).epsilon(1e-6));
    CHECK(metrics.cluster_diameter(100'000) == 92.0F);
}

TEST_CASE("markers: a pin's name on the side MarkerPainter writes it, and its box") {
    Marker pin = marker();
    pin.name = 100.0F;
    // right of the pin where it fits on the view; left of it near the view's right edge
    CHECK(!metrics.name_left(pin, 400.0F, view_width));
    CHECK(metrics.name_left(pin, 700.0F, view_width));
    // and right again where the left would run off the view as well
    CHECK(!metrics.name_left(pin, 100.0F, 150.0F));
    // the box: from 2 dp before the name to 2 dp after it, 0.7 of its 13 dp size either side of the pin's centre
    const labels::Box b = metrics.name_box(pin, 400.0F, 500.0F, view_width);
    CHECK(b.l == 400.0F + 30.0F + 12.0F - 4.0F);
    CHECK(b.r == 400.0F + 30.0F + 12.0F + 100.0F + 4.0F);
    CHECK(b.t == doctest::Approx(500.0F - (26.0F * 0.7F)).epsilon(1e-6));
    CHECK(b.b == doctest::Approx(500.0F + (26.0F * 0.7F)).epsilon(1e-6));
    // the chosen pin's name beside its disc, up on its stem
    pin.selected = true;
    const labels::Box s = metrics.name_box(pin, 400.0F, 500.0F, view_width);
    CHECK((s.t + s.b) / 2.0F == doctest::Approx(500.0F - 4.0F - 20.0F - 44.0F).epsilon(1e-6));
}
