// Stage T of the doors (geometry/Doors.hpp) over the bundled Westerkerk (ATLAS_TILES_DIR): every address of a tile that
// is no business and opens onto a street wall (the thing layer's door, style.cat, building and wall) is a door, its
// building found in its own tile (stage P files it there); its point lies on that wall at 0.15 to 0.85 of it, the
// wall's direction is a unit vector, and its item sorts as facade_details sorts it (its building's centroid at half its
// height, less 0.03; its radius cut at the node; the facades' list; five slots), its sphere holding its door and plate.
#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "geometry/Doors.hpp"
#include "geometry/RoofShapes.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Source.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Points.hpp"

#include <doctest/doctest.h>

#include <cmath>
#include <unordered_map>
#include <vector>

using namespace atlas;

TEST_CASE("doors: the bundled Westerkerk's addresses on a street wall are doors, each with its building in its own "
          "tile (ATLAS_TILES_DIR)") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled Westerkerk's doors are not built here");
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
    Arena arena(8U << 20U, Growth::grows, "doors test");
    std::size_t doors = 0;
    std::size_t shops = 0;
    std::size_t tiles = 0;
    for (const tl::ChunkRef& chunk : pack->chunks().chunks()) {
        arena.reset();
        auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
        ++tiles;
        const tl::Layer* things = tile->layer(tl::LayerId::thing);
        const tl::Layer* buildings = tile->layer(tl::LayerId::building);
        if (things == nullptr || buildings == nullptr) continue;
        std::unordered_map<std::int64_t, std::uint32_t> building_of;
        for (std::uint32_t i = 0; i < buildings->count; ++i)
            building_of.emplace(buildings->integer(tl::building::index, i), i);
        // what the tile holds: its addresses on a wall, and its businesses on one (a shopfront's, not a door)
        std::size_t want = 0;
        for (std::uint32_t i = 0; i < things->count; ++i) {
            const bool wall = things->nullable_integer(tl::thing::wall, i).has_value();
            const auto building = things->nullable_integer(tl::thing::building, i);
            // filed with its building, always
            if (building) CHECK(building_of.contains(*building));
            if (!wall) continue;
            CHECK(building.has_value());
            const auto cat = things->string(tl::thing::style_cat, i);
            if (cat && *cat != "service") {
                ++shops;
                continue;
            }
            if (things->boolean(tl::thing::door, i).value_or(false)) ++want;
        }
        const geometry::TileDoors g = geometry::build_doors({.tile = &*tile, .head = head, .kind = 7});
        CHECK(g.doors.size() == want);
        REQUIRE(g.items.size() == g.doors.size());
        // each door against its building's ring
        std::unordered_map<std::int64_t, std::uint32_t> by_id;
        for (std::uint32_t i = 0; i < buildings->count; ++i) by_id.emplace(buildings->integer(tl::building::id, i), i);
        for (std::size_t k = 0; k < g.doors.size(); ++k) {
            const geometry::Door& d = g.doors[k];
            const scene::ItemSpec& it = g.items[k];
            CHECK(std::abs(pymath::hypot(d.ux, d.uz) - 1.0) < 1e-12);
            const auto b = by_id.find(d.building);
            REQUIRE(b != by_id.end());
            const tl::RingView r = buildings->ring(tl::building::ring, b->second);
            std::vector<geometry::V2> ring;
            ring.reserve(r.size());
            for (std::size_t v = 0; v < r.size(); ++v) ring.emplace_back(f.x(r.lon[v]), f.z(r.lat[v]));
            // on one of its walls, at 0.15 to 0.85 of it
            bool on = false;
            for (std::size_t v = 0; v < ring.size() && !on; ++v) {
                const geometry::V2 a = ring[v];
                const geometry::V2 c = ring[(v + 1) % ring.size()];
                const double ex = c.x - a.x;
                const double ez = c.z - a.z;
                const double l = pymath::hypot(ex, ez);
                if (l == 0) continue;
                const double across = std::abs((ex * (d.qz - a.z)) - (ez * (d.qx - a.x))) / l;
                const double along = (((d.qx - a.x) * ex) + ((d.qz - a.z) * ez)) / (l * l);
                const double turned = std::abs((ex / l * d.uz) - (ez / l * d.ux));
                on = across < 1e-9 && along > 0.15 - 1e-12 && along < 0.85 + 1e-12 && turned < 1e-12;
            }
            CHECK_MESSAGE(on, "door of node " << d.id << " lies on no wall of building " << d.building);
            // facade_details' item: cb = cam.cam((cx, b['h'] / 2, cz)), its depth less 0.03, far(n['x'], n['z'])
            const geometry::V2 centre = geometry::centroid(ring);
            CHECK(it.x == centre.x);
            CHECK(it.z == centre.z);
            CHECK(it.y == buildings->decimal(tl::building::h, b->second).value_or(0.0) / 2);
            CHECK(it.offset == -0.03);
            CHECK(it.cut_x == d.x);
            CHECK(it.cut_z == d.z);
            CHECK((it.flags & scene::item_flag::radius) != 0);
            CHECK(it.order == scene::order_of(geometry::facades_list, d.node));
            CHECK(it.slots[0] == geometry::door_slots);
            CHECK(it.kind == 7);
            CHECK(static_cast<double>(it.radius) >
                  pymath::hypot(d.qx - centre.x, d.qz - centre.z) + std::abs(it.y) + 2.75);
        }
        doors += g.doors.size();
    }
    MESSAGE("the Westerkerk's " << tiles << " tiles: " << doors << " doors, " << shops << " shops on a street wall");
    CHECK(doors > 7000);
}
