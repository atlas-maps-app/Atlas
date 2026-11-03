// What a press on the map points at (scene/Pick.hpp), on the bundled grachten pack when ATLAS_TILES_DIR names a folder
// holding it: a ray straight down onto a building's footprint names that building, met at its lid, its height down from
// the eye, and its roof, in its roof's slot word (a flat top's with its mid grey) and colour; a ray slanting steeply
// onto a point just under its top names it too, met at its lid where the ray reaches its height; one slanting low from
// 60 m beside it meets a wall nearer than its middle, and one level from there 1 m over its base a wall, in its slot
// word and colour; a ray that does not fall to the street, or falls where no pack reaches, meets none; and a pick sees
// only the packs it is given. Without a pack, which a wall met stands in the walls' colour as geometry/Roofs.cpp draws
// it: below the eaves, a gable end's triangle and a skillion's wall below its slope. On a synthetic pack, a guessed
// roof's shape while Atlas's guesses are off is met as the plain box the map draws in its place: walls to its top under
// a flat top.
#include "base/arena.hpp"
#include "scene/Pick.hpp"
#include "style/Palette.hpp"
#include "tests/base/require.hpp"
#include "tests/building/fixture.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Store.hpp"
#include "tiles/layers/Buildings.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <filesystem>
#include <format>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

using namespace atlas;

namespace {

namespace tl = atlas::tiles;

/// The buildings' and parts' slot words (their colours' slots in the user's palette).
constexpr std::uint8_t wall_slot = tl::slot_of(tl::building::columns, "wall_slot");
constexpr std::uint8_t roof_slot = tl::slot_of(tl::building::columns, "roof_slot");

/// A building of the pack to aim at: its id, a point inside its footprint (frame metres), its base and its top, and
/// what a ray meeting its lid finds: its roof's slot word (a flat top's with its mid grey) and colour.
struct Target {
    std::int64_t id = 0;
    double x = 0.0;
    double z = 0.0;
    double base = 0.0;
    double top = 0.0;
    std::uint32_t lid_slot = 0;
    std::uint32_t roof_rgb = 0;
};

/// The roof shapes geometry/Walls.cpp gives their height: any other, or one without a height, a flat top.
constexpr std::array<std::string_view, 5> pitched = {"gabled", "pyramidal", "hipped", "skillion", "mansard"};

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
    // every building's and part's wall: its slot word and colour, by its id
    std::unordered_map<std::int64_t, std::pair<std::uint32_t, std::uint32_t>> walls_of;
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
            for (const tl::LayerId layer : {tl::LayerId::building, tl::LayerId::part}) {
                const tl::Layer* w = tile->layer(layer);
                for (std::uint32_t i = 0; w != nullptr && i < w->count; ++i) {
                    walls_of[w->integer(tl::building::id, i)] = {
                        static_cast<std::uint32_t>(w->integer(wall_slot, i)) & 0x3FFU,
                        static_cast<std::uint32_t>(w->integer(tl::building::wall_rgb, i)) & 0xFF'FFFFU};
                }
            }
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
                const std::int64_t id = l->integer(tl::building::id, i);
                std::string_view shape = l->string(tl::building::roof, i).value_or(std::string_view{});
                if (shape == "quadruple_saltbox") shape = "hipped";
                const bool flat = std::ranges::find(pitched, shape) == pitched.end() ||
                                  l->decimal(tl::building::roof_h, i).value_or(0.0) == 0.0;
                const auto roof_word = static_cast<std::uint32_t>(l->integer(roof_slot, i)) & 0x3FFU;
                targets.push_back(
                    {.id = id,
                     .x = cx,
                     .z = cz,
                     .base = l->decimal(tl::building::min, i).value_or(0.0),
                     .top = *top,
                     .lid_slot = flat ? style::flat_roof_slot(roof_word, id) : roof_word,
                     .roof_rgb = static_cast<std::uint32_t>(l->integer(tl::building::roof_rgb, i)) & 0xFF'FFFFU});
            }
        }
    }
    ATLAS_REQUIRE(targets.size() >= 10);
    std::size_t named = 0;
    std::size_t slanted = 0;
    std::size_t walls = 0;
    std::size_t known = 0;
    for (const Target& t : targets) {
        // straight down from 300 m: the building itself (or one standing over its middle), met at a lid
        const view::Vec3 above{t.x, 300.0, t.z};
        const auto down = scene::pick_building(store, packs, ground, above, {0.0, -1.0, 0.0});
        REQUIRE(down.has_value());
        CHECK(down->distance <= 300.0 - t.top + 1e-6);
        CHECK(down->roof);
        if (down->id == t.id) {
            ++named;
            CHECK(down->distance == doctest::Approx(300.0 - t.top).epsilon(1e-9));
            CHECK(down->part == 0);
            CHECK(down->slot == t.lid_slot);
            CHECK(down->rgb == t.roof_rgb);
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
            CHECK(slant->roof);
            CHECK(slant->slot == t.lid_slot);
        }
        // from 60 m east of it, 20 m up, slanting onto its middle 1 m above its base: a building's wall nearer than
        // that middle (in the canal ring, mostly another's, standing between)
        const view::Vec3 side{t.x + 60.0, t.base + 20.0, t.z};
        const view::Vec3 low{t.x - side.x, (t.base + 1.0) - side.y, t.z - side.z};
        const auto beside = scene::pick_building(store, packs, ground, side, low);
        REQUIRE(beside.has_value());
        CHECK(beside->distance < std::sqrt((low.x * low.x) + (low.y * low.y) + (low.z * low.z)));
        // level from 60 m east 1 m over its base, under any eaves: a wall, in its slot word and colour
        const view::Vec3 level{t.x + 60.0, t.base + 1.0, t.z};
        const auto wall = scene::pick_building(store, packs, ground, level, {-1.0, -1e-4, 0.0});
        REQUIRE(wall.has_value());
        if (!wall->roof) ++walls;
        const auto it = walls_of.find(wall->part != 0 ? wall->part : wall->id);
        if (!wall->roof && it != walls_of.end()) {
            ++known;
            CHECK(wall->slot == it->second.first);
            CHECK(wall->rgb == it->second.second);
        }
    }
    MESSAGE(std::format("{} buildings: {} named straight down, {} slanting, {} walls met level ({} of known slots)",
                        targets.size(), named, slanted, walls, known));
    CHECK(walls * 10 >= targets.size() * 9);
    CHECK(known * 10 >= walls * 9);
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

TEST_CASE("pick: a wall met stands in the walls' colour below the eaves, in a gable's triangle and under a skillion") {
    namespace gm = atlas::geometry;
    // 20 m along x (east) by 10 m, 12 m tall with a roof of 4 m: its eaves at 8 m. Sides from each vertex: 0 the north
    // (z = 0) and 2 the south, 20 m long; 1 the east and 3 the west, 10 m.
    const std::array<gm::V2, 4> box{gm::V2{0.0, 0.0}, gm::V2{20.0, 0.0}, gm::V2{20.0, 10.0}, gm::V2{0.0, 10.0}};
    const scene::PickRoof gabled{.shape = "gabled", .roof_h = 4.0};
    for (std::size_t e = 0; e < box.size(); ++e) CHECK(scene::in_walls_colour(box, e, 0.5, 7.9, 12.0, gabled));
    // the ridge along the long sides: over theirs the slopes, the short ones gables, their triangles' apex halfway
    CHECK_FALSE(scene::in_walls_colour(box, 0, 0.5, 8.1, 12.0, gabled));
    CHECK_FALSE(scene::in_walls_colour(box, 2, 0.5, 11.9, 12.0, gabled));
    CHECK(scene::in_walls_colour(box, 1, 0.5, 11.9, 12.0, gabled));
    CHECK(scene::in_walls_colour(box, 3, 0.25, 9.9, 12.0, gabled)); // its edge at a quarter: 8 + 4 / 2
    CHECK_FALSE(scene::in_walls_colour(box, 3, 0.25, 10.1, 12.0, gabled));
    CHECK_FALSE(scene::in_walls_colour(box, 1, 0.9, 9.0, 12.0, gabled)); // 8 + 4 * 0.2 = 8.8
    // across: the long sides the gables
    const scene::PickRoof across{.shape = "gabled", .roof_h = 4.0, .across = true};
    CHECK(scene::in_walls_colour(box, 0, 0.5, 11.9, 12.0, across));
    CHECK_FALSE(scene::in_walls_colour(box, 1, 0.5, 8.1, 12.0, across));
    // hipped, and a quadruple saltbox drawn hipped: no gables
    for (const std::string_view shape : {"hipped", "quadruple_saltbox", "pyramidal", "mansard"}) {
        const scene::PickRoof r{.shape = shape, .roof_h = 4.0};
        CHECK_FALSE(scene::in_walls_colour(box, 1, 0.5, 8.1, 12.0, r));
        CHECK(scene::in_walls_colour(box, 1, 0.5, 7.9, 12.0, r));
    }
    // a flat top, and a pitched shape without a height: walls to the top
    for (const scene::PickRoof& r : {scene::PickRoof{.shape = "flat", .roof_h = 4.0},
                                     scene::PickRoof{.shape = "gabled", .roof_h = 0.0}, scene::PickRoof{}}) {
        CHECK(scene::in_walls_colour(box, 0, 0.5, 11.9, 12.0, r));
        CHECK(scene::in_walls_colour(box, 1, 0.1, 11.9, 12.0, r));
    }
    // a skillion falling south: the north wall to the top, the south one to the eaves, the east one slanting between
    const scene::PickRoof skillion{.shape = "skillion", .roof_h = 4.0, .direction = "S"};
    CHECK(scene::in_walls_colour(box, 0, 0.5, 11.9, 12.0, skillion));
    CHECK_FALSE(scene::in_walls_colour(box, 2, 0.5, 8.1, 12.0, skillion));
    CHECK(scene::in_walls_colour(box, 1, 0.5, 9.9, 12.0, skillion)); // halfway south: 12 - 4 / 2
    CHECK_FALSE(scene::in_walls_colour(box, 1, 0.5, 10.1, 12.0, skillion));
}

TEST_CASE("pick: a guessed roof's shape, drawn as the plain box in its place while Atlas's guesses are off, is met as "
          "walls to its top under a flat top") {
    namespace fx = atlas::test::building;
    // the synthetic tile (tests/building/fixture.hpp) with the gabled house's shape guessed (building 1: 8 m east to
    // west by 14 m, 15 m tall, its eaves at 10 m, its ridge from north to south, so its gables the north and south
    // walls) and the hipped one's mapped (building 2: 14 m tall, its eaves at 10 m), both roofs in the red tiles' slot
    test::TileBytes t = fx::tile();
    const std::vector<std::string> shape{std::string(tl::building::guessed_roof_shape)};
    t.layers[0].columns.push_back({"guessed", tl::Codec::strs,
                                   test::columns::strs(t, {std::nullopt, shape, std::nullopt, std::nullopt,
                                                           std::nullopt, std::nullopt, std::nullopt})});
    t.layers[0].columns.push_back({"roof_slot", tl::Codec::varu, test::columns::varu({0, 80, 80, 0, 0, 0, 0})});
    const std::string path = fx::write_pack(std::move(t), fx::tile_features, "pick-guessed");
    auto pack = test::open_pack(path);
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const tl::RenderHead* head = pack->head();
    ATLAS_REQUIRE(head != nullptr);
    const view::GroundFrame ground = view::frame_of(head->frame);
    const std::uint8_t z = pack->chunks().level(head->levels[0]).front().tile.z;
    tl::Store store;
    ATLAS_REQUIRE(store.add({.id = "synthetic", .kind = tl::PackKind::bundled, .path = path}).has_value());
    const std::array<scene::PickPack, 1> packs{scene::PickPack{.id = "synthetic", .z = z}};
    // a point of the tile in frame metres, from the fixture's south-west corner in grid units
    const auto at = [&](std::int32_t dlon, std::int32_t dlat) {
        const double lat = tl::Frame::degrees(fx::s0 + dlat);
        const double lon = tl::Frame::degrees(fx::w0 + dlon);
        return std::array<double, 2>{ground.x(lat, lon), ground.z(lat, lon)};
    };
    const std::array<double, 2> house = at(3000 + 588, 630); // building 1's middle
    const std::array<double, 2> house_nw = at(3000, 1260);
    const std::array<double, 2> hipped = at(6000 + 735, 540); // building 2's middle
    const std::array<double, 2> hipped_n = at(6000, 1080);
    constexpr std::uint32_t red_tiles = 80;
    const std::uint32_t flat_red_tiles = style::flat_roof_slot(red_tiles, 101);
    REQUIRE(flat_red_tiles != red_tiles);
    for (const bool plain : {false, true}) {
        CAPTURE(plain);
        // straight down onto the gabled house: met at its lid, its roof's colour, its slot word a pitched roof's while
        // its shape is drawn, a flat top's (its mid grey) while the box is
        const auto down =
            scene::pick_building(store, packs, ground, {house[0], 300.0, house[1]}, {0.0, -1.0, 0.0}, plain);
        REQUIRE(down.has_value());
        CHECK(down->id == 101);
        CHECK(down->roof);
        CHECK(down->distance == doctest::Approx(300.0 - 15.0).epsilon(1e-9));
        CHECK(down->rgb == 0x9E5038U);
        CHECK(down->slot == (plain ? flat_red_tiles : red_tiles));
        // level from the north 12 m up, 1 m in from its west wall: over the gable's slope there (10 m + 5 m · 1/4),
        // so its roof while its shape is drawn, and the box's wall while the box is
        const auto gable = scene::pick_building(store, packs, ground, {house_nw[0] + 1.0, 12.0, house_nw[1] - 60.0},
                                                {0.0, -1e-4, 1.0}, plain);
        REQUIRE(gable.has_value());
        CHECK(gable->id == 101);
        CHECK(gable->roof == !plain);
        CHECK(gable->rgb == (plain ? 0xA27A62U : 0x9E5038U));
        CHECK(gable->slot == (plain ? 0U : red_tiles));
        // the hipped house's shape is mapped: drawn either way, its roof over the eaves and its lid a pitched one's
        const auto hip =
            scene::pick_building(store, packs, ground, {hipped[0], 12.0, hipped_n[1] - 60.0}, {0.0, -1e-4, 1.0}, plain);
        REQUIRE(hip.has_value());
        CHECK(hip->id == 102);
        CHECK(hip->roof);
        CHECK(hip->rgb == 0x535C66U);
        const auto lid =
            scene::pick_building(store, packs, ground, {hipped[0], 300.0, hipped[1]}, {0.0, -1.0, 0.0}, plain);
        REQUIRE(lid.has_value());
        CHECK(lid->id == 102);
        CHECK(lid->slot == red_tiles);
    }
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}
