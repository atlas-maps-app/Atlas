// Where a building is (ATLAS_REGIONS_DIR), on the three buildings of the canal ring and Paris that showed the cut gave
// two answers (the engine audit's count 2 of 19 November 2026): the centroid of the outline as mapped, which files a
// building in its tile, lies in one window, and the centroid of the ring the cleaning made of it in the next. The
// window that filed such a building did not take it for its own, so a variant of it would have been made by no window.
// The cut has one point now (load/Cut.cpp's filed_at). Each of the three is filed in one window, that window carries
// its variant, and the neighbour, whose data holds the building too, carries neither. None of the three has a variant
// of its own at the live map's bands, so the test gives each one: its window's data loaded again at 0.3 m, with the
// building 3 m higher there.
#include "base/arena.hpp"
#include "load/Cut.hpp"
#include "load/PackStyle.hpp"
#include "load/Regions.hpp"
#include "load/Window.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/load/query.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Schema.hpp"
#include "tiles/TileWriter.hpp"
#include "tiles/layers/Buildings.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <iterator>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace {

namespace load = atlas::load;
namespace osm = atlas::osm;
namespace tl = atlas::tiles;
using atlas::test::query;

/// The ladder's level the test's variant is at: 0.3 m, the first of the live map's bands.
constexpr int variant_level = 3;

/// One of count 2's buildings: the window it is filed in, and the neighbour its cleaned ring's centroid lies in.
struct Case {
    std::string_view region;
    std::int64_t id;
    bool relation;
    std::uint32_t x;
    std::uint32_t y;
    std::uint32_t next_x;
    std::uint32_t next_y;
};

constexpr std::array<Case, 3> cases = {{
    {.region = "grachten", .id = 268782345, .relation = false, .x = 8415, .y = 5384, .next_x = 8414, .next_y = 5384},
    {.region = "paris", .id = 541055, .relation = true, .x = 8298, .y = 5636, .next_x = 8298, .next_y = 5635},
    {.region = "paris", .id = 562716, .relation = true, .x = 8299, .y = 5636, .next_x = 8298, .next_y = 5636},
}};

/// What a window makes of one building.
struct Held {
    bool in_data = false;     ///< the window's data holds it
    std::size_t filed = 0;    ///< its features in the window's tile
    std::size_t variants = 0; ///< its variants there
};

std::string read_text(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

/// Window `key` as the phone builds it from `doc` (load/Window.cpp's build_window: its box's data, its place's rules),
/// cut with one variant's scene in which building `c` stands 3 m higher.
void window_of(const osm::Document& doc, const Case& c, const tl::TileKey& key, Held& out) {
    const load::WindowPlace place = load::place_of(key);
    const osm::Document mine = query(doc, load::window_box(key));
    std::shared_ptr<const load::Tradition> tradition;
    if (place.tradition) tradition = load::tradition_named(*place.tradition);
    const load::RegionConfig config{.name = place.region,
                                    .frame = place.frame,
                                    .bbox = load::window_box(key),
                                    .tradition = tradition,
                                    .keep_homes = place.new_looks,
                                    .close_up = place.close_up,
                                    .palette = load::region_palette(place.region),
                                    .park = place.park,
                                    .woods = place.woods,
                                    .zoo_woods = place.zoo_woods};
    const auto base = load::load_region(mine, config, 0.0);
    ATLAS_REQUIRE(base.has_value(), c.region);
    const double tolerance = tl::keep::tolerances.at(variant_level);
    auto other = load::load_region(mine, config, tolerance);
    ATLAS_REQUIRE(other.has_value(), c.region);
    std::optional<std::uint32_t> index;
    for (std::uint32_t i = 0; i < base->buildings.size(); ++i) {
        if (base->buildings[i].id == c.id && base->buildings[i].relation == c.relation) index = i;
    }
    if (!index) return;
    out.in_data = true;
    ATLAS_REQUIRE(*index < other->buildings.size() && other->buildings[*index].id == c.id, c.region);
    other->buildings[*index].h += 3.0;
    const std::array<load::CutVariant, 1> variants = {{{.tolerance = tolerance, .scene = &*other}}};
    const auto cut = load::cut_window(*base, place.frame, variants, key);
    ATLAS_REQUIRE(cut.has_value(), cut.error().what);
    for (const tl::TileFill& fill : *cut) {
        if (fill.level() != key.z || fill.x() != key.x || fill.y() != key.y) continue;
        auto raw = tl::encode_tile(fill);
        ATLAS_REQUIRE(raw.has_value(), tl::describe(raw.error()));
        tl::TileDecoder decoder;
        atlas::Arena arena(8U << 20U, atlas::Growth::grows, "filing test, a window's tile");
        const auto tile = decoder.decode(*raw, arena);
        ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
        if (const tl::Layer* l = tile->layer(tl::LayerId::building)) {
            for (std::uint32_t i = 0; i < l->count; ++i) {
                const bool relation = l->string(tl::building::otype, i).value_or(std::string_view{}) == "relation";
                if (l->integer(tl::building::id, i) == c.id && relation == c.relation) ++out.filed;
            }
        }
        if (const tl::Layer* v = tile->layer(tl::LayerId::variant)) {
            for (std::uint32_t i = 0; i < v->count; ++i) {
                if (v->integer(tl::variant::layer, i) == static_cast<std::int64_t>(tl::LayerId::building) &&
                    v->integer(tl::variant::index, i) == *index &&
                    v->integer(tl::variant::tolerance, i) == variant_level) {
                    ++out.variants;
                }
            }
        }
    }
}

} // namespace

TEST_CASE("load: a building whose cleaned ring's centroid lies in the next window is filed in one window, which "
          "carries its variants (ATLAS_REGIONS_DIR)") {
    const std::string regions = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (regions.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: no building's window is looked at here");
        return;
    }
    std::string_view read;
    std::string text;
    std::optional<osm::Document> doc;
    for (const Case& c : cases) {
        if (c.region != read) {
            doc.reset();
            text = read_text(std::filesystem::path(regions) / c.region / "raw.json");
            ATLAS_REQUIRE(!text.empty(), c.region);
            auto parsed = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
            ATLAS_REQUIRE(parsed.has_value(), c.region);
            doc.emplace(std::move(*parsed));
            read = c.region;
        }
        const std::string name = std::format("{}{} of {}", c.relation ? "r" : "w", c.id, c.region);
        CAPTURE(name);
        Held mine;
        Held next;
        window_of(*doc, c, {load::window_level, c.x, c.y}, mine);
        window_of(*doc, c, {load::window_level, c.next_x, c.next_y}, next);
        // both windows' data hold it: it stands on their edge
        CHECK(mine.in_data);
        CHECK(next.in_data);
        // one files it, and has its variant
        CHECK(mine.filed == 1);
        CHECK(mine.variants == 1);
        CHECK(next.filed == 0);
        CHECK(next.variants == 0);
        MESSAGE(name, ": filed in 14/", c.x, "/", c.y, " (", mine.filed, " feature, ", mine.variants,
                " variant), not in 14/", c.next_x, "/", c.next_y, " (", next.filed, ", ", next.variants, ")");
    }
}
