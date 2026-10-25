// draw_over's park shadows in the ground's stage T (geometry/Ground.cpp; render/coasters.py and woods.py's draw): a
// theme park window's coaster tracks as polylines of their subdivided points with their heights (the region items'
// pieces end to end), packed as (x, z, height) triples, and its woods' trees as 12-gons about their trunks moved by the
// shadow of 0.72 of their heights, after the casters' shadows, in a union of their own.
#include "base/arena.hpp"
#include "geometry/Ground.hpp"
#include "geometry/Items.hpp"
#include "tests/base/require.hpp"
#include "tests/ground/park_window.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <span>
#include <string>
#include <vector>

namespace geo = atlas::geometry;
namespace tl = atlas::tiles;

TEST_CASE("ground: a park's coaster tracks and woods' trees keep what their shadows on the ground are drawn from") {
    const std::string path = atlas::test::park_window("park-shadows");
    ATLAS_REQUIRE(!path.empty());
    auto pack = atlas::test::open_pack(path);
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const tl::RenderHead& head = *pack->head();
    ATLAS_REQUIRE(pack->style() != nullptr);
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "park shadows test");
    const auto chunks = pack->chunks().level(head.levels.front());
    ATLAS_REQUIRE(!chunks.empty());
    auto tile = pack->decode(chunks.front(), zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    const geo::P2 origin{.x = head.frame.x(tile->header.west), .z = head.frame.z(tile->header.south)};
    const geo::GroundTile g = geo::build_ground(*tile, head, *pack->style(), 0, origin);
    const geo::TileRegionItems items = geo::build_region_items({.tile = &*tile, .head = &head, .kind = 0});
    // every track: its pieces end to end, their heights, at 0.12; a wooden coaster's marked
    REQUIRE(g.tracks.size() == items.tracks.size());
    REQUIRE(g.tracks.size() == 5);
    std::size_t wooden = 0;
    for (std::size_t t = 0; t < g.tracks.size(); ++t) {
        const geo::ShadowTrack& st = g.tracks[t];
        const geo::CoasterTrackSpan& span = items.tracks[t];
        const geo::Polyline& pl = g.polylines[st.polyline];
        CHECK(pl.heights);
        CHECK(pl.y == 0.12);
        REQUIRE(pl.count == span.count + 1);
        CHECK(st.cut == span.cut);
        CHECK(st.wooden == (span.kind == 1));
        wooden += st.wooden ? 1U : 0U;
        for (std::uint32_t k = 0; k < span.count; ++k) {
            const geo::RegionItem& piece = items.items[span.first + k];
            CHECK(g.points[pl.first + k].x == piece.x);
            CHECK(g.points[pl.first + k].z == piece.z);
            CHECK(g.heights[pl.height + k] == piece.ya);
            CHECK(g.points[pl.first + k + 1].x == piece.bx);
            CHECK(g.heights[pl.height + k + 1] == piece.yb);
        }
        CHECK(geo::polyline_words_of(pl) == 3 * pl.count);
    }
    CHECK(wooden == 1);
    // every planted tree: its 12-gon after the casters' shadows, moved by the shadow of 0.72 of its height
    std::size_t trees = 0;
    for (const geo::RegionItem& it : items.items) trees += it.kind == geo::RegionItemKind::wood ? 1U : 0U;
    REQUIRE(trees > 50);
    REQUIRE(g.woods.size() == trees);
    CHECK(g.woods_first() + g.woods.size() == g.shadows.size());
    for (std::size_t i = 0; i < g.woods.size(); ++i) {
        const geo::Shadow& sh = g.shadows[g.woods_first() + i];
        const geo::ShadowTree& n = g.woods[i];
        CHECK(sh.move == geo::ShadowMove::moved);
        CHECK(sh.tree);
        CHECK(sh.count == 12);
        CHECK(sh.y == 0.12);
        CHECK(sh.h == n.h * 0.72);
        CHECK(sh.at.x == n.x);
        // woods.draw's r · 0.95 about the trunk, the first corner at a = 0
        CHECK(g.shadow_points[sh.first].x == n.x + (n.r * 0.95));
        CHECK(g.shadow_points[sh.first].z == n.z);
    }
    // packed: the tracks' points as (x, z, height) triples, the woods' fans a union of their own
    const geo::GroundGpu gpu = geo::pack(g);
    CHECK(gpu.woods_draw.count == g.woods.size() * 10 * 3);
    std::uint32_t words = 0;
    for (std::size_t p = 0; p < g.polylines.size(); ++p) {
        const geo::Polyline& pl = g.polylines[p];
        if (pl.heights) {
            for (std::uint32_t k = 0; k < pl.count; ++k) {
                const auto h = std::bit_cast<float>(gpu.lines[gpu.points.first + words + (3 * k) + 2]);
                CHECK(h == static_cast<float>(g.heights[pl.height + k]));
            }
        }
        words += geo::polyline_words_of(pl);
    }
    MESSAGE(g.tracks.size() << " tracks, " << g.woods.size() << " trees, " << g.woods_first() << " casters' shadows");
}
