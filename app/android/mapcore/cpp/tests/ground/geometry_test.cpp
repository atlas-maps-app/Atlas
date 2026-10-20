// The ground's stage T (geometry/Ground.hpp, Ribbons.hpp, Water.hpp; plan-vector K9): the port's ribbons, offset lines
// and dashes on hand-made lines, the water's turn and fans, and on the bundled packs (ATLAS_TILES_DIR) every tile's
// ground built and packed: each fill's triangles cover its ring's area (the ribbons' quads and the rings' earcut), the
// water's fans count each piece's winding, every record names its fill, and a dense tile builds within stage T's time.
#include "base/pymath/PyMath.hpp"
#include "geometry/Ground.hpp"
#include "geometry/Ribbons.hpp"
#include "geometry/Water.hpp"
#include "sun/Hulls.hpp"
#include "sun/Samples.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <span>
#include <string>
#include <vector>

namespace geo = atlas::geometry;
namespace tl = atlas::tiles;

namespace {

/// Twice the signed area of the triangle (a, b, c).
double area2(const geo::P2& a, const geo::P2& b, const geo::P2& c) {
    return ((b.x - a.x) * (c.z - a.z)) - ((c.x - a.x) * (b.z - a.z));
}

/// The vertices of a packed tile, back in the frame's metres.
std::vector<geo::P2> vertices_of(const geo::GroundGpu& gpu, const geo::GroundTile& g) {
    std::vector<geo::P2> v;
    for (std::uint32_t i = 0; i < gpu.vertices.count; ++i) {
        const auto x = std::bit_cast<float>(gpu.ground[gpu.vertices.first + (3 * i)]);
        const auto z = std::bit_cast<float>(gpu.ground[gpu.vertices.first + (3 * i) + 1]);
        v.push_back({static_cast<double>(x) + g.origin.x, static_cast<double>(z) + g.origin.z});
    }
    return v;
}

/// The vertex stage's swept hull (shaders/ground/ground.slang's shadow_vs) in double: count + 2 corners, the points in
/// order and the two turning points twice (where the edges facing the offset begin and end), each moved by `d` where
/// the edge on its side (before it, after it for a turning point's second corner) faces the offset.
std::vector<geo::P2> swept(std::span<const geo::P2> hull, geo::P2 d) {
    const std::size_t n = hull.size();
    auto facing = [&](std::size_t k) {
        const geo::P2& a = hull[k];
        const geo::P2& b = hull[(k + 1) % n];
        return (((b.z - a.z) * d.x) - ((b.x - a.x) * d.z)) > 0;
    };
    std::size_t t1 = n;
    std::size_t t2 = n;
    bool before = facing(n - 1);
    for (std::size_t k = 0; k < n; ++k) {
        const bool after = facing(k);
        if (after != before) {
            if (t1 == n) {
                t1 = k;
            } else if (t2 == n) {
                t2 = k;
            }
        }
        before = after;
    }
    std::vector<geo::P2> out;
    for (std::size_t j = 0; j < n + 2; ++j) {
        std::size_t i = std::min(j, n - 1);
        bool second = false;
        if (t2 < n) {
            if (j <= t1) {
                i = j;
            } else if (j == t1 + 1) {
                i = t1;
                second = true;
            } else if (j <= t2 + 1) {
                i = j - 1;
            } else if (j == t2 + 2) {
                i = t2;
                second = true;
            } else {
                i = j - 2;
            }
        }
        const geo::P2& q = hull[i];
        out.push_back(facing(second ? i : (i + n - 1) % n) ? geo::P2{.x = q.x + d.x, .z = q.z + d.z} : q);
    }
    return out;
}

/// How far point p lies outside the convex polygon `poly` (0 inside), metres.
double outside(std::span<const geo::P2> poly, const geo::P2& p) {
    double worst = 0;
    const double turn = geo::signed_area(poly) >= 0 ? 1.0 : -1.0;
    const std::size_t n = poly.size();
    for (std::size_t k = 0; k < n; ++k) {
        const geo::P2& a = poly[k];
        const geo::P2& b = poly[(k + 1) % n];
        const double len = atlas::pymath::hypot(b.x - a.x, b.z - a.z);
        if (len == 0) continue;
        // the signed distance to the edge's line, positive outside
        const double side = -turn * (((b.x - a.x) * (p.z - a.z)) - ((p.x - a.x) * (b.z - a.z))) / len;
        worst = std::max(worst, side);
    }
    return worst;
}

} // namespace

TEST_CASE("ground: ribbons, offset lines and dashes are the port's") {
    // a right-angled bend: the middle point moves along its neighbours' averaged normal, by w / 2
    const std::vector<geo::P2> pts{{0, 0}, {10, 0}, {10, 10}};
    std::vector<geo::P2> r;
    geo::ribbon(pts, 4, r);
    REQUIRE(r.size() == 6);
    // ends: the first segment's normal (0, 1)·2 and the last's (-1, 0)·2; the middle: (-10, 10)/|..| · 2
    CHECK(r[0] == geo::P2{0, 2});
    CHECK(r[5] == geo::P2{0, -2});
    CHECK(r[2] == geo::P2{8, 10});
    CHECK(r[3] == geo::P2{12, 10});
    const double k = 2 / std::sqrt(200.0) * 10; // the middle's offset per axis
    CHECK(std::abs(r[1].x - (10 - k)) < 1e-12);
    CHECK(std::abs(r[1].z - k) < 1e-12);
    // offset_line: left positive
    std::vector<geo::P2> o;
    geo::offset_line(pts, 1, o);
    REQUIRE(o.size() == 3);
    CHECK(o[0] == geo::P2{0, 1});
    CHECK(o[2] == geo::P2{9, 10});
    // dashes: 3 on, 9 off, measured along the whole line (the second dash starts 2 m into the second segment)
    std::vector<geo::Dash> d;
    geo::dashes(pts, 3, 9, d);
    REQUIRE(d.size() == 2);
    CHECK(d[0].a == geo::P2{0, 0});
    CHECK(d[0].b == geo::P2{3, 0});
    CHECK(d[1].a == geo::P2{10, 2});
    CHECK(d[1].b == geo::P2{10, 5});
    // a zero-length segment is skipped without advancing the pattern
    const std::vector<geo::P2> dup{{0, 0}, {0, 0}, {13, 0}};
    d.clear();
    geo::dashes(dup, 3, 9, d);
    REQUIRE(d.size() == 2);
    CHECK(d[1].a == geo::P2{12, 0});
    CHECK(d[1].b == geo::P2{13, 0});
    // draw_over's near: every len // 8th point and the last
    std::vector<geo::P2> line;
    line.reserve(17);
    for (int i = 0; i < 17; ++i) line.push_back({.x = static_cast<double>(i) * 100, .z = 0});
    CHECK(geo::near_radius(line, 1600, 0, 10));      // the last point
    CHECK(geo::near_radius(line, 200, 0, 10));       // point 2 (step 2)
    CHECK_FALSE(geo::near_radius(line, 300, 0, 10)); // point 3 is not sampled
    CHECK(geo::signed_area(std::vector<geo::P2>{{0, 0}, {1, 0}, {1, 1}}) == 0.5);
}

TEST_CASE("ground: the water's pieces turn as wound turns them, and their fans count their winding") {
    std::vector<geo::P2> ring{{0, 0}, {0, 10}, {10, 10}, {10, 0}}; // negative (x east, z south)
    geo::orient_water(ring, false);
    CHECK(geo::signed_area(ring) > 0);
    geo::orient_water(ring, true);
    CHECK(geo::signed_area(ring) < 0);
    // a concave ring's fan: the triangles' signed areas sum to the ring's
    const std::vector<geo::P2> c{{0, 0}, {10, 0}, {10, 10}, {5, 2}, {0, 10}};
    std::vector<std::uint32_t> f;
    geo::fan(0, static_cast<std::uint32_t>(c.size()), f);
    REQUIRE(f.size() == 9);
    double sum = 0;
    for (std::size_t t = 0; t < f.size(); t += 3) sum += area2(c[f[t]], c[f[t + 1]], c[f[t + 2]]);
    CHECK(std::abs((sum / 2) - geo::signed_area(c)) < 1e-12);
}

TEST_CASE("ground: every tile of the bundled packs builds and packs, its triangles covering its rings") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the real packs are not checked here");
        return;
    }
    for (const char* name : {"amsterdam", "grachten"}) {
        auto pack = atlas::test::open_pack((std::filesystem::path(dir) / (std::string(name) + ".atlas")).string());
        ATLAS_REQUIRE(pack.has_value(), "the pack opens");
        const tl::RenderHead& head = *pack->head();
        const geo::GroundStyle style = geo::GroundStyle::of(pack->style());
        CHECK(!style.land_kinds().empty());
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        atlas::Arena arena(4U << 20U, atlas::Growth::grows, "ground geometry test");
        std::size_t fills = 0;
        std::size_t strokes = 0;
        std::size_t uncovered = 0;
        std::size_t water_off = 0;
        double worst_ms = 0;
        for (const tl::ChunkRef& c : pack->chunks().level(head.levels.front())) {
            arena.reset();
            auto tile = pack->decode(c, zstd, decoder, arena, raw, scratch);
            ATLAS_REQUIRE(tile.has_value(), "the tile decodes");
            const geo::P2 origin{.x = head.frame.x(tile->header.west), .z = head.frame.z(tile->header.south)};
            const auto t0 = std::chrono::steady_clock::now();
            const geo::GroundTile g = geo::build_ground(*tile, head, style, 7, origin);
            const geo::GroundGpu gpu = geo::pack(g);
            worst_ms = std::max(
                worst_ms, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
            fills += g.fills.size();
            strokes += g.strokes.size();
            REQUIRE(gpu.fills.count == g.fills.size());
            const std::vector<geo::P2> v = vertices_of(gpu, g);
            // every vertex names its fill; the triangles of each fill cover its ring's area
            std::vector<double> covered(g.fills.size(), 0.0);
            for (const geo::Words draw : {gpu.above, gpu.below, gpu.canopy}) {
                for (std::uint32_t t = 0; t < draw.count; t += 3) {
                    const std::uint32_t a = gpu.ground[draw.first + t];
                    const std::uint32_t f = gpu.ground[gpu.vertices.first + (3 * a) + 2];
                    covered[f] +=
                        std::abs(area2(v[a], v[gpu.ground[draw.first + t + 1]], v[gpu.ground[draw.first + t + 2]])) / 2;
                }
            }
            std::vector<double> wound(g.fills.size(), 0.0);
            for (std::uint32_t t = 0; t < gpu.fans.count; t += 3) {
                const std::uint32_t a = gpu.ground[gpu.fans.first + t];
                const std::uint32_t f = gpu.ground[gpu.vertices.first + (3 * a) + 2];
                wound[f] +=
                    area2(v[a], v[gpu.ground[gpu.fans.first + t + 1]], v[gpu.ground[gpu.fans.first + t + 2]]) / 2;
            }
            for (std::size_t f = 0; f < g.fills.size(); ++f) {
                const geo::Fill& fill = g.fills[f];
                const std::span<const geo::P2> ring(g.points.data() + fill.first, fill.count);
                const double a = std::abs(geo::signed_area(ring));
                if (fill.kind == geo::FillKind::water) {
                    // the fan's signed areas sum to the piece's, turned as wound turns it
                    const double want = fill.hole ? -a : a;
                    if (std::abs(wound[f] - want) > 1e-3 * std::max(1.0, a)) ++water_off;
                    continue;
                }
                if (!fill.drawn) continue;
                // earcut's triangles cover a simple ring exactly; a ribbon's quads cover it where it does not fold
                if (std::abs(covered[f] - a) > 1e-3 * std::max(1.0, a)) ++uncovered;
            }
        }
        MESSAGE(
            std::string(name) << ": " << fills << " fills, " << strokes << " strokes; " << uncovered
                              << " fills whose triangles' area is not their ring's (folded ribbons, crossing rings), "
                              << water_off << " water pieces off; the slowest tile built and packed in " << worst_ms
                              << " ms");
        CHECK(water_off == 0);
        CHECK(static_cast<double>(uncovered) < 0.02 * static_cast<double>(fills));
    }
}

TEST_CASE("ground: the shadows before the sun, and the vertex stage's swept hulls are the port's hulls") {
    // hand-made casters: a square prism, an L-shaped one (its hull fills the corner), a flat one (a line: its shadow
    // the hull of the line and its offset), a raised part, a tree
    atlas::sun::TileCasters c;
    auto solid = [&](std::initializer_list<geo::P2> pts, double h, double min) {
        for (const geo::P2& p : pts) {
            c.solids.x.push_back(p.x);
            c.solids.z.push_back(p.z);
        }
        c.solids.first.push_back(static_cast<std::uint32_t>(c.solids.x.size()));
        c.solids.h.push_back(h);
        c.solids.min.push_back(min);
        c.solids.source.push_back(atlas::sun::Source::building);
        c.solids.record.push_back(0);
    };
    solid({{.x = 0, .z = 0}, {.x = 10, .z = 0}, {.x = 10, .z = 10}, {.x = 0, .z = 10}}, 20, 0);
    solid({{.x = 20, .z = 0},
           {.x = 40, .z = 0},
           {.x = 40, .z = 8},
           {.x = 28, .z = 8},
           {.x = 28, .z = 20},
           {.x = 20, .z = 20}},
          15, 0);
    solid({{.x = 50, .z = 0}, {.x = 55, .z = 5}, {.x = 60, .z = 10}}, 6, 0);
    solid({{.x = 70, .z = 0}, {.x = 74, .z = 0}, {.x = 74, .z = 4}, {.x = 70, .z = 4}}, 12, 5);
    c.trees.x.push_back(90);
    c.trees.z.push_back(5);
    c.trees.h.push_back(10);
    c.trees.crown.push_back(6);
    c.trees.gy.push_back(0);
    geo::GroundTile g;
    geo::build_shadows(c, g);
    REQUIRE(g.shadows.size() == 5);
    CHECK(g.shadows[0].move == geo::ShadowMove::sweep);
    CHECK(g.shadows[0].count == 4);
    CHECK(g.shadows[1].count == 5); // the L's hull: its inner corner gone
    CHECK(g.shadows[2].count == 2); // the line's two ends
    CHECK(g.shadows[3].move == geo::ShadowMove::moved);
    CHECK(g.shadows[3].count == 4);
    CHECK(g.shadows[4].tree);
    CHECK(g.shadows[4].count == 16);
    CHECK(g.shadows[4].h == 7.0);
    CHECK(g.shadows[0].at == geo::P2{5, 5});
    for (const geo::Shadow& sh : g.shadows) {
        if (sh.move == geo::ShadowMove::sweep && sh.count > 2) {
            CHECK(geo::signed_area(std::span(g.shadow_points).subspan(sh.first, sh.count)) > 0);
        }
    }
    // packed: every triangle's three corners are one shadow's (a swept hull's count + 2, the others' count)
    const geo::GroundGpu gpu = geo::pack(g);
    REQUIRE(gpu.shadows.count == 5);
    REQUIRE(gpu.shadow_points.count == g.shadow_points.size());
    std::vector<std::uint32_t> triangles(g.shadows.size(), 0);
    for (std::uint32_t t = 0; t < gpu.shadow_draw.count; t += 3) {
        const std::uint32_t s = gpu.ground[gpu.shadow_draw.first + t] >> 16U;
        REQUIRE(s < g.shadows.size());
        const geo::Shadow& sh = g.shadows[s];
        for (std::uint32_t k = 0; k < 3; ++k) {
            const std::uint32_t id = gpu.ground[gpu.shadow_draw.first + t + k];
            CHECK((id >> 16U) == s);
            CHECK((id & 0xFFFFU) < sh.count + (sh.move == geo::ShadowMove::sweep ? 2U : 0U));
        }
        ++triangles[s];
    }
    CHECK(triangles[0] == 4);  // a swept hull: a fan over its count + 2 corners
    CHECK(triangles[2] == 2);  // the line's: a parallelogram
    CHECK(triangles[3] == 2);  // the raised square's earcut
    CHECK(triangles[4] == 14); // the crown's fan
}

TEST_CASE("ground: on every tile of the bundled packs, the swept hulls cover the port's hulls") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the real packs are not checked here");
        return;
    }
    // the sunny moment's sun (style/ThemeTest.cpp's), and a low one
    const double len = std::sqrt((0.5307 * 0.5307) + (0.2779 * 0.2779) + (0.8006 * 0.8006));
    const std::array<atlas::sun::Sun, 2> suns{
        {{.x = 0.5307 / len, .y = 0.2779 / len, .z = 0.8006 / len}, {.x = -0.9, .y = 0.08, .z = 0.2}}};
    for (const char* name : {"amsterdam", "grachten"}) {
        auto pack = atlas::test::open_pack((std::filesystem::path(dir) / (std::string(name) + ".atlas")).string());
        ATLAS_REQUIRE(pack.has_value(), "the pack opens");
        const tl::RenderHead& head = *pack->head();
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        atlas::Arena arena(4U << 20U, atlas::Growth::grows, "ground shadows test");
        std::size_t prisms = 0;
        std::size_t moved = 0;
        double worst = 0;      // metres a polygon's point lies outside the other's
        double worst_area = 0; // the areas' relative difference
        for (const tl::ChunkRef& ch : pack->chunks().level(head.levels.front())) {
            arena.reset();
            auto tile = pack->decode(ch, zstd, decoder, arena, raw, scratch);
            ATLAS_REQUIRE(tile.has_value(), "the tile decodes");
            const atlas::sun::TileCasters casters = atlas::sun::solids_of(*tile, head, 7);
            geo::GroundTile g;
            geo::build_shadows(casters, g);
            for (const atlas::sun::Sun& sun : suns) {
                const atlas::sun::TileShadows port = atlas::sun::shadows_of(casters.solids, casters.trees, sun, {});
                // the port's polygons by their caster
                for (std::uint32_t p = 0; p < port.size(); ++p) {
                    const std::span<const double> xs = port.xs(p);
                    const std::span<const double> zs = port.zs(p);
                    std::vector<geo::P2> theirs;
                    theirs.reserve(xs.size());
                    for (std::size_t k = 0; k < xs.size(); ++k) theirs.push_back({.x = xs[k], .z = zs[k]});
                    const bool tree = port.kind[p] == atlas::sun::ShadowKind::tree;
                    const auto it = std::ranges::find_if(
                        g.shadows, [&](const geo::Shadow& sh) { return sh.tree == tree && sh.owner == port.owner[p]; });
                    if (theirs.size() < 3) continue; // nothing the renders draw (cam.poly needs three points)
                    ATLAS_REQUIRE(it != g.shadows.end(), "every polygon the port casts has its shadow");
                    const geo::Shadow& sh = *it;
                    const geo::P2 d{.x = -sun.x / sun.y * sh.h, .z = -sun.z / sun.y * sh.h};
                    const std::span<const geo::P2> pts = std::span(g.shadow_points).subspan(sh.first, sh.count);
                    if (sh.move == geo::ShadowMove::moved) {
                        // moved whole: the same points, the offset added first or last
                        REQUIRE(theirs.size() == pts.size());
                        for (std::size_t k = 0; k < pts.size(); ++k) {
                            worst = std::max(worst, atlas::pymath::hypot(pts[k].x + d.x - theirs[k].x,
                                                                         pts[k].z + d.z - theirs[k].z));
                        }
                        ++moved;
                        continue;
                    }
                    // swept: the hull2 of the footprint and its moved top, within its millimetres
                    const std::vector<geo::P2> ours = swept(pts, d);
                    for (const geo::P2& q : theirs) worst = std::max(worst, outside(ours, q));
                    for (const geo::P2& q : ours) worst = std::max(worst, outside(theirs, q));
                    const double a = std::abs(geo::signed_area(theirs));
                    if (a > 1) worst_area = std::max(worst_area, std::abs(std::abs(geo::signed_area(ours)) - a) / a);
                    ++prisms;
                }
            }
        }
        MESSAGE(std::string(name) << ": " << prisms << " swept hulls and " << moved
                                  << " moved outlines and crowns against the port's for two suns; the worst point "
                                  << worst << " m outside the other's polygon, the worst area " << worst_area
                                  << " apart");
        CHECK(prisms > 1000);
        CHECK(worst < 2e-3); // hull2's millimetres
        CHECK(worst_area < 1e-3);
    }
}

TEST_CASE("ground: the bundled Westerkerk's check is the host build's") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the real packs are not checked here");
        return;
    }
    auto pack = atlas::test::open_pack((std::filesystem::path(dir) / "amsterdam.atlas").string());
    ATLAS_REQUIRE(pack.has_value(), "the pack opens");
    const auto t0 = std::chrono::steady_clock::now();
    const std::string line = geo::ground_line(*pack);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    MESSAGE(line << " in " << ms << " ms");
    (void)std::printf("%s (%.0f ms)\n", line.c_str(), ms); // NOLINT: the line CI's logs keep
    CHECK(line.ends_with(" ok"));
}
