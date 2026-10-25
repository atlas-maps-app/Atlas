// A region's items' stage T (ENGINE.md step 4, "Trees and things"; geometry/Items.hpp) and the render thread's choices
// (passes/Items.hpp): a window's tower, turbine, cranes, masts, gantry, conveyor and power line as draw_items gathers
// them (each an item in its order, at its point, from 5 m, the radius cut where it applies at one point; a crane's form
// on a quay and in a yard, by its outline and by its rail; a segment each of a line, a gantry's legs at its way's ends,
// a conveyor's gaps, a power line's conductors' heights as the port computes them), every bundled tile's items,
// item_choice's thresholds and printed values, and the moment's tones. That the items are the port's, the twin's
// elements against the port's SVG say (L1).
#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "geometry/Items.hpp"
#include "load/Cut.hpp"
#include "load/Regions.hpp"
#include "load/Window.hpp"
#include "passes/Items.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileWriter.hpp"
#include "twin/item/ItemShader.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <format>
#include <map>
#include <string>
#include <vector>

namespace {

namespace tl = atlas::tiles;
namespace gm = atlas::geometry;
namespace sh = atlas::shaders;

/// The test packs' style (tests/tiles/support.hpp's tables), typed.
[[nodiscard]] tl::Style test_style() {
    auto tables = tl::read_style(atlas::test::style_bytes());
    REQUIRE(tables.has_value());
    auto style = tl::Style::of(*tables);
    REQUIRE(style.has_value());
    return *style;
}

/// A window's items: built from Overpass's JSON `elements` about the Westerkerk, decoded and gathered.
[[nodiscard]] gm::TileRegionItems window_items(const std::string& elements) {
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
)" + elements + "]}";
    const auto built =
        atlas::load::build_window(std::as_bytes(std::span(data.data(), data.size())), k, atlas::load::place_of(k));
    ATLAS_REQUIRE_OR_RETURN(gm::TileRegionItems{}, built.has_value(), built.error().what);
    auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(built->pack));
    ATLAS_REQUIRE_OR_RETURN(gm::TileRegionItems{}, pack.has_value(), tl::describe(pack.error()));
    ATLAS_REQUIRE_OR_RETURN(gm::TileRegionItems{}, pack->chunks().chunks().size() == 1);
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "items test, a window");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    auto tile = pack->decode(pack->chunks().chunks()[0], zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE_OR_RETURN(gm::TileRegionItems{}, tile.has_value(), tl::describe(tile.error()));
    return gm::build_region_items({.tile = &*tile, .head = pack->head(), .kind = 7});
}

/// A park's items: its document loaded as a theme park's region (its rides `rides`, its woods planted), cut, each tile
/// decoded and gathered; with the scene they came from.
struct ParkItems {
    atlas::load::Scene scene;
    gm::TileRegionItems items;
};

[[nodiscard]] ParkItems park_items(const std::string& elements, const atlas::load::RideProfiles& rides) {
    const std::string data = R"({"version": 0.6, "elements": [
)" + elements + "]}";
    const auto doc = atlas::osm::read_overpass(std::as_bytes(std::span(data.data(), data.size())));
    ATLAS_REQUIRE_OR_RETURN(ParkItems{}, doc.has_value());
    const atlas::load::RegionConfig region{.name = "test",
                                           .frame = atlas::load::westerkerk_frame(),
                                           .bbox = {52.368, 4.878, 52.376, 4.890},
                                           .tradition = nullptr,
                                           .palette = atlas::load::amsterdam_palette(),
                                           .park = &rides,
                                           .woods = atlas::load::WoodsAnchor{.cx = 0.0, .cz = 0.0, .reach = 800.0}};
    auto scene = atlas::load::load_region(*doc, region);
    ATLAS_REQUIRE_OR_RETURN(ParkItems{}, scene.has_value());
    const auto cut = atlas::load::cut_region(*scene, region.frame, {});
    ATLAS_REQUIRE_OR_RETURN(ParkItems{}, cut.has_value(), cut.error().what);
    tl::RenderHead head;
    head.frame = region.frame;
    ParkItems out{.scene = std::move(*scene), .items = {}};
    tl::TileDecoder decoder;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "items test, a park");
    for (const tl::TileFill& t : *cut) {
        if (t.level() != 14) continue;
        const auto raw = tl::encode_tile(t);
        ATLAS_REQUIRE_OR_RETURN(ParkItems{}, raw.has_value());
        arena.reset();
        const auto tile = decoder.decode(*raw, arena);
        ATLAS_REQUIRE_OR_RETURN(ParkItems{}, tile.has_value());
        gm::TileRegionItems g = gm::build_region_items({.tile = &*tile, .head = &head, .kind = 7});
        for (gm::CoasterTrackSpan& span : g.tracks) {
            span.first += static_cast<std::uint32_t>(out.items.items.size());
            out.items.tracks.push_back(std::move(span));
        }
        for (gm::RegionItem& it : g.items) {
            if (it.kind == gm::RegionItemKind::coaster)
                it.track += static_cast<std::uint32_t>(out.items.tracks.size() - g.tracks.size());
        }
        out.items.items.insert(out.items.items.end(), g.items.begin(), g.items.end());
        out.items.specs.insert(out.items.specs.end(), g.specs.begin(), g.specs.end());
    }
    return out;
}

} // namespace

TEST_CASE("items: a theme park's coaster tracks in 3 m pieces and its woods' trees, as coasters.py and woods.py draw "
          "them") {
    const std::string elements = R"({"type": "node", "id": 1, "lat": 52.37025, "lon": 4.88025,
    "tags": {"attraction": "roller_coaster", "name": "Big One"}},
{"type": "way", "id": 10, "tags": {"roller_coaster": "track", "roller_coaster:track": "lift_hill"}, "geometry": [
  {"lat": 52.3700, "lon": 4.8800}, {"lat": 52.3700, "lon": 4.8805}, {"lat": 52.3705, "lon": 4.8805}]},
{"type": "way", "id": 11, "tags": {"roller_coaster": "track", "layer": "2"}, "geometry": [
  {"lat": 52.3705, "lon": 4.8805}, {"lat": 52.37052, "lon": 4.88025}, {"lat": 52.3705, "lon": 4.8800},
  {"lat": 52.3702, "lon": 4.8799}, {"lat": 52.3700, "lon": 4.8800}]},
{"type": "way", "id": 12, "tags": {"railway": "monorail"}, "geometry": [
  {"lat": 52.3710, "lon": 4.8810}, {"lat": 52.3712, "lon": 4.8815}]},
{"type": "way", "id": 20, "tags": {"landuse": "forest"}, "geometry": [
  {"lat": 52.3720, "lon": 4.8800}, {"lat": 52.3720, "lon": 4.8812}, {"lat": 52.3728, "lon": 4.8812},
  {"lat": 52.3728, "lon": 4.8800}, {"lat": 52.3720, "lon": 4.8800}]})";
    atlas::load::RideProfiles rides;
    rides.heights = {{"Big One", 30.0}};
    const ParkItems park = park_items(elements, rides);
    const gm::TileRegionItems& g = park.items;
    REQUIRE(g.items.size() == g.specs.size());
    // each track's pieces as subdivide makes them: every edge in max(1, int(Lg / 3)) pieces, end to end, the heights
    // between its points' heights; the radius cut at every fourth of its points and its last
    REQUIRE(g.tracks.size() == park.scene.coasters.size());
    for (const gm::CoasterTrackSpan& span : g.tracks) {
        REQUIRE(span.count > 0);
        const gm::RegionItem& first = g.items[span.first];
        const atlas::load::CoasterTrack* tr = nullptr;
        for (const atlas::load::CoasterTrack& t : park.scene.coasters) {
            if (std::abs(t.pts.front().x - first.x) < 1e-9 && std::abs(t.pts.front().z - first.z) < 1e-9) tr = &t;
        }
        ATLAS_REQUIRE(tr != nullptr);
        std::size_t pieces = 0;
        for (std::size_t k = 1; k < tr->pts.size(); ++k) {
            pieces += static_cast<std::size_t>(std::max<std::int64_t>(
                1, atlas::pymath::to_int(atlas::geometry::dist(tr->pts[k - 1], tr->pts[k]) / gm::coaster_step)));
        }
        CHECK(span.count == pieces);
        CHECK(span.cut.size() == ((tr->pts.size() + 3) / 4) + 1);
        CHECK(span.kind == (tr->kind == "mono" ? 2 : (tr->kind == "wood" ? 1 : 0)));
        CHECK(first.ya == tr->ys.front());
        const gm::RegionItem& last = g.items[span.first + span.count - 1];
        CHECK(last.bx == tr->pts.back().x);
        CHECK(last.bz == tr->pts.back().z);
        CHECK(last.yb == tr->ys.back());
        for (std::uint32_t k = span.first; k < span.first + span.count; ++k) {
            const gm::RegionItem& it = g.items[k];
            const atlas::scene::ItemSpec& sp = g.specs[k];
            CHECK(it.kind == gm::RegionItemKind::coaster);
            CHECK(sp.y == (it.ya + it.yb) / 2); // c = cam.cam(((a + b) / 2, (ya + yb) / 2))
            CHECK(sp.min_depth == 3.0F);
            CHECK(sp.flags == 0);
            CHECK(sp.slots[0] == 4);
            if (k > span.first) {
                CHECK(it.x == g.items[k - 1].bx);
                CHECK(it.ya == g.items[k - 1].yb);
            }
        }
    }
    // the woods' trees: planted once for the region, each with random.Random(id)'s first draws, from 4 m at (x, 0.7 h,
    // z), after every coaster's piece in their group
    std::size_t trees = 0;
    for (std::size_t k = 0; k < g.items.size(); ++k) {
        const gm::RegionItem& it = g.items[k];
        if (it.kind != gm::RegionItemKind::wood) continue;
        ++trees;
        const atlas::scene::ItemSpec& sp = g.specs[k];
        CHECK(sp.y == it.h * 0.7);
        CHECK(sp.min_depth == 4.0F);
        CHECK((sp.flags & atlas::scene::item_flag::radius) != 0);
        CHECK(((sp.order & ((std::uint64_t{1} << 37U) - 1)) >> 16U) >= gm::wood_index_base);
        // its clumps as woods.draw draws them from random.Random(id)
        atlas::pymath::PyRandom rnd(it.id);
        const double r = it.crown / 2;
        for (std::size_t q = 0; q < 4; ++q) {
            const double ang = rnd.uniform(0.0, 2 * atlas::pymath::pi);
            const double rad = r * 0.42 * atlas::pymath::sqrt(rnd.random());
            const double dy = rnd.uniform(-0.25, 0.25) * r;
            const double rr = r * rnd.uniform(0.5, 0.66);
            CHECK(it.clumps.at(4 * q) == static_cast<float>(rad * atlas::pymath::cos(ang)));
            CHECK(it.clumps.at((4 * q) + 1) == static_cast<float>(dy));
            CHECK(it.clumps.at((4 * q) + 2) == static_cast<float>(rad * atlas::pymath::sin(ang)));
            CHECK(it.clumps.at((4 * q) + 3) == static_cast<float>(rr));
            CHECK(atlas::pymath::hypot(rad, dy) < 2.2); // never behind the near plane where the tree is drawn
        }
        CHECK(it.crown >= 6.0);
        CHECK(it.crown <= 9.0);
    }
    CHECK(trees == park.scene.woods.size());
    CHECK(trees > 50); // a 130 by 90 m wood, one about every 8.5 m
}

TEST_CASE("items: a window's tower, turbine, cranes, masts, gantry, conveyor and power line, as draw_items gathers "
          "them") {
    const std::string elements = R"({"type": "node", "id": 101, "lat": 52.3745, "lon": 4.8835,
    "tags": {"man_made": "tower", "tower:type": "observation", "height": "60"}},
{"type": "node", "id": 102, "lat": 52.3746, "lon": 4.8836, "tags": {"power": "generator", "generator:source": "wind"}},
{"type": "node", "id": 103, "lat": 52.3747, "lon": 4.8837, "tags": {"man_made": "mast", "tower:type": "lighting"}},
{"type": "node", "id": 104, "lat": 52.3748, "lon": 4.8838, "tags": {"man_made": "mast", "height": "12"}},
{"type": "node", "id": 105, "lat": 52.3749, "lon": 4.8839, "tags": {"man_made": "tower"}},
{"type": "way", "id": 201, "tags": {"man_made": "gantry"},
    "geometry": [{"lat": 52.3740, "lon": 4.8830}, {"lat": 52.3740, "lon": 4.8834}, {"lat": 52.3741, "lon": 4.8838}]},
{"type": "way", "id": 202, "tags": {"man_made": "goods_conveyor", "height": "9"},
    "geometry": [{"lat": 52.3750, "lon": 4.8830}, {"lat": 52.3750, "lon": 4.8845}]},
{"type": "way", "id": 203, "tags": {"power": "line"},
    "geometry": [{"lat": 52.3742, "lon": 4.8830}, {"lat": 52.3743, "lon": 4.8860}, {"lat": 52.3744, "lon": 4.8890}]},
{"type": "way", "id": 204, "tags": {"natural": "bay"},
    "geometry": [{"lat": 52.37555, "lon": 4.8840}, {"lat": 52.37555, "lon": 4.8860}, {"lat": 52.3762, "lon": 4.8860},
                 {"lat": 52.3762, "lon": 4.8840}, {"lat": 52.37555, "lon": 4.8840}]},
{"type": "node", "id": 106, "lat": 52.3754, "lon": 4.8850, "tags": {"man_made": "crane"}},
{"type": "way", "id": 205, "tags": {"man_made": "crane", "height": "30"},
    "geometry": [{"lat": 52.3735, "lon": 4.8820}, {"lat": 52.3735, "lon": 4.88229}, {"lat": 52.37361, "lon": 4.88229},
                 {"lat": 52.37361, "lon": 4.8820}, {"lat": 52.3735, "lon": 4.8820}]},
{"type": "node", "id": 107, "lat": 52.3733, "lon": 4.8840, "tags": {"man_made": "crane"}},
{"type": "way", "id": 206, "tags": {"railway": "crane"},
    "geometry": [{"lat": 52.3732, "lon": 4.8835}, {"lat": 52.3732, "lon": 4.8845}]})";
    const gm::TileRegionItems g = window_items(elements);
    REQUIRE(g.items.size() == g.specs.size());
    std::map<gm::RegionItemKind, std::vector<const gm::RegionItem*>> by;
    for (const gm::RegionItem& it : g.items) by[it.kind].push_back(&it);
    // a tower with a height alone (a tower without one is no item), its cab by its type
    REQUIRE(by[gm::RegionItemKind::tower].size() == 1);
    CHECK(by[gm::RegionItemKind::tower][0]->cab);
    CHECK(by[gm::RegionItemKind::tower][0]->h == 60.0);
    // a turbine's 100 m and its rotor's 50 m unless mapped
    REQUIRE(by[gm::RegionItemKind::turbine].size() == 1);
    CHECK(by[gm::RegionItemKind::turbine][0]->h == 100.0);
    CHECK(by[gm::RegionItemKind::turbine][0]->rotor == 50.0);
    // the cranes in S['cranes'] order (the elements'): on the bay's quay (a window's water has natural shores; a bay's
    // edges are quays) its boom toward the water (north, -z), hu its distance to the quay edge, 44 m; the outline's
    // along its longest edge, its half extents, its mapped height; by its rail a portal gantry along it, 9 by 14 m
    REQUIRE(by[gm::RegionItemKind::crane].size() == 3);
    const gm::RegionItem& quay = *by[gm::RegionItemKind::crane][0];
    CHECK(quay.id == 106);
    CHECK(!quay.yard);
    CHECK(!quay.way);
    CHECK(quay.h == 44.0);
    CHECK(quay.uz < -0.99);
    CHECK(quay.vx == -quay.uz);
    CHECK(quay.vz == quay.ux);
    CHECK(quay.hu > 15.0);
    CHECK(quay.hu < 18.0);
    const gm::RegionItem& rail = *by[gm::RegionItemKind::crane][2];
    CHECK(rail.id == 107);
    CHECK(rail.yard);
    CHECK(!rail.way);
    CHECK(rail.h == 26.0);
    CHECK(rail.ux > 0.99);
    CHECK(rail.hu == 9.0);
    CHECK(rail.hv == 14.0);
    const gm::RegionItem& outline = *by[gm::RegionItemKind::crane][1];
    CHECK(outline.id == 205);
    CHECK(outline.yard);
    CHECK(outline.way);
    CHECK(outline.h == 30.0);
    CHECK(std::abs(outline.ux) > 0.99);
    CHECK(outline.hu > 9.0);
    CHECK(outline.hu < 10.5);
    CHECK(outline.hv > 5.5);
    CHECK(outline.hv < 7.0);
    // a lighting mast's 30 m, another's mapped height
    REQUIRE(by[gm::RegionItemKind::mast].size() == 2);
    CHECK(by[gm::RegionItemKind::mast][0]->lighting);
    CHECK(by[gm::RegionItemKind::mast][0]->h == 30.0);
    CHECK(!by[gm::RegionItemKind::mast][1]->lighting);
    CHECK(by[gm::RegionItemKind::mast][1]->h == 12.0);
    // a gantry's two segments at 7 m, its legs at its way's first and last points
    REQUIRE(by[gm::RegionItemKind::gantry].size() == 2);
    CHECK(by[gm::RegionItemKind::gantry][0]->h == 7.0);
    CHECK(by[gm::RegionItemKind::gantry][0]->legs_a);
    CHECK(!by[gm::RegionItemKind::gantry][0]->legs_b);
    CHECK(!by[gm::RegionItemKind::gantry][1]->legs_a);
    CHECK(by[gm::RegionItemKind::gantry][1]->legs_b);
    // a conveyor's mapped height, its legs every Lg / max(1, int(Lg / 22))
    REQUIRE(by[gm::RegionItemKind::conveyor].size() == 1);
    const gm::RegionItem& conveyor = *by[gm::RegionItemKind::conveyor][0];
    CHECK(conveyor.h == 9.0);
    const double lg = atlas::pymath::hypot(conveyor.bx - conveyor.x, conveyor.bz - conveyor.z);
    CHECK(conveyor.gaps == static_cast<std::uint32_t>(std::max(1.0, std::trunc(lg / 22))));
    // a power line's two segments at 38 m, its conductors' heights H - 4 sin(pi f) min(1, Lg / 300) * 8 / 8
    REQUIRE(by[gm::RegionItemKind::power].size() == 2);
    for (const gm::RegionItem* p : by[gm::RegionItemKind::power]) {
        CHECK(p->h == gm::power_height);
        const double l = atlas::pymath::hypot(p->bx - p->x, p->bz - p->z);
        for (std::size_t q = 0; q < 3; ++q) {
            const double f = 0.25 * static_cast<double>(q + 1);
            const double want =
                38.0 - (4 * atlas::pymath::sin(atlas::pymath::pi * f) * atlas::pymath::min(1.0, l / 300) * 8 / 8);
            CHECK(p->sag[q] == want);
        }
    }
    CHECK(by[gm::RegionItemKind::power][0]->segment == 0);
    CHECK(by[gm::RegionItemKind::power][1]->segment == 1);
    // each an item in draw_items' order, from 5 m: a point's radius cut at it, a segment's the pass's (at either end)
    for (std::size_t i = 0; i < g.items.size(); ++i) {
        const gm::RegionItem& it = g.items[i];
        const atlas::scene::ItemSpec& s = g.specs[i];
        CHECK(s.min_depth == 5.0F);
        CHECK((s.order >> 40U) == gm::region_items_list);
        const std::uint64_t group = (s.order & ((std::uint64_t{1} << 40U) - 1)) >> 37U;
        CHECK(group == gm::region_item_group[static_cast<std::size_t>(it.kind)]);
        CHECK(s.slots[0] == gm::region_item_slots(it));
        const bool segment = it.kind == gm::RegionItemKind::gantry || it.kind == gm::RegionItemKind::conveyor ||
                             it.kind == gm::RegionItemKind::power;
        CHECK(((s.flags & atlas::scene::item_flag::radius) != 0) == !segment);
        if (segment) {
            CHECK(s.x == (it.x + it.bx) / 2);
            CHECK(s.y == it.h);
        } else if (it.kind == gm::RegionItemKind::crane) {
            CHECK(s.x == it.x);
            CHECK(s.y == 15.0); // cam.cam((x, 15.0, z))
            CHECK(s.slots[0] == (it.yard ? 45U : 61U));
        } else {
            CHECK(s.x == it.x);
            CHECK(s.y == it.h / 2);
        }
    }
    // the heap's words: ITEM_VECTORS each, the kind and flags in the first's w
    const std::vector<atlas::hlsl::uint4> words = atlas::passes::item_words(g, 0.0, 0.0, 0.0);
    REQUIRE(words.size() == g.items.size() * sh::ITEM_VECTORS);
    for (std::size_t i = 0; i < g.items.size(); ++i) {
        const sh::ItemWords w = sh::item_words(words[i * sh::ITEM_VECTORS], words[(i * sh::ITEM_VECTORS) + 1],
                                               words[(i * sh::ITEM_VECTORS) + 2]);
        CHECK(w.kind == static_cast<std::uint32_t>(g.items[i].kind));
        CHECK(((w.flags & sh::ITEM_CAB) != 0) == g.items[i].cab);
        CHECK(((w.flags & sh::ITEM_LIGHTING) != 0) == g.items[i].lighting);
        if (g.items[i].kind != gm::RegionItemKind::crane) continue;
        // a crane's half sizes, height and axes
        CHECK(((w.flags & sh::ITEM_YARD) != 0) == g.items[i].yard);
        CHECK(w.end.x == static_cast<float>(g.items[i].hu));
        CHECK(w.end.y == static_cast<float>(g.items[i].hv));
        CHECK(w.h == static_cast<float>(g.items[i].h));
        CHECK(w.u.x == static_cast<float>(g.items[i].ux));
        CHECK(w.v.y == static_cast<float>(g.items[i].vz));
    }
}

TEST_CASE("items: the render thread's choices, as draw_items compares and prints them in double") {
    const auto item = [](gm::RegionItemKind kind) {
        gm::RegionItem it;
        it.kind = kind;
        it.h = 30.0;
        return it;
    };
    const double focal = 1000.0;
    // a mast from focal / d of 0.25, a gantry's and a conveyor's from 0.3
    CHECK(atlas::passes::item_choice(item(gm::RegionItemKind::mast), focal / 0.25, focal, false).drawn);
    CHECK(!atlas::passes::item_choice(item(gm::RegionItemKind::mast), std::nextafter(focal / 0.25, 1e9), focal, false)
               .drawn);
    CHECK(atlas::passes::item_choice(item(gm::RegionItemKind::gantry), focal / 0.3, focal, false).drawn);
    CHECK(!atlas::passes::item_choice(item(gm::RegionItemKind::conveyor), 3400.0, focal, false).drawn);
    // a mast's glow '%.1f' % max(2.0, 9 · focal / d) and its core '%.1f' % max(0.6, 1.2 · focal / d)
    const atlas::passes::ItemChoice m = atlas::passes::item_choice(item(gm::RegionItemKind::mast), 300.0, focal, true);
    CHECK(m.a == static_cast<float>(atlas::pymath::round(9.0 * focal / 300.0, 1)));
    CHECK(m.b == static_cast<float>(atlas::pymath::round(1.2 * focal / 300.0, 1)));
    CHECK((m.flags & sh::ITEM_JOB_NIGHT) != 0);
    // a power line whole to 1,200 m, fading by 2,000 m (its fade printed), its tower '%.2f' % max(0.5, 1.4 s)
    const atlas::passes::ItemChoice near =
        atlas::passes::item_choice(item(gm::RegionItemKind::power), 1200.0, focal, false);
    CHECK(near.drawn);
    CHECK((near.flags & sh::ITEM_JOB_FADE) == 0);
    CHECK(near.a == static_cast<float>(atlas::pymath::round(atlas::pymath::max(0.5, 1.4 * focal / 1200.0), 2)));
    const atlas::passes::ItemChoice mid =
        atlas::passes::item_choice(item(gm::RegionItemKind::power), 1555.0, focal, false);
    CHECK((mid.flags & sh::ITEM_JOB_FADE) != 0);
    CHECK(mid.b == static_cast<float>(atlas::pymath::round((2000.0 - 1555.0) / 800.0, 2)));
    CHECK(!atlas::passes::item_choice(item(gm::RegionItemKind::power), 2000.0, focal, false).drawn);
    // a turbine's s, its light's offset max(1, 3 s), its hub '%.1f' % max(0.8, 2.2 s) and light '%.1f' % max(0.8, 1.2
    // s)
    const atlas::passes::ItemChoice t =
        atlas::passes::item_choice(item(gm::RegionItemKind::turbine), 700.0, focal, false);
    const double s = focal / 700.0;
    CHECK(t.a == static_cast<float>(s));
    CHECK(t.b == static_cast<float>(atlas::pymath::max(1.0, 3 * s)));
    CHECK(t.c == static_cast<float>(atlas::pymath::round(atlas::pymath::max(0.8, 2.2 * s), 1)));
    CHECK(t.d == static_cast<float>(atlas::pymath::round(atlas::pymath::max(0.8, 1.2 * s), 1)));
    // a crane from focal / d of 0.6, its stays '%.2f' % max(0.5, 0.35 · focal / d)
    CHECK(atlas::passes::item_choice(item(gm::RegionItemKind::crane), focal / 0.6, focal, false).drawn);
    CHECK(!atlas::passes::item_choice(item(gm::RegionItemKind::crane), std::nextafter(focal / 0.6, 1e9), focal, false)
               .drawn);
    const atlas::passes::ItemChoice cr =
        atlas::passes::item_choice(item(gm::RegionItemKind::crane), 450.0, focal, false);
    CHECK(cr.a == static_cast<float>(atlas::pymath::round(atlas::pymath::max(0.5, 0.35 * focal / 450.0), 2)));
    CHECK(atlas::passes::item_choice(item(gm::RegionItemKind::crane), 1500.0, focal, false).a == 0.5F);
    // a tower always
    CHECK(atlas::passes::item_choice(item(gm::RegionItemKind::tower), 9000.0, focal, false).drawn);
}

TEST_CASE("items: the moment's tones, the port's colours by day and at night") {
    const tl::Style style = test_style();
    atlas::style::ThemeBlock day{};
    day.shadow = {.r = 0x27, .g = 0x30, .b = 0x5A};
    day.wind_direction = 225.0;
    atlas::style::ThemeBlock night = day;
    night.flags = atlas::style::theme_flag::night;
    const atlas::shaders::Theme th{};
    const auto d = atlas::passes::item_tones(day, th, style.items);
    const auto n = atlas::passes::item_tones(night, th, style.items);
    const auto bgr = [](std::uint32_t rgb) {
        return ((rgb >> 16U) & 0xFFU) | (rgb & 0xFF00U) | ((rgb & 0xFFU) << 16U);
    };
    CHECK(d[sh::ITEM_TONE_TURBINE] == bgr(0xE9ECEE));
    CHECK(n[sh::ITEM_TONE_TURBINE] == bgr(0x3A3F4C));
    CHECK(d[sh::ITEM_TONE_POWER + 1] == bgr(0x4A4F57));
    CHECK(n[sh::ITEM_TONE_POWER + 1] == bgr(0x1A1E26));
    // a crane's stays: the steel unlit
    CHECK(d[sh::ITEM_TONE_STEEL] == bgr(0x7D8993));
    CHECK(n[sh::ITEM_TONE_STEEL] == bgr(0x1A1E24));
    // the antenna's gradient: tohex(mix(hx('#8C9096'), hx(sh), .35)), '#8C9096', tohex(mix(..., .5)); sh the moment's
    // shadow by day, '#05060A' at night
    const auto mixed = [](std::uint32_t a, std::uint32_t b, double t) {
        const auto channel = [&](unsigned shift) {
            const auto x = static_cast<double>((a >> shift) & 0xFFU);
            const auto y = static_cast<double>((b >> shift) & 0xFFU);
            return static_cast<std::uint32_t>(atlas::pymath::round(x + ((y - x) * t)));
        };
        return (channel(16) << 16U) | (channel(8) << 8U) | channel(0);
    };
    const std::uint32_t at = sh::ITEM_TONE_TOWER + 9;
    CHECK(d[at + 1] == bgr(0x8C9096));
    CHECK(d[at] == bgr(mixed(0x8C9096, 0x27305A, 0.35)));
    CHECK(d[at + 2] == bgr(mixed(0x8C9096, 0x27305A, 0.5)));
    CHECK(n[at] == bgr(mixed(0x8C9096, 0x05060A, 0.35)));
    // the hour's wind: fx, fz = sin(wd), -cos(wd)
    CHECK(std::bit_cast<float>(d[sh::ITEM_TONE_WIND]) ==
          static_cast<float>(atlas::pymath::sin(atlas::pymath::radians(225.0))));
    CHECK(std::bit_cast<float>(d[sh::ITEM_TONE_WIND + 1]) ==
          static_cast<float>(-atlas::pymath::cos(atlas::pymath::radians(225.0))));
}

TEST_CASE("items: stage T of every bundled tile") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled packs are not checked here");
        return;
    }
    for (const char* name : {"amsterdam", "grachten"}) {
        auto pack = atlas::test::open_pack(dir + "/" + name + ".atlas");
        ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
        const tl::RenderHead* head = pack->head();
        ATLAS_REQUIRE(head != nullptr);
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        atlas::Arena arena(4U << 20U, atlas::Growth::grows, "items test");
        std::array<std::size_t, 7> kinds{};
        for (const tl::ChunkRef& chunk : pack->chunks().level(head->levels[0])) {
            arena.reset();
            auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
            ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
            const gm::TileRegionItems g = gm::build_region_items({.tile = &*tile, .head = head, .kind = 7});
            CHECK(g.items.size() == g.specs.size());
            for (const gm::RegionItem& it : g.items) {
                ++kinds[static_cast<std::size_t>(it.kind)];
                if (it.kind == gm::RegionItemKind::tower) CHECK(it.h > 0);
            }
        }
        MESSAGE(std::format("{}: {} towers, {} turbines, {} cranes, {} masts, {} gantries' and {} conveyors' segments, "
                            "{} power lines' segments",
                            name, kinds[0], kinds[1], kinds[2], kinds[3], kinds[4], kinds[5], kinds[6]));
        if (std::string_view(name) == "grachten") {
            // the canal ring's 143 towers with a height as regions.py's num() reads it, its crane and its three masts
            CHECK(kinds[0] == 143);
            CHECK(kinds[2] == 1);
            CHECK(kinds[3] == 3);
        } else {
            CHECK(std::ranges::all_of(kinds, [](std::size_t k) { return k == 0; })); // the living map has none
        }
    }
}
