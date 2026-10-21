// The board's labels placed (plan-vector D13, K13; ENGINE.md design point 8): LabelPainter's placement held to
// mapview's own (the generated frames' hash that mapview's LabelPlacementTest pins); the collision index's grid against
// every box; a label placed once and kept where it is on the map, at rest and moving, growing and shrinking with it,
// fading out where it meets one kept before it and back once clear, the set placed anew beyond its band (the floor
// raising its low end) and a label the frame brings in placed around the kept ones; the sprites' set and where a
// sprite goes, at its own size and scaled; and the time a frame's placement takes (gated with ATLAS_TIMED=1, printed
// otherwise).
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

/// The entry of candidate `c` among `drawn` that is leaving (or not), or null.
const Drawn* entry(std::span<const Drawn> drawn, std::uint32_t c, bool leaving = false) {
    const auto it =
        std::ranges::find_if(drawn, [&](const Drawn& d) { return d.candidate == c && d.leaving == leaving; });
    return it != drawn.end() ? &*it : nullptr;
}

TEST_CASE("a placed label stays where it is on the map, growing and shrinking with it, fading where it meets another") {
    // Two labels on a frame of 400 x 300 px at a distance of 400 m. The first by priority stands centred on its point;
    // the second's first point is the same, where every spot meets the first, so it stands at its second point.
    std::array<Point, 1> p0{{{.x = 200, .y = 150}}};
    std::array<Point, 3> p1{{{.x = 200, .y = 150}, {.x = 60, .y = 60}, {.x = 300, .y = 250}}};
    std::vector<Candidate> c{
        {.id = 7, .label = 0, .priority = 5, .measures = measures(40, 20), .points = p0},
        {.id = 9, .label = 1, .priority = 1, .measures = measures(30, 20), .points = p1},
    };
    const Frame frame{.width = 400, .height = 300};
    const double at = 1.0 / 400.0;
    Placement placement;
    auto drawn = placement.update(c, frame, at, 0.0);
    ATLAS_REQUIRE(drawn.size() == 2);
    CHECK(drawn[0].candidate == 0);
    CHECK(drawn[0].stand.x == 200.0F);
    CHECK(drawn[0].alpha == 1.0F); // the set's first frame at once
    CHECK(drawn[0].scale == 1.0F);
    CHECK(drawn[1].candidate == 1);
    CHECK(drawn[1].stand.x == 60.0F);
    CHECK(drawn[1].stand.y == 60.0F);
    CHECK(drawn[1].baseline == 67.0F); // the centre's y less (ascent + descent) / 2
    CHECK(placement.placed() == 2);
    CHECK(placement.line().starts_with("label place "));

    // Moving with the map, and at rest again: both keep their points and spots (no re-placing).
    p0 = {{{.x = 190, .y = 150}}};
    p1 = {{{.x = 190, .y = 150}, {.x = 50, .y = 60}, {.x = 290, .y = 250}}};
    drawn = placement.update(c, frame, at, 0.1);
    ATLAS_REQUIRE(drawn.size() == 2);
    CHECK(drawn[1].stand.x == 50.0F);
    CHECK(!placement.fading());

    // Zoomed in by a quarter about the frame's centre: the points spread, the labels grow by a quarter where they are.
    const auto zoom = [](Point p, float k) {
        return Point{.x = 200 + ((p.x - 200) * k), .y = 150 + ((p.y - 150) * k)};
    };
    p0 = {{zoom({.x = 190, .y = 150}, 1.25F)}};
    p1 = {{zoom({.x = 190, .y = 150}, 1.25F), zoom({.x = 50, .y = 60}, 1.25F), zoom({.x = 290, .y = 250}, 1.25F)}};
    drawn = placement.update(c, frame, at * 1.25, 0.2);
    ATLAS_REQUIRE(drawn.size() == 2);
    CHECK(placement.size() == 1.25F);
    CHECK(drawn[1].scale == 1.25F);
    CHECK(drawn[1].stand.x == p1[1].x); // its second point still, centred on it
    CHECK(drawn[1].stand.y == p1[1].y);
    CHECK(drawn[1].stand.box.r - drawn[1].stand.box.l == 2.0F * 1.25F * (30.0F + 10.5F));
    CHECK(drawn[1].baseline == p1[1].y + (7.0F * 1.25F));
    CHECK(!placement.fading());

    // A marker comes over the second's place: it fades out where it stands, taking no other point...
    const std::array<Box, 1> marker{{{.l = 0, .t = 0, .r = 120, .b = 120}}};
    const Frame covered{.width = 400, .height = 300, .markers = marker};
    drawn = placement.update(c, covered, at * 1.25, 0.3);
    const Drawn* fading = entry(drawn, 1);
    ATLAS_REQUIRE(fading != nullptr);
    CHECK(fading->stand.x == p1[1].x);
    CHECK(fading->alpha == 1.0F);
    CHECK(placement.placed() == 1);
    drawn = placement.update(c, covered, at * 1.25, 0.425);
    fading = entry(drawn, 1);
    ATLAS_REQUIRE(fading != nullptr);
    CHECK(fading->alpha == doctest::Approx(0.5));
    CHECK(placement.fading());
    drawn = placement.update(c, covered, at * 1.25, 0.6);
    CHECK(entry(drawn, 1) == nullptr);
    CHECK(drawn.size() == 1);
    // ... and back in where it was once the marker goes, from where its fade had reached (turned half way)
    drawn = placement.update(c, covered, at * 1.25, 0.7);
    CHECK(entry(drawn, 1) == nullptr);
    drawn = placement.update(c, frame, at * 1.25, 0.8);
    const Drawn* back = entry(drawn, 1);
    ATLAS_REQUIRE(back != nullptr);
    CHECK(back->stand.x == p1[1].x);
    CHECK(back->alpha == 0.0F);
    drawn = placement.update(c, frame, at * 1.25, 1.2);
    back = entry(drawn, 1);
    ATLAS_REQUIRE(back != nullptr);
    CHECK(back->alpha == 1.0F);

    // A label the frame brings in is placed around the kept ones, at the set's size, and fades in.
    std::array<Point, 2> p2{{zoom({.x = 190, .y = 150}, 1.25F), {.x = 330, .y = 60}}};
    c.push_back({.id = 11, .label = 2, .priority = 9, .measures = measures(20, 20), .points = p2});
    drawn = placement.update(c, frame, at * 1.25, 1.3);
    const Drawn* come = entry(drawn, 2);
    ATLAS_REQUIRE(come != nullptr);
    CHECK(come->stand.x == 330.0F); // its first point meets the first label, kept though of a lower priority
    CHECK(come->alpha == 0.0F);
    CHECK(come->scale == 1.25F);
    CHECK(entry(drawn, 0) != nullptr);
    CHECK(entry(drawn, 0)->alpha == 1.0F);

    // Past the band (1.5 times the set's size): the set is placed anew at its own size, every label fading out where
    // it stood (at the size it had) as its new place fades in.
    drawn = placement.update(c, frame, at * 1.6, 2.0);
    CHECK(placement.size() == 1.0F);
    CHECK(placement.reference() == at * 1.6);
    const Drawn* old = entry(drawn, 0, true);
    ATLAS_REQUIRE(old != nullptr);
    CHECK(old->scale == doctest::Approx(1.6));
    CHECK(old->alpha == 1.0F);
    // placed anew from scratch, by priority: the newcomer, the highest, now takes its first point, and fades in there
    const Drawn* anew = entry(drawn, 2);
    ATLAS_REQUIRE(anew != nullptr);
    CHECK(anew->stand.x == p2[0].x);
    CHECK(anew->scale == 1.0F);
    CHECK(anew->alpha == 0.0F);
    CHECK(entry(drawn, 2, true) != nullptr); // its place in the band before, fading out
    CHECK(placement.fading());

    // The readable floor raises the band's low end: from that size, shrinking to 0.85 of it is beyond the band.
    drawn = placement.update(c, frame, at * 1.6, 3.0);
    const Frame floored{.width = 400, .height = 300, .least = 0.9F};
    drawn = placement.update(c, floored, at * 1.6 * 0.85, 3.1);
    CHECK(placement.size() == 1.0F);
    CHECK(placement.reference() == at * 1.6 * 0.85);
    // without it, the same is within the band
    Placement unfloored;
    (void)unfloored.update(c, frame, at, 0.0);
    (void)unfloored.update(c, frame, at * 0.85, 0.1);
    CHECK(unfloored.size() == 0.85F);
}

TEST_CASE("placed labels that the map's turn brings together: the one of lower priority fades out, neither moves") {
    // Two labels side by side, then the map turns a quarter about the frame's centre: their points come one above the
    // other, close enough for their boxes to meet.
    std::array<Point, 1> a{{{.x = 100, .y = 150}}};
    std::array<Point, 1> b{{{.x = 300, .y = 150}}};
    std::vector<Candidate> c{
        {.id = 1, .label = 0, .priority = 2, .measures = measures(60, 20), .points = a},
        {.id = 2, .label = 1, .priority = 1, .measures = measures(60, 20), .points = b},
    };
    const Frame frame{.width = 400, .height = 300};
    Placement placement;
    auto drawn = placement.update(c, frame, 0.01, 0.0);
    ATLAS_REQUIRE(drawn.size() == 2);
    a = {{{.x = 200, .y = 130}}};
    b = {{{.x = 200, .y = 160}}};
    drawn = placement.update(c, frame, 0.01, 0.1);
    const Drawn* higher = entry(drawn, 0);
    const Drawn* lower = entry(drawn, 1);
    ATLAS_REQUIRE(higher != nullptr);
    ATLAS_REQUIRE(lower != nullptr);
    CHECK(higher->stand.x == 200.0F);
    CHECK(higher->stand.y == 130.0F);
    CHECK(higher->alpha == 1.0F);
    CHECK(lower->stand.y == 160.0F); // where it is, fading out
    CHECK(lower->alpha == 1.0F);
    CHECK(placement.placed() == 1);
    drawn = placement.update(c, frame, 0.01, 0.5);
    CHECK(entry(drawn, 1) == nullptr);
    // turned back: clear again (by more than the margin), it fades back in at its own place
    a = {{{.x = 100, .y = 150}}};
    b = {{{.x = 300, .y = 150}}};
    drawn = placement.update(c, frame, 0.01, 0.6);
    lower = entry(drawn, 1);
    ATLAS_REQUIRE(lower != nullptr);
    CHECK(lower->stand.x == 300.0F);
    CHECK(lower->alpha == 0.0F);
}

TEST_CASE("a set of labels keeps every image's texels once, and a sprite goes where its anchor lands rounded") {
    std::vector<LabelSprite> sprites(2);
    sprites[0] = {
        .id = 1,
        .priority = 3,
        .measures = measures(20, 10),
        .points = {{52.37, 4.88}},
        .own = {.width = 3, .height = 2, .pixels = {1, 2, 3, 4, 5, 6}, .anchor_x = 1, .anchor_y = 1},
        .large = {
            .width = 4, .height = 3, .pixels = {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9}, .anchor_x = 2, .anchor_y = 2}};
    sprites[1] = {.id = 2,
                  .priority = 1,
                  .measures = measures(20, 10),
                  .points = {{52.38, 4.89}},
                  .own = {.width = 2, .height = 1, .pixels = {7, 8}, .anchor_x = 0, .anchor_y = 0}};
    const auto set = LabelSet::make(sprites, 42, 0.0F, 1.5F);
    ATLAS_REQUIRE(set.has_value(), describe(set.error()));
    CHECK((*set)->revision() == 42);
    CHECK((*set)->large_scale() == 1.5F);
    CHECK((*set)->texels().size() == 20);
    CHECK(std::ranges::equal((*set)->texels().first(6), std::array<std::uint32_t, 6>{1, 2, 3, 4, 5, 6}));
    CHECK(std::ranges::equal((*set)->texels().last(2), std::array<std::uint32_t, 2>{7, 8}));
    CHECK(std::ranges::equal((*set)->offsets(), std::array<std::array<std::uint32_t, 2>, 2>{{{0, 6}, {18, 0}}}));
    const LabelSprite& first = (*set)->sprites()[0];
    // Kotlin's roundToInt: halves up, also below zero
    Drawn d{.stand = {.x = 10.5F, .y = 0.0F}, .baseline = -2.5F};
    CHECK(sprite_at(first.own, d).x == 10.0F);
    CHECK(sprite_at(first.own, d).y == -3.0F);
    CHECK(sprite_at(first.own, d).width == 3.0F);
    CHECK(sprite_at(first.own, d).scale == 1.0F);
    d.stand.x = 10.4999F;
    CHECK(sprite_at(first.own, d).x == 9.0F);
    CHECK(image_at(first, 1.5F, d).image == 0);
    // scaled: about its anchor, where it falls
    d.scale = 1.5F;
    const SpriteAt big = sprite_at(first.own, d);
    CHECK(big.x == 10.4999F - 1.5F);
    CHECK(big.y == -2.5F - 1.5F);
    CHECK(big.width == 4.5F);
    CHECK(big.height == 3.0F);
    CHECK(big.scale == 1.5F);
    // grown, from its large image, a texel a pixel at the band's top
    const ImageAt grown = image_at(first, 1.5F, d);
    CHECK(grown.image == 1);
    CHECK(grown.at.x == 10.4999F - 2.0F);
    CHECK(grown.at.y == -2.5F - 2.0F);
    CHECK(grown.at.width == 4.0F);
    CHECK(grown.at.scale == 1.0F);
    d.scale = 1.2F;
    CHECK(image_at(first, 1.5F, d).at.scale == doctest::Approx(0.8F));
    // shrunk, or without a large image: from its own image
    d.scale = 0.8F;
    CHECK(image_at(first, 1.5F, d).image == 0);
    CHECK(image_at(first, 1.5F, d).at.scale == 0.8F);
    d.scale = 1.2F;
    CHECK(image_at((*set)->sprites()[1], 1.5F, d).image == 0);
    // refused: pixels that are not width x height, no image, no points, a large image without its scale, a scale
    // below 1
    std::vector<LabelSprite> bad(1, sprites[0]);
    bad[0].own.pixels.pop_back();
    CHECK(LabelSet::make(bad, 1).error() == SpriteError::size);
    bad[0] = sprites[0];
    bad[0].large.pixels.pop_back();
    CHECK(LabelSet::make(bad, 1, 0.0F, 1.5F).error() == SpriteError::size);
    bad[0] = sprites[0];
    bad[0].own = {};
    CHECK(LabelSet::make(bad, 1, 0.0F, 1.5F).error() == SpriteError::size);
    bad[0] = sprites[0];
    bad[0].points.clear();
    CHECK(LabelSet::make(bad, 1, 0.0F, 1.5F).error() == SpriteError::points);
    bad[0] = sprites[0];
    CHECK(LabelSet::make(bad, 1).error() == SpriteError::scale);
    CHECK(LabelSet::make(bad, 1, 0.0F, 0.5F).error() == SpriteError::scale);
}

TEST_CASE("a set's large images are kept by priority within its budget; the others grow from their own image") {
    // Three labels whose own images take three quarters of their budget, each large image a quarter of the whole
    // budget: two of them fit, the two of highest priority keep theirs.
    const auto image = [](std::uint32_t width, std::uint32_t height) {
        return SpriteImage{
            .width = width, .height = height, .pixels = std::vector<std::uint32_t>(std::size_t{width} * height, 1U)};
    };
    const auto three = [&](std::uint32_t own_height) {
        std::vector<LabelSprite> sprites(3);
        for (std::uint32_t i = 0; i < 3; ++i) {
            sprites[i] = {.id = i + 1,
                          .priority = static_cast<std::int32_t>(i == 1 ? 9 : i),
                          .measures = measures(20, 10),
                          .points = {{52.37, 4.88}},
                          .own = image(1024, own_height),
                          .large = image(2048, 1024)};
        }
        return sprites;
    };
    const auto set = LabelSet::make(three(1024), 1, 0.0F, 1.5F);
    ATLAS_REQUIRE(set.has_value(), describe(set.error()));
    CHECK(!(*set)->sprites()[1].large.empty()); // priority 9
    CHECK(!(*set)->sprites()[2].large.empty()); // priority 2
    CHECK((*set)->sprites()[0].large.empty());  // priority 0: let go
    CHECK((*set)->texels().size() == (3U * 1024U * 1024U) + (2U * 2048U * 1024U));
    CHECK((*set)->texels().size() <= LabelSet::max_all_texels);
    // own images beyond their budget: refused
    CHECK(LabelSet::make(three(2048), 1, 0.0F, 1.5F).error() == SpriteError::budget);
}

TEST_CASE("a block of label sprites as the app writes it is read field for field, and a broken one refused") {
    // Two sprites in MK/state/LabelBlock.kt's layout, the first with a large image.
    std::vector<std::byte> block;
    const auto put = [&](const auto& v) {
        const auto* p = reinterpret_cast<const std::byte*>(&v); // NOLINT: the value's bytes
        block.insert(block.end(), p, p + sizeof v);
    };
    const auto sprite = [&](std::int32_t id, std::uint32_t w, std::uint32_t h, std::span<const double> points,
                            std::uint32_t lw, std::uint32_t lh) {
        put(id);
        put(std::int32_t{7});
        for (const float v : {40.5F, 10.5F, 20.0F, -7.0F}) put(v);
        put(w);
        put(h);
        put(std::int32_t{1});
        put(std::int32_t{2});
        put(static_cast<std::uint32_t>(points.size() / 2));
        for (const double v : points) put(v);
        for (std::uint32_t i = 0; i < w * h; ++i) put(i * 0x01010101U);
        put(lw);
        put(lh);
        put(std::int32_t{3});
        put(std::int32_t{4});
        for (std::uint32_t i = 0; i < lw * lh; ++i) put(0xFF000000U + i);
    };
    put(label_block_magic);
    put(std::uint32_t{2});
    put(1.5F);
    const std::array<double, 2> one{52.37, 4.88};
    const std::array<double, 4> two{52.38, 4.89, 52.381, 4.891};
    sprite(1, 3, 2, one, 5, 3);
    sprite(9, 1, 1, two, 0, 0);
    const auto read = read_label_block(block);
    ATLAS_REQUIRE(read.has_value(), read.error());
    CHECK(read->large_scale == 1.5F);
    ATLAS_REQUIRE(read->sprites.size() == 2);
    const LabelSprite& a = read->sprites[0];
    CHECK(a.id == 1);
    CHECK(a.priority == 7);
    CHECK(a.measures.half == 40.5F);
    CHECK(a.measures.pad == 10.5F);
    CHECK(a.measures.text_height == 20.0F);
    CHECK(a.measures.baseline == -7.0F);
    CHECK(a.own.width == 3);
    CHECK(a.own.height == 2);
    CHECK(a.own.anchor_x == 1);
    CHECK(a.own.anchor_y == 2);
    CHECK(a.points == std::vector<std::array<double, 2>>{{52.37, 4.88}});
    CHECK(a.own.pixels ==
          std::vector<std::uint32_t>{0, 0x01010101U, 0x02020202U, 0x03030303U, 0x04040404U, 0x05050505U});
    CHECK(a.large.width == 5);
    CHECK(a.large.height == 3);
    CHECK(a.large.anchor_x == 3);
    CHECK(a.large.anchor_y == 4);
    CHECK(a.large.pixels.size() == 15);
    CHECK(a.large.pixels.back() == 0xFF00000EU);
    const LabelSprite& b = read->sprites[1];
    CHECK(b.id == 9);
    CHECK(b.points == std::vector<std::array<double, 2>>{{52.38, 4.89}, {52.381, 4.891}});
    CHECK(b.large.empty());
    CHECK(b.large.pixels.empty());
    CHECK(LabelSet::make(read->sprites, 1, 0.0F, read->large_scale).has_value());
    // refused: another magic, a block cut short in a sprite's head, points, pixels or large image, bytes past the last
    // sprite
    const std::size_t head = 12;
    const std::size_t sprite_head = 44;
    std::vector<std::byte> bad = block;
    bad[0] = std::byte{0};
    CHECK(read_label_block(bad).error() == "not a block of label sprites");
    CHECK(read_label_block(std::span(block).first(10)).error() == "the block ends before its count");
    CHECK(read_label_block(std::span(block).first(24)).error() == "the block ends in sprite 0's head");
    CHECK(read_label_block(std::span(block).first(head + sprite_head + 8)).error() ==
          "the block ends in sprite 0's points");
    CHECK(read_label_block(std::span(block).first(head + sprite_head + 16 + 8)).error() ==
          "the block ends in sprite 0's pixels");
    CHECK(read_label_block(std::span(block).first(head + sprite_head + 16 + 24 + 8)).error() ==
          "the block ends in sprite 0's large image");
    CHECK(read_label_block(std::span(block).first(head + sprite_head + 16 + 24 + 16 + 4)).error() ==
          "the block ends in sprite 0's large image");
    bad = block;
    bad.push_back(std::byte{0});
    CHECK(read_label_block(bad).error() == "the block runs on past its sprites");
    // an empty block: no sprites
    std::vector<std::byte> none;
    for (const std::uint32_t v : {label_block_magic, 0U, 0U}) {
        const auto* p = reinterpret_cast<const std::byte*>(&v); // NOLINT: the value's bytes
        none.insert(none.end(), p, p + 4);
    }
    CHECK(read_label_block(none).value().sprites.empty());
}

TEST_CASE("a frame's placement takes at most 0.5 ms on one core (gated with ATLAS_TIMED=1)") {
    // A busy board: 60 labels of up to four points, 8 keep-outs, 24 markers, panning and slowly zooming in over 1,000
    // frames (past the band once).
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
        (void)placement.update(c, frame, (1.0 + (0.001 * f)) / 400.0, f / 60.0);
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
