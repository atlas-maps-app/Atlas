// The seams of the canal ring's and Paris's buildings, as the tiles carry them (ATLAS_REGIONS_DIR): each region cut
// whole (the canal ring as the app's bundled pack is cut), its windows as the phone builds them, and its windows'
// cells' levels (L13, L12). The engine audit's three counts of 19 November 2026 (section 7.1), kept as a test:
//   - a houseboat's ring carries no seam (count 1);
//   - a ring's marks (tiles/Schema.hpp's keep::seam_piece, seam_court) are the seams stage P's library finds on it, and
//     at every level of the ladder they are the same seams on the same grid points and pair, so its pieces there are
//     level 0's (count 3: found again by geometry on a simplified ring, they were not);
//   - no piece drawn at a level has an area of 0 or less. A piece that a level leaves no area, or turns inside out, or
//     that is under a level's least face (L13, L12), draws nothing there (geometry/Seams.hpp's split): those are
//     counted and named in the test's line.
// The last case reads count 3's ring of the canal ring from its seed, where the regions' data is not (CI's runners).
#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "geometry/Seams.hpp"
#include "load/Clean.hpp"
#include "load/Cut.hpp"
#include "load/Levels.hpp"
#include "load/Regions.hpp"
#include "load/Window.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/load/query.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Json.hpp"
#include "tiles/LeastFace.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Schema.hpp"
#include "tiles/Simplify.hpp"
#include "tiles/Source.hpp"
#include "tiles/layers/Buildings.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <format>
#include <fstream>
#include <iterator>
#include <map>
#include <memory>
#include <numeric>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

namespace {

namespace gm = atlas::geometry;
namespace load = atlas::load;
namespace osm = atlas::osm;
namespace tl = atlas::tiles;
using atlas::test::query;

std::string read_text(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

/// What a level makes of the rings that carry marks.
struct AtLevel {
    std::size_t unlike = 0; ///< rings whose marks there are not level 0's seams on the same grid points, or do not pair
    std::size_t bad = 0;    ///< drawn pieces whose own edges enclose an area of 0 or less
    std::size_t gone = 0;   ///< pieces a level leaves no area, or turns inside out: they draw nothing there
    std::size_t small = 0;  ///< pieces under the level's least face, with an area: they draw nothing there
    std::vector<std::string> named; ///< the rings of any of these
};

/// The seams of the buildings and parts of the tiles walked: by element, `w` a way, `r` a relation, `p:` a part.
struct Seams {
    std::size_t boats = 0; ///< houseboats stage T draws (not hidden, with a height and a ring)
    std::vector<std::string> boats_seamed;
    std::size_t rings = 0;             ///< rings that carry marks
    std::size_t pieces = 0;            ///< their pieces, as written
    std::vector<std::string> unmarked; ///< rings whose marks are not the seams the library finds on them
    /// Where a footprint is (geometry::corner_mean: its corners' mean, a seam's ends once), of the rings with marks as
    /// their tiles hold them: how far the ring's own mean, which counts a seam's ends twice, lies from it.
    std::size_t leaning = 0; ///< rings whose own mean lies over 1 m from it
    double most_lean = 0;
    std::string most_lean_of;
    /// The street's side of a wall (load/Cut.cpp's street_wall: by the ring's turn), of every ring's walls of 1.5 m²
    /// or more: those where the side away from the ring's own mean, the renders' rule, is the footprint's inside.
    std::size_t walls = 0;
    std::size_t inward = 0;        ///< of them, the walls the ring's own mean puts the street behind
    std::size_t inward_seamed = 0; ///< of those, on rings with marks
    /// By the ladder's level: 0 the ring as its tile holds it, the others what the level leaves of it.
    std::array<AtLevel, tl::keep::tolerances.size()> levels;
};

/// A seam as its mark says it: its first corner's grid point, its second's, its kind.
using Mark = std::tuple<std::int32_t, std::int32_t, std::int32_t, std::int32_t, std::uint8_t>;

[[nodiscard]] std::vector<Mark> marks_of(const tl::RingView& ring, std::span<const std::uint32_t> kept,
                                         std::span<const std::uint8_t> seam) {
    std::vector<Mark> out;
    for (std::size_t i = 0; i < seam.size(); ++i) {
        if (seam[i] == gm::seam_kind::none) continue;
        const std::uint32_t a = kept[i];
        const std::uint32_t b = kept[(i + 1) % kept.size()];
        out.emplace_back(ring.lat[a], ring.lon[a], ring.lat[b], ring.lon[b], seam[i]);
    }
    return out;
}

/// A piece's area by its own edges alone (a seam there and back adds nothing), metres from the ring's first corner.
[[nodiscard]] double area_of(const gm::Piece& piece, std::span<const load::V2> poly) {
    double twice = 0.0;
    for (const std::uint32_t e : piece.edges) {
        const load::V2& a = poly[e];
        const load::V2& b = poly[(e + 1) % poly.size()];
        twice += ((a.x - poly[0].x) * (b.z - poly[0].z)) - ((b.x - poly[0].x) * (a.z - poly[0].z));
    }
    return twice / 2;
}

/// One level's reading of a ring with marks: `kept` its corners there, `whole` its marks and `drawn` its drawn pieces
/// as its tile holds it.
void read_at(const tl::RingView& ring, std::span<const std::uint32_t> kept, const tl::Frame& frame, double least_face,
             const std::vector<Mark>& whole, std::size_t drawn, const std::string& name, AtLevel& out) {
    std::vector<std::uint8_t> seam;
    gm::seams_at(ring, kept, seam);
    std::vector<load::V2> poly;
    poly.reserve(kept.size());
    for (const std::uint32_t v : kept) poly.emplace_back(frame.x(ring.lon[v]), frame.z(ring.lat[v]));
    const gm::Split bare = gm::split(poly, seam, 0.0);
    const gm::Split s = gm::split(poly, seam, least_face);
    const auto count = [](const gm::Split& of) {
        return static_cast<std::size_t>(std::ranges::count_if(of.pieces, [](const gm::Piece& p) { return p.drawn; }));
    };
    const bool unlike = !s.paired || marks_of(ring, kept, seam) != whole;
    std::size_t bad = 0;
    for (const gm::Piece& p : s.pieces) {
        if (p.drawn && s.paired && !(area_of(p, poly) > 0.0)) ++bad;
    }
    const std::size_t gone = drawn - std::min(drawn, count(bare));
    const std::size_t small = count(bare) - std::min(count(bare), count(s));
    out.unlike += unlike ? 1 : 0;
    out.bad += bad;
    out.gone += gone;
    out.small += small;
    if (unlike || bad != 0 || gone != 0 || small != 0) out.named.push_back(name);
}

/// Walks a decoded tile's buildings and parts. A tile of level 14 is read at every level of the ladder; a level's tile
/// (L13, L12), written simplified, as it is, against its level's least face.
void walk(const tl::Tile& tile, const tl::Frame& frame, Seams& out) {
    const double least_face = tl::least_face(tile.header.level);
    const bool ladder = tile.header.level >= load::window_level;
    std::vector<std::uint32_t> every;
    std::vector<std::uint32_t> kept;
    std::vector<std::uint8_t> seam;
    for (const tl::LayerId id : {tl::LayerId::building, tl::LayerId::part}) {
        const tl::Layer* l = tile.layer(id);
        if (l == nullptr) continue;
        for (std::uint32_t i = 0; i < l->count; ++i) {
            const tl::RingView ring = l->ring(tl::building::ring, i);
            if (ring.size() == 0) continue;
            const bool relation = l->string(tl::building::otype, i).value_or(std::string_view{}) == "relation";
            const std::string name = std::format("{}{}{}", id == tl::LayerId::part ? "p:" : "", relation ? "r" : "w",
                                                 l->integer(tl::building::id, i));
            every.resize(ring.size());
            std::iota(every.begin(), every.end(), 0U);
            gm::seams_at(ring, every, seam);
            std::vector<load::V2> poly;
            poly.reserve(ring.size());
            for (std::size_t k = 0; k < ring.size(); ++k) poly.emplace_back(frame.x(ring.lon[k]), frame.z(ring.lat[k]));
            if (ladder) {
                // as written: the marks are the seams stage P's library finds on this very ring, each of its kind
                const std::vector<std::uint8_t> found = load::seam_kinds(poly);
                if (found != seam) out.unmarked.push_back(name);
                const bool drawn =
                    !l->boolean(tl::building::hidden, i).value_or(false) && l->decimal(tl::building::h, i).has_value();
                const bool boat = (static_cast<std::uint64_t>(l->integer(tl::building::flags, i)) &
                                   tl::building::flag_houseboat) != 0;
                if (drawn && boat) {
                    ++out.boats;
                    if (!found.empty() || !seam.empty()) out.boats_seamed.push_back(name);
                }
                // a seam's weight on the ring's own mean, and the walls it puts the street behind
                const load::V2 own = gm::centroid(poly);
                if (!seam.empty()) {
                    const load::V2 once = gm::corner_mean(poly, seam);
                    const double lean = std::hypot(own.x - once.x, own.z - once.z);
                    if (lean > 1.0) ++out.leaning;
                    if (lean > out.most_lean) {
                        out.most_lean = lean;
                        out.most_lean_of = name;
                    }
                }
                const bool turned = gm::signed_area(poly) < 0;
                for (const gm::WallEdge<load::V2> w : gm::walls_of(poly, seam)) {
                    const double ux = w.b.x - w.a.x;
                    const double uz = w.b.z - w.a.z;
                    if ((ux * ux) + (uz * uz) < 1.5) continue;
                    ++out.walls;
                    // the wall's right, and whether the ring's own mean lies on it
                    const bool behind =
                        (uz * (((w.a.x + w.b.x) / 2) - own.x)) - (ux * (((w.a.z + w.b.z) / 2) - own.z)) < 0;
                    if (behind != turned) {
                        ++out.inward;
                        if (!seam.empty()) ++out.inward_seamed;
                    }
                }
            }
            if (seam.empty()) continue;
            ++out.rings;
            const gm::Split first = gm::split(poly, seam, 0.0);
            out.pieces += first.pieces.size();
            const std::vector<Mark> whole = marks_of(ring, every, seam);
            const auto drawn = static_cast<std::size_t>(
                std::ranges::count_if(first.pieces, [](const gm::Piece& p) { return p.drawn; }));
            // as its tile holds it: a level's tile against the pieces it lists, each of which had an area at level 0
            read_at(ring, every, frame, least_face, whole, ladder ? drawn : first.pieces.size(), name, out.levels[0]);
            if (!ladder) continue;
            kept.resize(ring.size());
            for (unsigned level = 1; level < tl::keep::tolerances.size(); ++level) {
                const tl::Simplified s = tl::simplify(ring, level, frame, kept);
                read_at(ring, std::span<const std::uint32_t>(kept).first(s.count), frame, least_face, whole, drawn,
                        name, out.levels.at(level));
            }
        }
    }
}

[[nodiscard]] std::string first_of(const std::vector<std::string>& names) {
    std::string out;
    for (std::size_t i = 0; i < names.size() && i < 8; ++i) out += (i == 0 ? " (" : " ") + names[i];
    return out.empty() ? out : out + (names.size() > 8 ? " …)" : ")");
}

/// A count by level, `code:count` for each level that has any, or `none`.
[[nodiscard]] std::string by_level(const Seams& s, std::size_t AtLevel::* count) {
    std::string out;
    for (std::size_t level = 0; level < s.levels.size(); ++level) {
        if (s.levels.at(level).*count != 0) out += std::format(" {}:{}", level, s.levels.at(level).*count);
    }
    return out.empty() ? " none" : out;
}

void say(const std::string& line) {
    MESSAGE(line);
    (void)std::printf("%s\n", line.c_str()); // NOLINT: the line a run's log keeps
}

/// The line of what was walked, and what holds of it everywhere: the marks are the library's seams, they are the same
/// at every level and pair, and no drawn piece is without an area.
void report(std::string_view region, std::string_view source, const Seams& s) {
    std::vector<std::string> named;
    for (const AtLevel& l : s.levels) named.insert(named.end(), l.named.begin(), l.named.end());
    std::ranges::sort(named);
    named.erase(std::ranges::unique(named).begin(), named.end());
    say(std::format(
        "{}'s seams, {}: {} rings with marks in {} pieces; {} rings whose marks are not the library's "
        "seams{}; by the ladder's level, rings whose marks are not level 0's or do not pair{}, drawn "
        "pieces without an area{}, pieces left no area that draw nothing{}, pieces under the least face{}{}",
        region, source, s.rings, s.pieces, s.unmarked.size(), first_of(s.unmarked), by_level(s, &AtLevel::unlike),
        by_level(s, &AtLevel::bad), by_level(s, &AtLevel::gone), by_level(s, &AtLevel::small), first_of(named)));
    if (s.walls != 0) {
        say(std::format("{}'s footprints, {}: the ring's own corner mean lies over 1 m from its corners' mean with a "
                        "seam's ends counted once in {} of the {} rings with marks, at most {:.1f} m ({}); of {} walls "
                        "the side away from the ring's own mean is the footprint's inside at {}, {} of them on rings "
                        "with marks",
                        region, source, s.leaning, s.rings, s.most_lean, s.most_lean_of, s.walls, s.inward,
                        s.inward_seamed));
    }
    CHECK(s.unmarked.empty());
    for (const AtLevel& l : s.levels) {
        CHECK(l.unlike == 0);
        CHECK(l.bad == 0);
    }
}

} // namespace

// A houseboat's ring carries no seam. Stage T walls a houseboat on its own (geometry/Houseboats.cpp): a hull on every
// edge of its ring and a cabin within it, so a seam there would stand as a wall across the boat. Nothing in the
// cleaning exempts a houseboat: one that overlaps a jetty or another boat, or that a part covers, is cut and seamed as
// any building is (load/Clean.cpp). Of the canal ring's 1,083 and Paris's 3, none.
TEST_CASE("load: the canal ring's and Paris's seams, cut whole, in windows and in their cells' levels: the marks are "
          "the seams at every level, no houseboat carries one (ATLAS_REGIONS_DIR)") {
    const std::string regions = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (regions.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: no seam is looked at here");
        return;
    }
    for (const std::string_view name : {std::string_view{"grachten"}, std::string_view{"paris"}}) {
        const std::filesystem::path folder = std::filesystem::path(regions) / name;
        const std::string text = read_text(folder / "raw.json");
        const std::string json = read_text(folder / "region.json");
        ATLAS_REQUIRE(!text.empty() && !json.empty(), name);
        const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
        ATLAS_REQUIRE(doc.has_value(), name);
        const auto region = tl::read_json(std::as_bytes(std::span(json.data(), json.size())));
        ATLAS_REQUIRE(region.has_value(), name);
        const tl::Value origin = region->root().find("origin").value_or(tl::Value());
        const tl::Value bbox = region->root().find("bbox").value_or(tl::Value());
        ATLAS_REQUIRE(origin.size() == 2 && bbox.size() == 4, name);
        std::array<double, 4> box{};
        for (std::size_t i = 0; i < box.size(); ++i) box.at(i) = bbox.child(i).number().value_or(0);
        const double lat0 = origin.child(0).number().value_or(0);
        // the region's frame, as the packer gives it (regions_test.cpp's)
        const tl::Frame frame{.lat0 = lat0,
                              .lon0 = origin.child(1).number().value_or(0),
                              .kx = 111320 * atlas::pymath::cos(atlas::pymath::radians(lat0)),
                              .kz = 110540};

        // the region cut whole, the canal ring as the app's bundled pack is (new looks: its homes', no tradition,
        // close up)
        Seams whole;
        {
            std::shared_ptr<const load::Tradition> tradition;
            if (const auto t = region->root().find("tradition")) {
                if (const auto named = t->string()) tradition = load::tradition_named(*named);
            }
            load::RegionConfig config{.name = name,
                                      .frame = frame,
                                      .bbox = box,
                                      .tradition = tradition,
                                      .palette = load::region_palette(name)};
            if (name == "grachten") {
                config.keep_homes = true;
                config.tradition.reset();
                config.close_up = true;
            }
            const auto scene = load::load_region(*doc, config);
            ATLAS_REQUIRE(scene.has_value(), name);
            const auto cut = load::cut_region(*scene, frame, {});
            ATLAS_REQUIRE(cut.has_value(), cut.error().what);
            for (const tl::TileFill& t : *cut) {
                if (t.level() != load::window_level) continue;
                auto raw = tl::encode_tile(t);
                ATLAS_REQUIRE(raw.has_value(), tl::describe(raw.error()));
                tl::TileDecoder decoder;
                atlas::Arena arena(8U << 20U, atlas::Growth::grows, "seams test, a region's tile");
                const auto tile = decoder.decode(*raw, arena);
                ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
                walk(*tile, frame, whole);
            }
        }

        // its windows, each as the phone builds it, and what a pack build's line counts of each
        Seams windows;
        load::SeamMarks counted;
        std::map<std::pair<std::uint32_t, std::uint32_t>, std::vector<std::vector<std::byte>>> cells;
        const auto at = [](double degrees) { return static_cast<std::int32_t>(std::lround(degrees * 1e7)); };
        const tl::TileKey sw = tl::tile_of(load::window_level, at(box[0]), at(box[1]));
        const tl::TileKey ne = tl::tile_of(load::window_level, at(box[2]), at(box[3]));
        for (std::uint32_t y = ne.y; y <= sw.y; ++y) {
            for (std::uint32_t x = sw.x; x <= ne.x; ++x) {
                const tl::TileKey key{load::window_level, x, y};
                const load::WindowPlace place = load::place_of(key);
                auto built = load::build_window(query(*doc, load::window_box(key)), tl::Sha256Digest{}, key, place);
                ATLAS_REQUIRE(built.has_value(), built.error().what);
                const auto held = load::buildings_of(built->pack);
                ATLAS_REQUIRE(held.has_value(), held.error().what);
                counted.piece += held->seams.piece;
                counted.court += held->seams.court;
                counted.unpaired += held->seams.unpaired;
                auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(built->pack));
                ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
                if (pack->chunks().chunks().empty()) continue;
                {
                    tl::Zstd zstd;
                    tl::TileDecoder decoder;
                    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "seams test, a window's tile");
                    std::vector<std::byte> raw;
                    std::vector<std::byte> scratch;
                    const auto tile = pack->decode(pack->chunks().chunks()[0], zstd, decoder, arena, raw, scratch);
                    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
                    walk(*tile, place.frame, windows);
                }
                const unsigned down = load::window_level - load::frame_cell_level;
                cells[{x >> down, y >> down}].push_back(std::move(built->pack));
            }
        }

        // its windows' cells' levels: the L13 and L12 tiles a pack build writes beside them
        std::array<Seams, 2> levels; // L13, L12
        std::array<load::SeamMarks, 2> written;
        for (const auto& [cell, packs] : cells) {
            std::vector<std::span<const std::byte>> spans;
            spans.reserve(packs.size());
            for (const std::vector<std::byte>& p : packs) spans.emplace_back(p);
            const auto built = load::build_levels(spans);
            ATLAS_REQUIRE(built.has_value(), built.error().what);
            for (const load::BuiltLevel& level : *built) {
                const std::size_t which = level.key.z == load::coarse_level ? 0 : 1;
                written.at(which).piece += level.seams.piece;
                written.at(which).court += level.seams.court;
                written.at(which).unpaired += level.seams.unpaired;
                auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(level.pack));
                ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
                ATLAS_REQUIRE(pack->head() != nullptr);
                tl::Zstd zstd;
                tl::TileDecoder decoder;
                std::vector<std::byte> raw;
                std::vector<std::byte> scratch;
                for (const tl::ChunkRef& c : pack->chunks().chunks()) {
                    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "seams test, a level's tile");
                    const auto tile = pack->decode(c, zstd, decoder, arena, raw, scratch);
                    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
                    walk(*tile, pack->head()->frame, levels.at(which));
                }
            }
        }

        say(std::format(
            "{}'s houseboats: {} drawn cut whole, {} with a seam{}; {} drawn in its windows, {} with a seam{}", name,
            whole.boats, whole.boats_seamed.size(), first_of(whole.boats_seamed), windows.boats,
            windows.boats_seamed.size(), first_of(windows.boats_seamed)));
        CHECK(whole.boats > 0);
        CHECK(whole.boats_seamed.empty());
        CHECK(windows.boats == whole.boats);
        CHECK(windows.boats_seamed.empty());
        report(name, "cut whole", whole);
        report(name, "in its windows", windows);
        say(std::format(
            "{}'s windows' tiles: {} seams between pieces and {} to courtyards marked, {} rings whose marks "
            "do not pair",
            name, counted.piece / 2, counted.court / 2, counted.unpaired));
        CHECK(counted.unpaired == 0);
        CHECK(whole.rings > 0);
        CHECK(windows.rings > 0);
        for (std::size_t which = 0; which < levels.size(); ++which) {
            const Seams& l = levels.at(which);
            report(name, which == 0 ? "in its L13 tiles" : "in its L12 tiles", l);
            say(std::format("{}'s L{} tiles: {} seams between pieces and {} to courtyards marked, {} rings whose marks "
                            "do not pair or are too few to carry them; of {} pieces listed, {} left no area and {} "
                            "under the least face of {:.2f} m² draw nothing",
                            name, which == 0 ? 13 : 12, written.at(which).piece / 2, written.at(which).court / 2,
                            written.at(which).unpaired, l.pieces, l.levels[0].gone, l.levels[0].small,
                            tl::least_face(which == 0 ? load::coarse_level : load::far_level)));
            CHECK(written.at(which).unpaired == 0);
        }
    }
}

// Count 3's ring of the canal ring, where the regions' data is not: the Pontsteiger (relation 13123696 with its part,
// way 995816141; tests/load/data/pontsteiger.json, the Netherlands' seed 8414-5382). Found again by geometry on a
// band's simplified ring, its seams were not the ring's, and a piece drawn there had no area. Its window as the phone
// builds it, read at every level of the ladder, and its cell's levels.
TEST_CASE("load: the Pontsteiger's seams from its seed, in its window and in its cell's levels: the marks are the "
          "seams at every level, no drawn piece is without an area") {
    const std::string text = read_text(std::filesystem::path(ATLAS_TESTS_DIR) / "load" / "data" / "pontsteiger.json");
    ATLAS_REQUIRE(!text.empty());
    const tl::TileKey key{load::window_level, 8414, 5382};
    const load::WindowPlace place = load::place_of(key);
    const auto built = load::build_window(std::as_bytes(std::span(text.data(), text.size())), key, place);
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    const auto held = load::buildings_of(built->pack);
    ATLAS_REQUIRE(held.has_value(), held.error().what);
    CHECK(held->seams.piece + held->seams.court > 0);
    CHECK(held->seams.unpaired == 0);
    // a pack's tiles, each walked in the pack's own frame
    const auto walked = [](std::span<const std::byte> bytes, Seams& out) {
        auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(bytes));
        ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
        ATLAS_REQUIRE(pack->head() != nullptr);
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        for (const tl::ChunkRef& c : pack->chunks().chunks()) {
            atlas::Arena arena(8U << 20U, atlas::Growth::grows, "seams test, the Pontsteiger's tile");
            const auto tile = pack->decode(c, zstd, decoder, arena, raw, scratch);
            ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
            walk(*tile, pack->head()->frame, out);
        }
    };
    Seams window;
    walked(built->pack, window);
    CHECK(window.rings > 0);
    report("the Pontsteiger", "in its window", window);
    const std::array<std::span<const std::byte>, 1> packs{std::span<const std::byte>(built->pack)};
    const auto levels = load::build_levels(packs);
    ATLAS_REQUIRE(levels.has_value(), levels.error().what);
    for (const load::BuiltLevel& level : *levels) {
        Seams at;
        walked(level.pack, at);
        report("the Pontsteiger", level.key.z == load::coarse_level ? "in its L13 tile" : "in its L12 tile", at);
        CHECK(level.seams.unpaired == 0);
    }
}
