// A seam's marks (tiles/Schema.hpp's keep::seam_piece and seam_court) from the writer to the core's reader: a window
// built from a document made by hand, with a block round a courtyard, an outline its part cuts in two, and one whose
// part leaves it an annex of 4 m². The marks come back as stage P found the seams on the ring it wrote, they are the
// same seams on the same grid points at every level of the ladder, and the levels written simplified (L13, L12) hold
// them too. The port's reader has the same case beside L0DecodeTest (mapcorecheck's SeamMarksTest). The document:
// tests/load/seamed_document.hpp.
#include "base/arena.hpp"
#include "geometry/Seams.hpp"
#include "load/Clean.hpp"
#include "load/Levels.hpp"
#include "load/Window.hpp"
#include "tests/base/require.hpp"
#include "tests/load/seamed_document.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"
#include "tiles/LeastFace.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Schema.hpp"
#include "tiles/Simplify.hpp"
#include "tiles/Source.hpp"
#include "tiles/layers/Buildings.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <memory>
#include <numeric>
#include <span>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

namespace {

namespace gm = atlas::geometry;
namespace kind = atlas::geometry::seam_kind;
namespace load = atlas::load;
namespace tl = atlas::tiles;

using atlas::test::seamed::annex_id;
using atlas::test::seamed::block_id;
using atlas::test::seamed::cut_id;
using atlas::test::seamed::document;
constexpr std::uint8_t window_z = 14;

/// A building's or a part's ring as a tile holds it.
struct Ring {
    std::int64_t id = 0;
    bool part = false;
    std::vector<std::int32_t> lat;
    std::vector<std::int32_t> lon;
    std::vector<std::uint8_t> keep;
    bool has_keep = false;

    [[nodiscard]] tl::RingView view() const { return {.lat = lat, .lon = lon, .keep = keep, .has_keep = has_keep}; }
};

struct Held {
    tl::Frame frame;
    std::vector<Ring> rings;

    [[nodiscard]] const Ring* building(std::int64_t id) const {
        const auto it = std::ranges::find_if(rings, [&](const Ring& r) { return !r.part && r.id == id; });
        return it == rings.end() ? nullptr : &*it;
    }
};

/// The buildings' and parts' rings of a pack's tiles of level `z`.
[[nodiscard]] Held rings_of(std::span<const std::byte> bytes, std::uint8_t z) {
    Held out;
    auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(bytes));
    ATLAS_REQUIRE_OR_RETURN(out, pack.has_value(), tl::describe(pack.error()));
    ATLAS_REQUIRE_OR_RETURN(out, pack->head() != nullptr);
    out.frame = pack->head()->frame;
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    for (const tl::ChunkRef& c : pack->chunks().chunks()) {
        if (c.tile.z != z) continue;
        atlas::Arena arena(std::size_t{1} << 22U, atlas::Growth::grows, "marks test");
        const auto tile = pack->decode(c, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE_OR_RETURN(out, tile.has_value(), tl::describe(tile.error()));
        CHECK(tile->header.level == z);
        for (const tl::LayerId id : {tl::LayerId::building, tl::LayerId::part}) {
            const tl::Layer* l = tile->layer(id);
            if (l == nullptr) continue;
            for (std::uint32_t i = 0; i < l->count; ++i) {
                const tl::RingView r = l->ring(tl::building::ring, i);
                out.rings.push_back({.id = l->integer(tl::building::id, i),
                                     .part = id == tl::LayerId::part,
                                     .lat = {r.lat.begin(), r.lat.end()},
                                     .lon = {r.lon.begin(), r.lon.end()},
                                     .keep = {r.keep.begin(), r.keep.end()},
                                     .has_keep = r.has_keep});
            }
        }
    }
    return out;
}

/// A ring at one of the ladder's levels as stage T reads it: the corners kept, their places, the seams by the marks.
struct Read {
    std::vector<std::uint32_t> kept;
    std::vector<gm::V2> poly;
    std::vector<std::uint8_t> seam;
    /// Each seam: its first corner's grid point, its second's, its kind.
    std::vector<std::tuple<std::int32_t, std::int32_t, std::int32_t, std::int32_t, std::uint8_t>> marks;
};

[[nodiscard]] Read read(const Ring& ring, unsigned level, const tl::Frame& frame) {
    Read out;
    out.kept.resize(ring.lat.size());
    const tl::Simplified s = tl::simplify(ring.view(), level, frame, out.kept);
    out.kept.resize(s.count);
    for (const std::uint32_t v : out.kept) out.poly.emplace_back(frame.x(ring.lon[v]), frame.z(ring.lat[v]));
    gm::seams_at(ring.view(), out.kept, out.seam);
    for (std::size_t i = 0; i < out.seam.size(); ++i) {
        if (out.seam[i] == kind::none) continue;
        const std::uint32_t a = out.kept[i];
        const std::uint32_t b = out.kept[(i + 1) % out.kept.size()];
        out.marks.emplace_back(ring.lat[a], ring.lon[a], ring.lat[b], ring.lon[b], out.seam[i]);
    }
    return out;
}

[[nodiscard]] std::size_t drawn(const gm::Split& s) {
    return static_cast<std::size_t>(std::ranges::count_if(s.pieces, [](const gm::Piece& p) { return p.drawn; }));
}

[[nodiscard]] load::BuiltWindow window() {
    const tl::TileKey k = atlas::test::seamed::window();
    const std::string answer = document();
    auto built = load::build_window(std::as_bytes(std::span(answer.data(), answer.size())), k, load::place_of(k));
    ATLAS_REQUIRE_OR_RETURN({}, built.has_value(), built.error().what);
    return std::move(*built);
}

} // namespace

TEST_CASE("marks: a window's seams come back from its tile as written, the same seams at every level of the ladder") {
    const load::BuiltWindow built = window();
    CHECK(built.counts.courtyards == 1);
    CHECK(built.counts.seamed == 3);
    // what the pack build's line prints: one courtyard's seam and two between pieces, each there and back
    const auto counted = load::buildings_of(built.pack);
    ATLAS_REQUIRE(counted.has_value(), counted.error().what);
    CHECK(counted->records == 5);
    CHECK(counted->seams.court == 2);
    CHECK(counted->seams.piece == 4);
    CHECK(counted->seams.unpaired == 0);

    const Held held = rings_of(built.pack, window_z);
    CHECK(held.rings.size() == 5);
    const std::array<std::tuple<std::int64_t, std::uint8_t, std::size_t>, 3> expected = {{
        {block_id, kind::court, 1}, // a block and its courtyard: one piece
        {cut_id, kind::piece, 2},
        {annex_id, kind::piece, 2},
    }};
    for (const auto& [id, of, pieces] : expected) {
        INFO("building " << id);
        const Ring* ring = held.building(id);
        ATLAS_REQUIRE(ring != nullptr);
        ATLAS_REQUIRE(ring->has_keep);
        const Read whole = read(*ring, 0, held.frame);
        ATLAS_REQUIRE(whole.poly.size() == ring->lat.size());
        // as written: the seams stage P's library finds on this very ring, each of the kind its middle gives
        CHECK(whole.seam == load::seam_kinds(whole.poly));
        ATLAS_REQUIRE(whole.marks.size() == 2);
        CHECK(std::get<4>(whole.marks[0]) == of);
        CHECK(std::get<4>(whole.marks[1]) == of);
        const gm::Split first = gm::split(whole.poly, whole.seam, 0.0);
        CHECK(first.paired);
        CHECK(first.pieces.size() == pieces);
        CHECK(drawn(first) == pieces);
        // every other corner's code is the ladder's
        CHECK(std::ranges::count_if(ring->keep, [](std::uint8_t c) { return c > tl::keep::all; }) == 2);
        bool fewer = false;
        for (unsigned level = 1; level < tl::keep::tolerances.size(); ++level) {
            INFO("the ladder's level " << level);
            const Read at = read(*ring, level, held.frame);
            fewer = fewer || at.poly.size() < whole.poly.size();
            CHECK(at.marks == whole.marks); // the same seams, on the same grid points
            const gm::Split s = gm::split(at.poly, at.seam, 0.0);
            CHECK(s.paired);
            CHECK(s.pieces.size() <= pieces);
            CHECK(drawn(s) >= 1);
            // up to the coarsest tolerance the frame reads (4 m), the block and the cut outline keep their pieces
            if (id != annex_id && level <= 11) CHECK(drawn(s) == pieces);
        }
        CHECK(fewer); // the ladder did drop corners of it
    }
    // a ring without a seam carries no mark
    for (const Ring& r : held.rings) {
        if (!r.part) continue;
        CHECK(std::ranges::none_of(r.keep, [](std::uint8_t c) { return c > tl::keep::all; }));
        CHECK(read(r, 0, held.frame).seam.empty());
    }
}

TEST_CASE("marks: the levels written simplified hold a window's seams on its own grid points, and count them") {
    const load::BuiltWindow built = window();
    const Held held = rings_of(built.pack, window_z);
    const std::array<std::span<const std::byte>, 1> windows{std::span<const std::byte>(built.pack)};
    const auto levels = load::build_levels(windows);
    ATLAS_REQUIRE(levels.has_value(), levels.error().what);
    ATLAS_REQUIRE(levels->size() == 2);
    for (const load::BuiltLevel& level : *levels) {
        INFO("level " << static_cast<int>(level.key.z));
        const Held at = rings_of(level.pack, level.key.z);
        load::SeamMarks seen;
        for (const std::int64_t id : {block_id, cut_id, annex_id}) {
            INFO("building " << id);
            const Ring* whole = held.building(id);
            const Ring* ring = at.building(id);
            ATLAS_REQUIRE(whole != nullptr);
            ATLAS_REQUIRE(ring != nullptr);
            CHECK(ring->lat.size() < whole->lat.size()); // written simplified
            const Read r = read(*ring, 0, at.frame);
            // the level's tile holds the window's own grid points, so no two of a ring's corners fall together there
            // that did not in its window: its seams are the window's, corner for corner
            CHECK(r.marks == read(*whole, 0, held.frame).marks);
            load::count_marks(ring->view(), at.frame, seen);
            const gm::Split s = gm::split(r.poly, r.seam, tl::least_face(level.key.z));
            CHECK(s.paired);
            // the annex of 2 m by 2 m keeps no area at either level's tolerance: it draws nothing there, its building
            // does
            CHECK(drawn(s) == (id == cut_id ? 2U : 1U));
            if (id == annex_id) CHECK(drawn(gm::split(r.poly, r.seam, 0.0)) + s.pieces.size() >= 2);
        }
        CHECK(seen.unpaired == 0);
        CHECK(level.seams.piece == seen.piece);
        CHECK(level.seams.court == seen.court);
        CHECK(level.seams.unpaired == 0);
        CHECK(level.seams.court == 2);
        CHECK(level.seams.piece == 4);
    }
    CHECK(tl::least_face(13) > 1.0);
    CHECK(tl::least_face(13) < tl::least_face(12));
    CHECK(tl::least_face(window_z) == 0.0);
}
