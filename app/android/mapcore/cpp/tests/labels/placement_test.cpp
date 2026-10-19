// The board's labels placed (plan-vector D13, K13): LabelPainter's placement held to mapview's own (the generated
// frames' hash that mapview's LabelPlacementTest pins); the collision index's grid against every box; a label kept
// while moving, faded over 250 ms where it changes, the rest frame's own choice; the sprites' set and where a sprite
// goes; and the time a frame's placement takes (gated with ATLAS_TIMED=1, printed otherwise).
#include "labels/Placement.hpp"
#include "labels/Sprites.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <format>
#include <limits>
#include <vector>

namespace {

using namespace atlas::labels;

/// SplitMix64, and floats in [0, 1) from its top 24 bits, exact: mapview's LabelPlacementTest's generator.
class Mix {
public:
    explicit Mix(std::uint64_t seed) noexcept : state_(seed) {}
    std::uint64_t next() noexcept {
        state_ += 0x9E3779B97F4A7C15ULL;
        std::uint64_t z = state_;
        z = (z ^ (z >> 30U)) * 0xBF58476D1CE4E5B9ULL;
        z = (z ^ (z >> 27U)) * 0x94D049BB133111EBULL;
        return z ^ (z >> 31U);
    }
    std::uint32_t below(std::uint32_t n) noexcept { return static_cast<std::uint32_t>(next() % n); }
    float unit() noexcept { return static_cast<float>(next() >> 40U) / 16777216.0F; }

private:
    std::uint64_t state_;
};

/// FNV-1a, 64 bits, over little-endian ints.
struct Fnv {
    std::uint64_t hash = 0xCBF29CE484222325ULL;
    void put(std::uint32_t v) noexcept {
        for (unsigned k = 0; k < 4; ++k) {
            hash ^= (v >> (8U * k)) & 0xFFU;
            hash *= 0x100000001B3ULL;
        }
    }
};

constexpr float no_point = std::numeric_limits<float>::quiet_NaN();

/// One generated frame, as LabelPlacementTest makes it.
struct Generated {
    float width = 0.0F;
    float height = 0.0F;
    float pad = 0.0F;
    std::vector<Box> keep_out;
    std::vector<Box> markers;
    std::vector<Candidate> candidates;
    std::vector<std::vector<Point>> points;
};

Generated generate(Mix& mix) {
    Generated g;
    g.width = static_cast<float>(600 + mix.below(900));
    g.height = static_cast<float>(900 + mix.below(1500));
    constexpr std::array densities{2.0F, 2.625F, 3.0F, 3.5F};
    const float density = densities[mix.below(4)];
    g.pad = 4.0F * density;
    const std::uint32_t keep_outs = mix.below(5);
    for (std::uint32_t i = 0; i < keep_outs; ++i) {
        const float l = mix.unit() * g.width;
        const float t = mix.unit() * g.height;
        const float r = l + (20.0F + (mix.unit() * 300.0F));
        const float b = t + (20.0F + (mix.unit() * 120.0F));
        g.keep_out.push_back({.l = l, .t = t, .r = r, .b = b});
    }
    const std::uint32_t markers = mix.below(8);
    for (std::uint32_t i = 0; i < markers; ++i) {
        const float l = mix.unit() * g.width;
        const float t = mix.unit() * g.height;
        g.markers.push_back({.l = l, .t = t, .r = l + (30.0F * density), .b = t + (30.0F * density)});
    }
    const std::uint32_t n = 10 + mix.below(50);
    g.points.resize(n);
    for (std::uint32_t i = 0; i < n; ++i) {
        Candidate c{.id = i, .label = i};
        c.priority = static_cast<std::int32_t>(mix.below(6));
        c.measures.half = (10.0F + (mix.unit() * 300.0F)) + g.pad;
        c.measures.pad = g.pad;
        c.measures.text_height = 12.0F + (mix.unit() * 60.0F);
        const std::uint32_t count = 1 + mix.below(6);
        for (std::uint32_t k = 0; k < count; ++k) {
            if (mix.below(10) == 0) {
                g.points[i].push_back({.x = no_point, .y = no_point});
            } else {
                const float x = -60.0F + (mix.unit() * (g.width + 120.0F));
                const float y = -60.0F + (mix.unit() * (g.height + 120.0F));
                g.points[i].push_back({.x = x, .y = y});
            }
        }
        g.candidates.push_back(c);
    }
    for (std::uint32_t i = 0; i < n; ++i) g.candidates[i].points = g.points[i];
    return g;
}

Measures measures(float half, float text_height, float pad = 10.5F) {
    return {.half = half + pad, .pad = pad, .text_height = text_height, .baseline = -7.0F};
}

} // namespace

TEST_CASE("LabelPainter's placement: mapview's 400 generated frames hash to the value LabelPlacementTest pins") {
    Mix mix(0x4B3133);
    Fnv fnv;
    CollisionIndex index;
    std::vector<std::uint32_t> order;
    std::vector<Placed> placed;
    std::array<std::size_t, spot_count> by_spot{};
    std::size_t labels = 0;
    std::size_t drawn = 0;
    for (std::uint32_t frame = 0; frame < 400; ++frame) {
        const Generated g = generate(mix);
        const Frame f{.width = g.width, .height = g.height, .keep_out = g.keep_out, .markers = g.markers};
        priority_order(g.candidates, order);
        index.reset(g.width, g.height);
        placed.clear();
        place(g.candidates, order, f, index, placed);
        for (const Placed& p : placed) {
            fnv.put(frame);
            fnv.put(p.candidate);
            fnv.put(p.point);
            fnv.put(std::bit_cast<std::uint32_t>(p.stand.x));
            fnv.put(std::bit_cast<std::uint32_t>(p.stand.y));
            ++by_spot.at(p.spot);
        }
        labels += g.candidates.size();
        drawn += placed.size();
    }
    MESSAGE(std::format("{} labels over 400 frames, {} placed (by spot: {}, {}, {}, {}, {}, {}), hash {:016x}", labels,
                        drawn, by_spot[0], by_spot[1], by_spot[2], by_spot[3], by_spot[4], by_spot[5], fnv.hash));
    CHECK(labels == 14145);
    CHECK(drawn == 8568);
    CHECK(by_spot == std::array<std::size_t, spot_count>{4664, 591, 1437, 1391, 249, 236});
    CHECK(fnv.hash == 0xdd1efd97b6fc8e79ULL);
}

TEST_CASE("the collision index finds what a test of every box finds") {
    Mix mix(17);
    CollisionIndex index;
    std::size_t hits = 0;
    for (int frame = 0; frame < 50; ++frame) {
        const float width = 1080.0F;
        const float height = 2400.0F;
        index.reset(width, height);
        std::vector<Box> all;
        for (int i = 0; i < 200; ++i) {
            // boxes over the frame and past its edges, some of no width
            const float l = -200.0F + (mix.unit() * (width + 400.0F));
            const float t = -200.0F + (mix.unit() * (height + 400.0F));
            const Box b{.l = l, .t = t, .r = l + (mix.unit() * 250.0F), .b = t + (mix.unit() * 90.0F)};
            const bool expect = overlaps_any(all, b);
            CHECK(index.hits(b) == expect);
            hits += expect ? 1U : 0U;
            if (!expect) {
                index.insert(b);
                all.push_back(b);
            }
        }
        CHECK(index.boxes().size() == all.size());
    }
    CHECK(hits > 1000);
    // touching boxes do not meet (open intervals); NaN meets nothing
    index.reset(100, 100);
    index.insert({.l = 10, .t = 10, .r = 20, .b = 20});
    CHECK(!index.hits({.l = 20, .t = 10, .r = 30, .b = 20}));
    CHECK(index.hits({.l = 19.5F, .t = 10, .r = 30, .b = 20}));
    CHECK(!index.hits({.l = no_point, .t = no_point, .r = no_point, .b = no_point}));
}

TEST_CASE("moving, a placed label keeps its place while it fits; a change fades over 250 ms; at rest LabelPainter's") {
    // Two labels on a frame of 400 x 300 px. The first by priority stands centred on its point; the second's first
    // point is the same, where every spot meets the first, so it stands at its second point.
    const std::array<Point, 1> p0{{{.x = 200, .y = 150}}};
    const std::array<Point, 3> p1{{{.x = 200, .y = 150}, {.x = 60, .y = 60}, {.x = 300, .y = 250}}};
    std::vector<Candidate> c{
        {.id = 7, .label = 0, .priority = 5, .measures = measures(40, 20), .points = p0},
        {.id = 9, .label = 1, .priority = 1, .measures = measures(30, 20), .points = p1},
    };
    const Frame frame{.width = 400, .height = 300};
    Placement placement;
    auto drawn = placement.update(c, frame, true, 0.0);
    ATLAS_REQUIRE(drawn.size() == 2);
    CHECK(drawn[0].candidate == 0);
    CHECK(drawn[0].stand.x == 200.0F);
    CHECK(drawn[0].alpha == 1.0F); // the first frame at once
    CHECK(drawn[1].candidate == 1);
    CHECK(drawn[1].stand.x == 60.0F);
    CHECK(drawn[1].stand.y == 60.0F);
    CHECK(drawn[1].baseline == 67.0F); // the centre's y less (ascent + descent) / 2
    CHECK(placement.placed() == 2);
    CHECK(placement.line().starts_with("label place "));

    // Moving with the map: both keep their place.
    const std::array<Point, 1> q0{{{.x = 190, .y = 150}}};
    const std::array<Point, 3> q1{{{.x = 190, .y = 150}, {.x = 50, .y = 60}, {.x = 290, .y = 250}}};
    c[0].points = q0;
    c[1].points = q1;
    drawn = placement.update(c, frame, false, 0.1);
    ATLAS_REQUIRE(drawn.size() == 2);
    CHECK(drawn[1].stand.x == 50.0F);
    CHECK(!placement.fading());

    // A marker comes over the second's place: it takes its third point, fading out where it stood and in there.
    const std::array<Box, 1> marker{{{.l = 0, .t = 0, .r = 120, .b = 120}}};
    const Frame covered{.width = 400, .height = 300, .markers = marker};
    drawn = placement.update(c, covered, false, 0.2);
    ATLAS_REQUIRE(drawn.size() == 3);
    const auto gone = std::ranges::find_if(drawn, [](const Drawn& d) { return d.candidate == 1 && d.leaving; });
    const auto come = std::ranges::find_if(drawn, [](const Drawn& d) { return d.candidate == 1 && !d.leaving; });
    ATLAS_REQUIRE(gone != drawn.end() && come != drawn.end());
    CHECK(gone->alpha == 1.0F);
    CHECK(gone->stand.x == 50.0F);
    CHECK(come->alpha == 0.0F);
    CHECK(come->stand.x == 290.0F);
    CHECK(placement.fading());
    drawn = placement.update(c, covered, false, 0.325);
    ATLAS_REQUIRE(drawn.size() == 3);
    const auto half_way = std::ranges::find_if(drawn, [](const Drawn& d) { return d.candidate == 1 && !d.leaving; });
    ATLAS_REQUIRE(half_way != drawn.end());
    CHECK(half_way->alpha == doctest::Approx(0.5));
    drawn = placement.update(c, covered, false, 0.5);
    ATLAS_REQUIRE(drawn.size() == 2);
    CHECK(!placement.fading());

    // The camera stops with the marker gone: at rest LabelPainter's own place, the second point again, faded in.
    drawn = placement.update(c, frame, true, 1.0);
    ATLAS_REQUIRE(drawn.size() == 3);
    const auto settled = std::ranges::find_if(drawn, [](const Drawn& d) { return d.candidate == 1 && !d.leaving; });
    ATLAS_REQUIRE(settled != drawn.end());
    CHECK(settled->stand.x == 50.0F);
    CHECK(settled->alpha == 0.0F);

    // Labels that no longer fit anywhere fade out where they stood.
    const std::array<Box, 1> wall{{{.l = -1000, .t = -1000, .r = 1000, .b = 1000}}};
    const Frame blocked{.width = 400, .height = 300, .keep_out = wall};
    drawn = placement.update(c, blocked, false, 2.0);
    CHECK(drawn.size() == 2);
    CHECK(std::ranges::all_of(drawn, [](const Drawn& d) { return d.leaving && d.alpha == 1.0F; }));
    drawn = placement.update(c, blocked, false, 2.3);
    CHECK(drawn.empty());
}

TEST_CASE("a set of labels keeps every sprite's texels once, and a sprite goes where its anchor lands rounded") {
    std::vector<LabelSprite> sprites(2);
    sprites[0] = {.id = 1,
                  .priority = 3,
                  .measures = measures(20, 10),
                  .points = {{52.37, 4.88}},
                  .width = 3,
                  .height = 2,
                  .pixels = {1, 2, 3, 4, 5, 6},
                  .anchor_x = 1,
                  .anchor_y = 1};
    sprites[1] = {.id = 2,
                  .priority = 1,
                  .measures = measures(20, 10),
                  .points = {{52.38, 4.89}},
                  .width = 2,
                  .height = 1,
                  .pixels = {7, 8},
                  .anchor_x = 0,
                  .anchor_y = 0};
    const auto set = LabelSet::make(sprites, 42);
    ATLAS_REQUIRE(set.has_value(), describe(set.error()));
    CHECK((*set)->revision() == 42);
    CHECK(std::ranges::equal((*set)->texels(), std::array<std::uint32_t, 8>{1, 2, 3, 4, 5, 6, 7, 8}));
    CHECK(std::ranges::equal((*set)->offsets(), std::array<std::uint32_t, 2>{0, 6}));
    // Kotlin's roundToInt: halves up, also below zero
    Drawn d{.stand = {.x = 10.5F, .y = 0.0F}, .baseline = -2.5F};
    CHECK(sprite_at((*set)->sprites()[0], d).x == 10);
    CHECK(sprite_at((*set)->sprites()[0], d).y == -3);
    d.stand.x = 10.4999F;
    CHECK(sprite_at((*set)->sprites()[0], d).x == 9);
    // refused: pixels that are not width x height, and no points
    std::vector<LabelSprite> bad(1, sprites[0]);
    bad[0].pixels.pop_back();
    CHECK(LabelSet::make(bad, 1).error() == SpriteError::size);
    bad[0] = sprites[0];
    bad[0].points.clear();
    CHECK(LabelSet::make(bad, 1).error() == SpriteError::points);
}

TEST_CASE("a frame's placement takes at most 0.5 ms on one core (gated with ATLAS_TIMED=1)") {
    // A busy board: 60 labels of up to four points, 8 keep-outs, 24 markers, panning 1,000 frames and a rest frame
    // every tenth.
    Mix mix(5);
    const float width = 1080.0F;
    const float height = 2400.0F;
    std::vector<Box> keep_out;
    for (int i = 0; i < 8; ++i) {
        const float l = mix.unit() * width;
        const float t = mix.unit() * height;
        keep_out.push_back({.l = l, .t = t, .r = l + 160.0F, .b = t + 90.0F});
    }
    std::vector<Box> markers;
    for (int i = 0; i < 24; ++i) {
        const float l = mix.unit() * width;
        const float t = mix.unit() * height;
        markers.push_back({.l = l, .t = t, .r = l + 79.0F, .b = t + 79.0F});
    }
    std::vector<std::vector<Point>> base(60);
    std::vector<Candidate> c(60);
    for (std::uint32_t i = 0; i < 60; ++i) {
        for (int k = 0; k < 4; ++k) base[i].push_back({.x = mix.unit() * width, .y = mix.unit() * height});
        c[i] = {.id = i,
                .label = i,
                .priority = static_cast<std::int32_t>(mix.below(10)),
                .measures = measures(40.0F + (mix.unit() * 160.0F), 50.0F)};
    }
    std::vector<std::vector<Point>> moved = base;
    Placement placement;
    std::vector<double> ms;
    std::size_t placed = 0;
    for (int f = 0; f < 1000; ++f) {
        const float dx = static_cast<float>(f) * 0.7F;
        const float dy = static_cast<float>(f) * -0.4F;
        for (std::size_t i = 0; i < c.size(); ++i) {
            for (std::size_t k = 0; k < base[i].size(); ++k)
                moved[i][k] = {.x = base[i][k].x + dx, .y = base[i][k].y + dy};
            c[i].points = moved[i];
        }
        const Frame frame{.width = width, .height = height, .keep_out = keep_out, .markers = markers};
        (void)placement.update(c, frame, f % 10 == 0, f / 60.0);
        ms.push_back(placement.millis());
        placed += placement.placed();
    }
    std::ranges::sort(ms);
    const double p50 = ms[ms.size() / 2];
    const double p99 = ms[(ms.size() * 99) / 100];
    MESSAGE(std::format("label place: 60 labels, {:.1f} placed a frame; p50 {:.4f} ms, p99 {:.4f} ms, worst {:.4f} ms",
                        static_cast<double>(placed) / 1000.0, p50, p99, ms.back()));
    if (atlas::test::environment("ATLAS_TIMED") == "1") CHECK(p99 <= 0.5);
}
