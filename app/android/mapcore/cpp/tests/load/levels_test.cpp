// A frame cell's levels (load/Levels.hpp, ENGINE.md one pack per country, step 7), derived from its windows' packs
// alone. Two windows of the Westerkerk's frame cell: the Westerkerk's own (14/8414/5384) with three houses, a hall, a
// tall tower, a church whose tower and nave are parts of their own, a street and a park; and its east neighbour
// (14/8415/5384) with a house:
//   - their L13 tile holds every building and part, each in the cell of the quarter of its place its L14 cell lies in;
//     their L12 tile only the large ones (the hall, the tower, the church with both its parts, the nave below 30 m),
//     each in its place's cell; both hold the street and the park;
//   - a level's tile is the same bytes from the same windows in any order;
//   - the store takes the levels' tiles into the cell's pack beside its windows, its levels 14, 13 and 12;
//   - windows that hold nothing to draw give no levels; windows of two frame cells are refused.
#include "base/arena.hpp"
#include "geometry/Walls.hpp"
#include "load/Levels.hpp"
#include "load/Window.hpp"
#include "scene/Cells.hpp"
#include "sun/Samples.hpp"
#include "tests/base/require.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "tiles/Store.hpp"
#include "tiles/layers/Buildings.hpp"
#include "twin/building/BuildingShader.hpp"
#include "twin/building/BuildingTile.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <map>
#include <memory>
#include <span>
#include <string>
#include <vector>

namespace {

namespace gm = atlas::geometry;
namespace load = atlas::load;
namespace tl = atlas::tiles;

/// A pack's one tile, decoded and kept with what its views read.
struct Decoded {
    explicit Decoded(tl::Pack p) : pack(std::move(p)) {}
    tl::Pack pack;
    atlas::Arena arena{std::size_t{1} << 20U, atlas::Growth::grows, "levels test, a tile"};
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    tl::Tile tile{};
};

std::unique_ptr<Decoded> decoded(const std::vector<std::byte>& bytes) {
    auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(bytes));
    ATLAS_REQUIRE_OR_RETURN(nullptr, pack.has_value(), tl::describe(pack.error()));
    ATLAS_REQUIRE_OR_RETURN(nullptr, pack->chunks().chunks().size() == 1);
    auto out = std::make_unique<Decoded>(std::move(*pack));
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    auto tile = out->pack.decode(out->pack.chunks().chunks()[0], zstd, decoder, out->arena, out->raw, out->scratch);
    ATLAS_REQUIRE_OR_RETURN(nullptr, tile.has_value(), tl::describe(tile.error()));
    out->tile = *tile;
    return out;
}

/// The features of layer `id` of `t` by their OSM ids, each with the cell it lies in.
std::map<std::int64_t, std::uint32_t> cells_of(const tl::Tile& t, tl::LayerId id) {
    std::map<std::int64_t, std::uint32_t> out;
    const tl::Layer* l = t.layer(id);
    if (l == nullptr || l->cell_first.empty()) return out;
    for (std::uint32_t c = 0; c + 1 < l->cell_first.size(); ++c) {
        for (std::uint32_t i = l->cell_first[c]; i < l->cell_first[c + 1]; ++i)
            out[l->integer(tl::building::id, i)] = c;
    }
    return out;
}

std::size_t count_of(const tl::Tile& t, tl::LayerId id) {
    const tl::Layer* l = t.layer(id);
    return l == nullptr ? 0 : l->count;
}

/// A closed way's geometry: a box of `dlat` by `dlon` degrees from its south-west corner.
std::string box(double lat, double lon, double dlat, double dlon) {
    return std::format(R"([{{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}},
    {{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}}])",
                       lat, lon, lat, lon + dlon, lat + dlat, lon + dlon, lat + dlat, lon, lat, lon);
}

std::string way(int id, const std::string& tags, const std::string& geometry) {
    return std::format(R"({{"type": "way", "id": {}, "tags": {{{}}}, "geometry": {}}})", id, tags, geometry);
}

std::string answer(const std::vector<std::string>& elements) {
    std::string out = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-30T08:00:00Z"}, "elements": [
)";
    for (std::size_t i = 0; i < elements.size(); ++i) out += (i == 0 ? "" : ",\n") + elements[i];
    return out + "]}";
}

std::span<const std::byte> bytes(const std::string& s) { return std::as_bytes(std::span(s.data(), s.size())); }

std::string written(const std::vector<std::byte>& pack, const std::string& name) {
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "levels";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / name;
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(pack.size()));
    return file.string();
}

/// The L14 cell (row · 8 + column) of a window's tile each of its layer's features lies in, by id.
std::map<std::int64_t, std::uint32_t> window_cells(const std::vector<std::byte>& pack, tl::LayerId id) {
    const auto d = decoded(pack);
    if (!d) return {};
    return cells_of(d->tile, id);
}

} // namespace

TEST_CASE(
    "load: a frame cell's levels: L13 every building in its place's quarter, L12 the large ones with their parts") {
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const tl::TileKey east(14, k.x + 1, k.y);
    REQUIRE(k.x == 8414);
    REQUIRE(k.y == 5384);
    // the Westerkerk's window (52.361 to 52.375 north, 4.878 to 4.900 east)
    const std::string west_answer = answer({
        way(10, R"("building": "house")", box(52.3700, 4.8826, 0.0001, 0.00015)),
        way(11, R"("building": "house")", box(52.3700, 4.8830, 0.0001, 0.00015)),
        way(12, R"("building": "house")", box(52.3650, 4.8950, 0.0001, 0.00015)),
        // a hall of about 3,600 m², and a tower of 45 m on 75 m²
        way(20, R"("building": "warehouse")", box(52.3640, 4.8800, 0.0006, 0.0008)),
        way(21, R"("building": "yes", "height": "45")", box(52.3680, 4.8960, 0.0001, 0.0001)),
        // a church of about 900 m² with no height of its own: its tower of 60 m, its nave of 12 m, parts in it
        way(30, R"("building": "church")", box(52.3690, 4.8850, 0.0003, 0.0004)),
        way(31, R"("building:part": "yes", "height": "60")", box(52.3691, 4.8851, 0.0001, 0.0001)),
        way(32, R"("building:part": "yes", "height": "12")", box(52.3691, 4.8852, 0.0002, 0.00018)),
        way(40, R"("highway": "residential", "name": "Teststraat")",
            R"([{"lat": 52.3705, "lon": 4.8800}, {"lat": 52.3705, "lon": 4.8900}])"),
        way(41, R"("leisure": "park")", box(52.3620, 4.8900, 0.002, 0.003)),
    });
    const std::string east_answer = answer({way(50, R"("building": "house")", box(52.3700, 4.9050, 0.0001, 0.00015))});
    const auto west = load::build_window(bytes(west_answer), k, load::place_of(k));
    ATLAS_REQUIRE(west.has_value(), west.error().what);
    const auto beside = load::build_window(bytes(east_answer), east, load::place_of(east));
    ATLAS_REQUIRE(beside.has_value(), beside.error().what);
    const std::array<std::span<const std::byte>, 2> windows{std::span<const std::byte>(west->pack),
                                                            std::span<const std::byte>(beside->pack)};
    const auto built = load::build_levels(windows);
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    // one tile of each level holds both places: the L13 tile first, then the L12
    ATLAS_REQUIRE(built->size() == 2);
    const load::BuiltLevel& coarse = (*built)[0];
    const load::BuiltLevel& far = (*built)[1];
    CHECK(coarse.key.packed() == tl::TileKey(13, k.x / 2, k.y / 2).packed());
    CHECK(far.key.packed() == tl::TileKey(12, k.x / 4, k.y / 4).packed());
    const auto c = decoded(coarse.pack);
    const auto f = decoded(far.pack);
    ATLAS_REQUIRE(c != nullptr && f != nullptr);
    CHECK(c->tile.header.level == 13);
    CHECK(f->tile.header.level == 12);
    CHECK(coarse.features == c->pack.chunks().chunks()[0].count);
    CHECK(far.features == f->pack.chunks().chunks()[0].count);

    // L13: every building and part, each in the cell of its place's quarter that holds its L14 cell
    const auto west_buildings = window_cells(west->pack, tl::LayerId::building);
    const auto west_parts = window_cells(west->pack, tl::LayerId::part);
    const auto east_buildings = window_cells(beside->pack, tl::LayerId::building);
    REQUIRE(west_buildings.size() == 6);
    REQUIRE(west_parts.size() == 2);
    REQUIRE(east_buildings.size() == 1);
    const auto quarter_cell = [](std::uint32_t l14, std::uint32_t dx) {
        return ((l14 / 8 / 4) * 4) + (2 * dx) + ((l14 % 8) / 4);
    };
    std::map<std::int64_t, std::uint32_t> want_coarse;
    for (const auto& [id, cell] : west_buildings) want_coarse[id] = quarter_cell(cell, 0);
    for (const auto& [id, cell] : east_buildings) want_coarse[id] = quarter_cell(cell, 1);
    CHECK(cells_of(c->tile, tl::LayerId::building) == want_coarse);
    std::map<std::int64_t, std::uint32_t> want_parts;
    for (const auto& [id, cell] : west_parts) want_parts[id] = quarter_cell(cell, 0);
    CHECK(cells_of(c->tile, tl::LayerId::part) == want_parts);
    CHECK(coarse.buildings == 9);
    // L12: the hall, the tower and the church with both its parts, all in the Westerkerk's place's cell (row 0,
    // column 2 of the L12 tile's sixteen places); no house
    const std::map<std::int64_t, std::uint32_t> want_far{{20, 2}, {21, 2}, {30, 2}};
    CHECK(cells_of(f->tile, tl::LayerId::building) == want_far);
    const std::map<std::int64_t, std::uint32_t> want_far_parts{{31, 2}, {32, 2}};
    CHECK(cells_of(f->tile, tl::LayerId::part) == want_far_parts);
    CHECK(far.buildings == 5);
    // A level's walls are lean: one cell each, drawn at any distance, without the near cells (drawn within 460 m)
    // nor the windowed walls (within 760 m at most), and its buildings without the columns only those read (their
    // storeys, their no-windows mark, their walls' material, their look's kind); the window's walls have them all.
    const auto wd = decoded(west->pack);
    ATLAS_REQUIRE(wd != nullptr);
    const auto walls = [](const Decoded& d) {
        return gm::build_buildings({.tile = &d.tile, .head = d.pack.head(), .level = 0, .region = {}, .kind = 0});
    };
    const auto near_cells = [](const gm::TileBuildings& g) {
        return std::ranges::count_if(g.edged,
                                     [](const gm::Prim& p) { return p.kind == gm::PrimKind::cell && p.ncol > 0; });
    };
    const gm::TileBuildings window_walls = walls(*wd);
    CHECK(near_cells(window_walls) > 0);
    CHECK(window_walls.windows > 0);
    CHECK(!window_walls.window_walls.empty());
    const tl::Layer* window_layer = wd->tile.layer(tl::LayerId::building);
    ATLAS_REQUIRE(window_layer != nullptr);
    CHECK(window_layer->column(tl::building::levels).present);
    for (const Decoded* level : {c.get(), f.get()}) {
        const gm::TileBuildings g = walls(*level);
        CHECK(!g.edged.empty());
        CHECK(near_cells(g) == 0);
        CHECK(std::ranges::all_of(g.edged, [](const gm::Prim& p) {
            return p.kind != gm::PrimKind::cell || (p.flags & gm::prim_flag::any) == gm::prim_flag::any;
        }));
        CHECK(g.window_walls.empty());
        CHECK(g.windows == 0);
        CHECK(g.kit_walls.empty());
        const tl::Layer* layer = level->tile.layer(tl::LayerId::building);
        ATLAS_REQUIRE(layer != nullptr);
        for (const std::uint8_t slot :
             {tl::building::levels, tl::building::no_win, tl::building::mat_draw, tl::building::kind}) {
            CHECK_FALSE(layer->column(slot).present);
        }
        MESSAGE("L" << int{level->tile.header.level} << ": " << g.edged.size() << " walls' primitives, the window's "
                    << window_walls.edged.size() << " (" << near_cells(window_walls) << " near cells, "
                    << window_walls.windows << " windows)");
    }
    // both hold the street and the park
    CHECK(count_of(c->tile, tl::LayerId::road) == count_of(f->tile, tl::LayerId::road));
    CHECK(count_of(f->tile, tl::LayerId::road) >= 1);
    CHECK(count_of(f->tile, tl::LayerId::ground) >= 1);
    CHECK(count_of(c->tile, tl::LayerId::ground) >= count_of(f->tile, tl::LayerId::ground));
    // each names its tile and the windows' versions, in the cell's frame
    CHECK(c->pack.head()->region == "13/4207/2692");
    CHECK(f->pack.head()->region == "12/2103/1346");
    CHECK(c->pack.head()->engine_version == load::stage_p_version);
    CHECK(c->pack.head()->frame.lat0 == load::place_of(k).frame.lat0);
    CHECK(f->pack.head()->frame.lon0 == load::place_of(k).frame.lon0);

    // the same bytes from the same windows in another order
    const std::array<std::span<const std::byte>, 2> swapped{windows[1], windows[0]};
    const auto again = load::build_levels(swapped);
    ATLAS_REQUIRE(again.has_value(), again.error().what);
    ATLAS_REQUIRE(again->size() == 2);
    CHECK(std::ranges::equal((*again)[0].pack, coarse.pack));
    CHECK(std::ranges::equal((*again)[1].pack, far.pack));

    // the store takes them into the cell's pack beside the windows: its levels 14, 13 and 12, each tile found
    tl::Store store;
    const auto add = [&store](const std::string& name, const std::vector<std::byte>& pack) {
        std::string file = name;
        std::ranges::replace(file, '/', '-');
        return store.add(
            {.id = "window/" + name, .kind = tl::PackKind::window, .path = written(pack, file + ".atlas")});
    };
    ATLAS_REQUIRE(add("14/8414/5384", west->pack).has_value());
    ATLAS_REQUIRE(add("14/8415/5384", beside->pack).has_value());
    ATLAS_REQUIRE(add("13/4207/2692", coarse.pack).has_value());
    ATLAS_REQUIRE(add("12/2103/1346", far.pack).has_value());
    ATLAS_REQUIRE(store.packs().size() == 1);
    const std::array<std::uint8_t, 3> levels{14, 13, 12};
    CHECK(std::ranges::equal(store.packs()[0]->pack.head()->levels, levels));
    CHECK(store.find(k).size() == 1);
    CHECK(store.find(coarse.key).size() == 1);
    CHECK(store.find(far.key).size() == 1);
    CHECK(store.holder_of("window/12/2103/1346") == tl::Store::cell_id(tl::TileKey(10, k.x >> 4U, k.y >> 4U)));
}

TEST_CASE("load: a level's sun samples are its lean walls', the places another tile holds leave its solids and its "
          "marches, and its faces are lit by their bits where its tile has them, else by day") {
    namespace sun = atlas::sun;
    namespace sh = atlas::shaders;
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const tl::TileKey east(14, k.x + 1, k.y);
    // the Westerkerk's window: a tower, a gabled house beside it and a long house of three floors (its walls of ncol
    // cells at L14); its east neighbour: a house
    const std::string west_answer = answer({
        way(10, R"("building": "yes", "height": "60")", box(52.3700, 4.8826, 0.0001, 0.00015)),
        way(11, R"("building": "house", "height": "8", "roof:shape": "gabled", "roof:height": "3")",
            box(52.3700, 4.8830, 0.0001, 0.00015)),
        way(12, R"("building": "house", "building:levels": "3")", box(52.3650, 4.8950, 0.0002, 0.0004)),
    });
    const std::string east_answer =
        answer({way(50, R"("building": "house", "building:levels": "2")", box(52.3700, 4.9050, 0.0001, 0.00015))});
    const auto west = load::build_window(bytes(west_answer), k, load::place_of(k));
    ATLAS_REQUIRE(west.has_value(), west.error().what);
    const auto beside = load::build_window(bytes(east_answer), east, load::place_of(east));
    ATLAS_REQUIRE(beside.has_value(), beside.error().what);
    const std::array<std::span<const std::byte>, 2> windows{std::span<const std::byte>(west->pack),
                                                            std::span<const std::byte>(beside->pack)};
    const auto built = load::build_levels(windows);
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    ATLAS_REQUIRE(built->size() == 2);
    const auto c = decoded((*built)[0].pack);
    const auto f = decoded((*built)[1].pack);
    const auto wd = decoded(west->pack);
    ATLAS_REQUIRE(c != nullptr && f != nullptr && wd != nullptr);
    const auto walls = [](const Decoded& d) {
        return gm::build_buildings({.tile = &d.tile, .head = d.pack.head(), .level = 0, .region = {}, .kind = 0});
    };
    const auto samples = [](const Decoded& d, std::uint64_t live = sun::all_cells) {
        return sun::make_tile(d.tile, *d.pack.head(), sun::Region{}, 0, {}, live);
    };
    const auto count = [](const sun::TileSamples& s, sun::Kind kind) { return std::ranges::count(s.kind, kind); };
    // The window's samples have its walls' cells and its windows; a level's, its walls' one cell each alone, every
    // primitive's sample the one K8 gives the same face (kind, record, edge), the buildings' samples ending where the
    // crowns' begin.
    const sun::SunTile window_samples = samples(*wd);
    CHECK(count(window_samples.samples, sun::Kind::cell) > 0);
    CHECK(count(window_samples.samples, sun::Kind::window) > 0);
    for (const Decoded* level : {c.get(), f.get()}) {
        const gm::TileBuildings g = walls(*level);
        const sun::SunTile st = samples(*level);
        const sun::TileSamples& s = st.samples;
        CHECK(g.samples == s.crowns_first);
        CHECK(s.windows_first == s.size());
        CHECK(count(s, sun::Kind::cell) == 0);
        CHECK(count(s, sun::Kind::band) == 0);
        CHECK(count(s, sun::Kind::window) == 0);
        std::size_t sampled = 0;
        std::size_t wrong = 0;
        for (const auto* list : {&g.edged, &g.plain}) {
            for (const gm::Prim& p : *list) {
                if (p.sample == gm::no_sample) continue;
                ++sampled;
                const gm::BuildingItem& item = g.items[p.item];
                const bool walled = p.kind == gm::PrimKind::cell;
                const bool same = p.sample < s.size() && (!walled || s.kind[p.sample] == sun::Kind::wall) &&
                                  s.source[p.sample] == static_cast<sun::Source>(item.source) &&
                                  s.owner[p.sample] == item.record && s.edge[p.sample] == p.edge;
                if (!same) ++wrong;
            }
        }
        CHECK(sampled > 0);
        CHECK(wrong == 0);
        // its faces lit by their samples where its tile has bits, else by day; none drawn as if in the shade
        atlas::passes::BuildingTile layout;
        (void)atlas::passes::heap_words(g, 0.0, 0.0, layout);
        CHECK(!layout.lights.empty());
        CHECK(std::ranges::all_of(layout.lights, [](const atlas::passes::BuildingLight& l) {
            return (l.flags & (sh::BUILDING_FACE_LEVEL | sh::BUILDING_FACE_DAY)) != 0U;
        }));
    }
    const gm::TileBuildings window_walls = walls(*wd);
    atlas::passes::BuildingTile window_layout;
    (void)atlas::passes::heap_words(window_walls, 0.0, 0.0, window_layout);
    CHECK(std::ranges::none_of(window_layout.lights, [](const atlas::passes::BuildingLight& l) {
        return (l.flags & sh::BUILDING_FACE_LEVEL) != 0U;
    }));
    const atlas::hlsl::uint2 none{0U, 0U};
    const atlas::hlsl::uint2 lit{1U << 3U, 1U << 3U};
    CHECK(sh::building_sun_amount(sh::BUILDING_FACE_LEVEL, 3, none, 0, 1.0F) == 1.0F); // no bits: by day
    CHECK(sh::building_sun_amount(sh::BUILDING_FACE_LEVEL, 3, none, 1, 1.0F) == 0.0F); // its bit: in the shade
    CHECK(sh::building_sun_amount(sh::BUILDING_FACE_LEVEL, 3, lit, 1, 1.0F) == 1.0F);
    CHECK(sh::building_sun_amount(0, 3, none, 0, 1.0F) == 0.0F); // a window's face before its bits

    // The L13 taking the east place alone (the west one drawn from its window): the east house its one solid, every
    // sample of the west place's buildings unmarched, the east house's marched leaving its own solid.
    const std::uint64_t live = atlas::scene::cells_over(4, 1, east.x, east.y);
    CHECK(live == 0xCCU);
    const sun::SunTile taken = samples(*c, live);
    const tl::Layer* layer = c->tile.layer(tl::LayerId::building);
    ATLAS_REQUIRE(layer != nullptr);
    REQUIRE(taken.solids.size() == 1);
    CHECK(layer->integer(tl::building::id, taken.solids.record[0]) == 50);
    std::size_t marched = 0;
    std::size_t unmarched = 0;
    const sun::TileSamples& ts = taken.samples;
    for (std::uint32_t i = 0; i < ts.crowns_first; ++i) {
        const bool mine = layer->integer(tl::building::id, ts.owner[i]) == 50;
        if (ts.skip[i] == sun::no_march) {
            ++unmarched;
            CHECK_FALSE(mine);
        } else {
            ++marched;
            CHECK(mine);
            CHECK(ts.skip[i] == 0);
        }
    }
    CHECK(marched > 0);
    CHECK(unmarched > 0);
    CHECK(ts.size() == samples(*c).samples.size()); // the same samples, in the same order
}

TEST_CASE("load: windows that hold nothing give no levels; windows of two frame cells are refused") {
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const auto empty = load::build_window(bytes(answer({})), k, load::place_of(k));
    ATLAS_REQUIRE(empty.has_value(), empty.error().what);
    const std::array<std::span<const std::byte>, 1> nothing{std::span<const std::byte>(empty->pack)};
    const auto none = load::build_levels(nothing);
    ATLAS_REQUIRE(none.has_value(), none.error().what);
    CHECK(none->empty());
    CHECK(load::build_levels({})->empty());
    // a house in the Westerkerk's window and one in a window of the next frame cell east (16 places on)
    const tl::TileKey far_east(14, k.x + 16, k.y);
    const auto here =
        load::build_window(bytes(answer({way(10, R"("building": "house")", box(52.3700, 4.8840, 0.0001, 0.00015))})), k,
                           load::place_of(k));
    ATLAS_REQUIRE(here.has_value(), here.error().what);
    const auto there =
        load::build_window(bytes(answer({way(11, R"("building": "house")", box(52.3700, 5.2365, 0.0001, 0.00015))})),
                           far_east, load::place_of(far_east));
    ATLAS_REQUIRE(there.has_value(), there.error().what);
    const std::array<std::span<const std::byte>, 2> two{std::span<const std::byte>(here->pack),
                                                        std::span<const std::byte>(there->pack)};
    const auto refused = load::build_levels(two);
    ATLAS_REQUIRE(!refused.has_value());
    CHECK(refused.error().what == "windows of two frame cells");
}
