// What a press on the map points at (scene/Pick.hpp), on the bundled grachten pack when ATLAS_TILES_DIR names a folder
// holding it: a ray straight down onto a building's footprint names that building, met at its lid, its height down from
// the eye; a ray slanting steeply onto a point just under its top names it too, met at its lid where the ray reaches
// its height; one slanting low from 60 m beside it meets a wall nearer than its middle; a ray that does not fall to
// the street, or falls where no pack reaches, meets none; and a pick sees only the packs it is given.
#include "base/arena.hpp"
#include "scene/Pick.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Store.hpp"
#include "tiles/layers/Buildings.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <cmath>
#include <format>
#include <vector>

using namespace atlas;

namespace {

namespace tl = atlas::tiles;

/// A building of the pack to aim at: its id, a point inside its footprint (frame metres), its base and its top.
struct Target {
    std::int64_t id = 0;
    double x = 0.0;
    double z = 0.0;
    double base = 0.0;
    double top = 0.0;
};

/// Whether (x, z) lies in the ring (even-odd).
bool inside(const std::vector<std::array<double, 2>>& ring, double x, double z) {
    bool in = false;
    for (std::size_t i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
        const auto& a = ring[i];
        const auto& b = ring[j];
        if ((a[1] > z) != (b[1] > z) && x < ((b[0] - a[0]) * (z - a[1]) / (b[1] - a[1])) + a[0]) in = !in;
    }
    return in;
}

} // namespace

TEST_CASE("pick: the building a ray from the eye meets first, on the bundled grachten pack") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled packs are not checked here");
        return;
    }
    const std::string path = dir + "/grachten.atlas";
    auto pack = test::open_pack(path);
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const tl::RenderHead* head = pack->head();
    ATLAS_REQUIRE(head != nullptr);
    const view::GroundFrame ground = view::frame_of(head->frame);
    const std::uint8_t z = pack->chunks().level(head->levels[0]).front().tile.z;
    tl::Store store;
    ATLAS_REQUIRE(store.add({.id = "grachten", .kind = tl::PackKind::render, .path = path}).has_value());
    const std::array<scene::PickPack, 1> packs{scene::PickPack{.id = "grachten", .z = z}};
    // Buildings to aim at: outlines drawn as themselves, at least 8 m tall, each with a point well inside it (the
    // middle of its ring's box, where that lies inside, 2 m in from every edge).
    std::vector<Target> targets;
    {
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        Arena arena(4U << 20U, Growth::grows, "pick test");
        for (const tl::ChunkRef& chunk : pack->chunks().level(head->levels[0])) {
            arena.reset();
            auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
            ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
            const tl::Layer* l = tile->layer(tl::LayerId::building);
            if (l == nullptr) continue;
            for (std::uint32_t i = 0; i < l->count && targets.size() < 40; ++i) {
                if (l->boolean(tl::building::hidden, i).value_or(false)) continue;
                const auto top = l->decimal(tl::building::h, i);
                if (!top || *top < 8.0) continue;
                const tl::RingView r = l->ring(tl::building::ring, i);
                std::vector<std::array<double, 2>> ring;
                double x0 = 1e300;
                double x1 = -1e300;
                double z0 = 1e300;
                double z1 = -1e300;
                for (std::size_t k = 0; k < r.size(); ++k) {
                    const double lat = tl::Frame::degrees(r.lat[k]);
                    const double lon = tl::Frame::degrees(r.lon[k]);
                    ring.push_back({ground.x(lat, lon), ground.z(lat, lon)});
                    x0 = std::min(x0, ring.back()[0]);
                    x1 = std::max(x1, ring.back()[0]);
                    z0 = std::min(z0, ring.back()[1]);
                    z1 = std::max(z1, ring.back()[1]);
                }
                const double cx = (x0 + x1) / 2.0;
                const double cz = (z0 + z1) / 2.0;
                bool deep = inside(ring, cx, cz);
                for (const double dx : {-2.0, 2.0}) deep = deep && inside(ring, cx + dx, cz);
                for (const double dz : {-2.0, 2.0}) deep = deep && inside(ring, cx, cz + dz);
                if (!deep) continue;
                targets.push_back({.id = l->integer(tl::building::id, i),
                                   .x = cx,
                                   .z = cz,
                                   .base = l->decimal(tl::building::min, i).value_or(0.0),
                                   .top = *top});
            }
            if (targets.size() >= 40) break;
        }
    }
    ATLAS_REQUIRE(targets.size() >= 10);
    std::size_t named = 0;
    std::size_t slanted = 0;
    for (const Target& t : targets) {
        // straight down from 300 m: the building itself (or one standing over its middle), met at a lid
        const view::Vec3 above{t.x, 300.0, t.z};
        const auto down = scene::pick_building(store, packs, ground, above, {0.0, -1.0, 0.0});
        REQUIRE(down.has_value());
        CHECK(down->distance <= 300.0 - t.top + 1e-6);
        if (down->id == t.id) {
            ++named;
            CHECK(down->distance == doctest::Approx(300.0 - t.top).epsilon(1e-9));
            CHECK(down->part == 0);
        }
        // slanting down from 30 m over it and 15 m east onto a point 1 m under its top in its middle: met first at its
        // lid, half a metre from that point, by itself (nothing taller stands within its 2 m)
        const view::Vec3 eye{t.x + 15.0, t.top + 30.0, t.z};
        const view::Vec3 aim{t.x - eye.x, (t.top - 1.0) - eye.y, t.z - eye.z};
        const auto slant = scene::pick_building(store, packs, ground, eye, aim);
        REQUIRE(slant.has_value());
        const double to_aim = std::sqrt((aim.x * aim.x) + (aim.y * aim.y) + (aim.z * aim.z));
        CHECK(slant->distance < to_aim);
        if (slant->id == t.id) {
            ++slanted;
            CHECK(slant->distance == doctest::Approx(to_aim * 30.0 / 31.0).epsilon(1e-9));
        }
        // from 60 m east of it, 20 m up, slanting onto its middle 1 m above its base: a building's wall nearer than
        // that middle (in the canal ring, mostly another's, standing between)
        const view::Vec3 side{t.x + 60.0, t.base + 20.0, t.z};
        const view::Vec3 low{t.x - side.x, (t.base + 1.0) - side.y, t.z - side.z};
        const auto beside = scene::pick_building(store, packs, ground, side, low);
        REQUIRE(beside.has_value());
        CHECK(beside->distance < std::sqrt((low.x * low.x) + (low.y * low.y) + (low.z * low.z)));
    }
    MESSAGE(std::format("{} buildings: {} named straight down, {} slanting", targets.size(), named, slanted));
    // nearly every one names itself (another may stand over a middle, or within 2 m taller)
    CHECK(named * 10 >= targets.size() * 9);
    CHECK(slanted * 10 >= targets.size() * 9);
    const Target& t = targets.front();
    // a ray that does not fall to the street; one far from the pack; and none in a pack not given
    CHECK(!scene::pick_building(store, packs, ground, {t.x, 300.0, t.z}, {0.0, 1.0, 0.0}).has_value());
    CHECK(!scene::pick_building(store, packs, ground, {t.x + 1e6, 300.0, t.z}, {0.0, -1.0, 0.0}).has_value());
    const std::array<scene::PickPack, 1> other{scene::PickPack{.id = "amsterdam", .z = z}};
    CHECK(!scene::pick_building(store, other, ground, {t.x, 300.0, t.z}, {0.0, -1.0, 0.0}).has_value());
}
