// Stage T of the shopfronts (geometry/Shopfronts.hpp) over the bundled Westerkerk (ATLAS_TILES_DIR): every business of
// a tile (a place style other than a service's) on a street wall is a shopfront, its building in its own tile; its
// point lies on that wall, its direction a unit vector, its place style and opening hours as the thing layer holds
// them, a food place with outdoor seating its terrace; its items as facade_details sorts what shopfront draws (the
// board as a door, its blade and its tables at their own points, each cut at its node); and every place with opening
// hours its glow's item after them. And the frame's open hours (passes/Shopfronts.hpp's OpenHours): each tile's
// distinct hours answered once a moment, open, closed or none.
#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "geometry/Doors.hpp"
#include "geometry/RoofShapes.hpp"
#include "geometry/Shopfronts.hpp"
#include "passes/Shopfronts.hpp"
#include "style/OpeningHours.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Source.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Points.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

using namespace atlas;

TEST_CASE("shopfronts: the bundled Westerkerk's businesses on a street wall are shopfronts, their items as "
          "facade_details sorts them, its places with opening hours their glows (ATLAS_TILES_DIR)") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled Westerkerk's shopfronts are not built here");
        return;
    }
    namespace tl = atlas::tiles;
    auto pack = test::open_pack(dir + "/amsterdam.atlas");
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const tl::RenderHead* head = pack->head();
    ATLAS_REQUIRE(head != nullptr);
    const tl::Frame& f = head->frame;
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(8U << 20U, Growth::grows, "shopfronts test");
    constexpr std::uint16_t board = 7;
    constexpr std::uint16_t blade = 8;
    constexpr std::uint16_t table = 9;
    constexpr std::uint16_t glow = 10;
    std::size_t shops = 0;
    std::size_t seating = 0;
    std::size_t brands = 0;
    std::size_t with_hours = 0;
    std::size_t places = 0;
    std::size_t water = 0;
    for (const tl::ChunkRef& chunk : pack->chunks().chunks()) {
        arena.reset();
        auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
        const geometry::TileShopfronts g = geometry::build_shopfronts(
            {.tile = &*tile, .head = head, .board = board, .blade = blade, .table = table, .glow = glow});
        water += g.water.size();
        const tl::Layer* things = tile->layer(tl::LayerId::thing);
        const tl::Layer* buildings = tile->layer(tl::LayerId::building);
        if (things == nullptr || buildings == nullptr) {
            CHECK(g.shops.empty());
            continue;
        }
        std::unordered_map<std::int64_t, std::uint32_t> by_id;
        for (std::uint32_t i = 0; i < buildings->count; ++i) by_id.emplace(buildings->integer(tl::building::id, i), i);
        // what the tile holds: its businesses on a wall (facade_details' biz), its places with opening hours
        std::vector<std::uint32_t> want;
        std::vector<std::uint32_t> want_places;
        for (std::uint32_t i = 0; i < things->count; ++i) {
            const auto oh = things->string(tl::thing::tag_opening_hours, i);
            if (things->boolean(tl::thing::poi, i).value_or(false) && oh && !oh->empty()) want_places.push_back(i);
            const auto cat = things->string(tl::thing::style_cat, i);
            if (!cat || *cat == "service" || !things->nullable_integer(tl::thing::wall, i)) continue;
            if (things->nullable_integer(tl::thing::building, i)) want.push_back(i);
        }
        REQUIRE(g.shops.size() == want.size());
        REQUIRE(g.places.size() == want_places.size());
        // the hours: each distinct value once
        const std::set<std::string> distinct(g.hours.begin(), g.hours.end());
        CHECK(distinct.size() == g.hours.size());
        std::size_t items = 0;
        for (std::size_t k = 0; k < g.shops.size(); ++k) {
            const geometry::Shopfront& s = g.shops[k];
            const std::uint32_t i = want[k];
            CAPTURE(s.id);
            CHECK(s.id == things->integer(tl::thing::id, i));
            CHECK(s.node == static_cast<std::uint32_t>(things->integer(tl::thing::index, i)));
            CHECK(std::abs(pymath::hypot(s.ux, s.uz) - 1.0) < 1e-12);
            const auto oh = things->string(tl::thing::tag_opening_hours, i);
            if (oh && !oh->empty()) {
                REQUIRE(s.hours < g.hours.size());
                CHECK(g.hours[s.hours] == *oh);
                ++with_hours;
            } else {
                CHECK(s.hours == geometry::no_hours);
            }
            const std::string cat(things->string(tl::thing::style_cat, i).value_or(""));
            const std::string outdoor(things->string(tl::thing::tag_outdoor_seating, i).value_or(""));
            const bool seats = cat == "food" && (outdoor == "yes" || outdoor == "sidewalk" ||
                                                 outdoor == "pedestrian_zone" || outdoor == "limited");
            CHECK(s.seating == seats);
            CHECK(s.kind == things->string(tl::thing::style_kind, i).value_or(""));
            CHECK(s.brand == things->string(tl::thing::style_brand, i).value_or(""));
            seating += s.seating ? 1U : 0U;
            brands += s.brand.empty() ? 0U : 1U;
            // on its wall: its point at 0.15 to 0.85 of one of its building's edges, along it
            const auto b = by_id.find(s.building);
            REQUIRE(b != by_id.end());
            const tl::RingView r = buildings->ring(tl::building::ring, b->second);
            std::vector<geometry::V2> ring;
            ring.reserve(r.size());
            for (std::size_t v = 0; v < r.size(); ++v) ring.emplace_back(f.x(r.lon[v]), f.z(r.lat[v]));
            bool on = false;
            for (std::size_t v = 0; v < ring.size() && !on; ++v) {
                const geometry::V2 a = ring[v];
                const geometry::V2 c = ring[(v + 1) % ring.size()];
                const double ex = c.x - a.x;
                const double ez = c.z - a.z;
                const double l = pymath::hypot(ex, ez);
                if (l == 0) continue;
                const double across = std::abs((ex * (s.qz - a.z)) - (ez * (s.qx - a.x))) / l;
                const double along = (((s.qx - a.x) * ex) + ((s.qz - a.z) * ez)) / (l * l);
                const double turned = std::abs((ex / l * s.uz) - (ez / l * s.ux));
                on = across < 1e-9 && along > 0.15 - 1e-12 && along < 0.85 + 1e-12 && turned < 1e-12;
            }
            CHECK_MESSAGE(on, "shopfront of node " << s.id << " lies on no wall of building " << s.building);
            // its items: the board at its building's centroid at half its height, less 0.03; the blade at P3(W2 +
            // 0.35, 3.15, 0.6); its tables' tops at 0.75 m; each cut at its node, in the facades' list by its node
            const geometry::V2 centre = geometry::centroid(ring);
            const double h = buildings->decimal(tl::building::h, b->second).value_or(0.0);
            const geometry::ShopFrame sf = geometry::shop_frame(s);
            const std::uint32_t n = 2 + (s.seating ? 3U : 0U);
            REQUIRE(items + n <= g.items.size());
            for (std::uint32_t j = 0; j < n; ++j) {
                const scene::ItemSpec& it = g.items[items + j];
                CAPTURE(j);
                CHECK(it.cut_x == s.x);
                CHECK(it.cut_z == s.z);
                CHECK((it.flags & scene::item_flag::radius) != 0);
                CHECK(it.order == scene::order_of(geometry::facades_list, (std::uint64_t{s.node} * 8U) + j));
                CHECK(it.cell == buildings->cell_of(b->second));
                if (j == 0) {
                    CHECK(it.kind == board);
                    CHECK(it.x == centre.x);
                    CHECK(it.z == centre.z);
                    CHECK(it.y == h / 2);
                    CHECK(it.offset == -0.03);
                    CHECK(it.slots[0] == geometry::board_slots);
                    // its sphere holds all it draws: the corners of its box, the night's spill 2.8 m to each side
                    // and 2 m out, the sign 3.62 m up
                    double far = 0;
                    for (const double u : {-2.8, 2.8}) {
                        for (const double off : {0.0, 2.0}) {
                            const geometry::V2 p = geometry::shop_point(sf, u, off);
                            for (const double y : {0.0, 3.62}) {
                                const double dx = p.x - it.x;
                                const double dy = y - it.y;
                                const double dz = p.z - it.z;
                                far = std::max(far, std::sqrt((dx * dx) + (dy * dy) + (dz * dz)));
                            }
                        }
                    }
                    CHECK(static_cast<double>(it.radius) > far);
                } else if (j == 1) {
                    const geometry::V2 p = geometry::shop_point(sf, geometry::shop_half_width + 0.35, 0.6);
                    CHECK(it.kind == blade);
                    CHECK(it.x == p.x);
                    CHECK(it.z == p.z);
                    CHECK(it.y == 3.15);
                    CHECK(it.slots[0] == geometry::blade_slots);
                } else {
                    const auto& place = geometry::table_places.at(j - 2);
                    const geometry::V2 p = geometry::shop_point(sf, place[0], place[1]);
                    CHECK(it.kind == table);
                    CHECK(it.x == p.x);
                    CHECK(it.z == p.z);
                    CHECK(it.y == 0.75);
                    CHECK(it.slots[0] == geometry::table_slots);
                }
            }
            items += n;
        }
        // the glows' items after every shopfront's, at their nodes 1.5 m up
        REQUIRE(g.items.size() == items + g.places.size());
        for (std::size_t k = 0; k < g.places.size(); ++k) {
            const geometry::OpenPlace& p = g.places[k];
            const std::uint32_t i = want_places[k];
            const scene::ItemSpec& it = g.items[items + k];
            CHECK(p.id == things->integer(tl::thing::id, i));
            REQUIRE(p.hours < g.hours.size());
            CHECK(g.hours[p.hours] == things->string(tl::thing::tag_opening_hours, i).value_or(""));
            CHECK(it.kind == glow);
            CHECK(it.x == p.x);
            CHECK(it.z == p.z);
            CHECK(it.y == 1.5);
            CHECK(it.order == scene::order_of(geometry::glows_list, p.node));
            CHECK(it.cell == things->cell_of(i));
            CHECK(it.slots[0] == geometry::glow_slots);
        }
        shops += g.shops.size();
        places += g.places.size();
    }
    MESSAGE("the Westerkerk: " << shops << " shopfronts (" << seating << " with a terrace, " << brands << " branded, "
                               << with_hours << " with opening hours), " << places << " places with opening hours, "
                               << water << " pieces of water");
    CHECK(shops > 400);
    CHECK(seating > 30);
    CHECK(with_hours > 100);
    CHECK(places > 100);
    CHECK(water > 0);
}

TEST_CASE("shopfronts: a tile's opening hours answered once a moment, open, closed or none") {
    passes::ShopTile tile;
    tile.serial = 41;
    tile.hours = {"Mo-Su 08:00-22:00", "Mo-Fr 09:00-17:00", "not a rule", "Sa,Su 10:00-18:00; Mo-Fr off"};
    passes::ShopTile other;
    other.serial = 42;
    other.hours = {"Mo-Fr 09:00-17:00"};
    frame::FrameState state;
    // Thursday 1 October 2026
    state.theme.year = 2026;
    state.theme.month = 10;
    state.theme.day = 1;
    state.theme.hour = 16;
    state.theme.minute = 0;
    passes::OpenHours hours;
    const std::vector<std::int8_t> afternoon = hours.of(tile, state);
    CHECK(afternoon == std::vector<std::int8_t>{1, 1, -1, 0});
    // the same moment again: the same answers, kept
    CHECK(&hours.of(tile, state) == &hours.of(tile, state));
    CHECK(hours.of(other, state) == std::vector<std::int8_t>{1});
    // at 21:30, the café alone; at 23:00, none
    state.theme.hour = 21;
    state.theme.minute = 30;
    CHECK(hours.of(tile, state) == std::vector<std::int8_t>{1, 0, -1, 0});
    CHECK(hours.of(other, state) == std::vector<std::int8_t>{0});
    state.theme.hour = 23;
    state.theme.minute = 0;
    CHECK(hours.of(tile, state) == std::vector<std::int8_t>{0, 0, -1, 0});
    // Saturday 3 October, noon: the weekend's
    state.theme.day = 3;
    state.theme.hour = 12;
    CHECK(hours.of(tile, state) == std::vector<std::int8_t>{1, 0, -1, 1});
    // each as open_at answers it
    for (const std::string& oh : tile.hours) {
        CAPTURE(oh);
        const auto now =
            style::open_at(oh, style::Weekday::sa, {.hour = 12, .minute = 0}, {.year = 2026, .month = 10, .day = 3});
        const std::int8_t want = !now ? std::int8_t{-1} : *now ? std::int8_t{1} : std::int8_t{0};
        const auto at = static_cast<std::size_t>(&oh - tile.hours.data());
        CHECK(hours.of(tile, state)[at] == want);
    }
}
