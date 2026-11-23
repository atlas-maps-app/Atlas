// The sun's sample points (sun/Samples.hpp) on a tile written to the specification by the tests' own writer
// (tests/tiles/support.hpp): a flat-topped house, gabled, hipped, pyramidal, skillion and mansard roofs, a houseboat
// beside a quay, a raised part, trees, and a quay edge under a bridge road. Every kind's samples in the documented
// order, the window group last, each sample's face and solid; the solids and the tallest; a hash of every sample's bits
// pinned on this Mac, so every other host (CI's two runners, the emulator's Bionic) computes the same points.
// MT/sun/SunParityTest.kt holds the samples of the boards' real tiles to the port's, point for point.
#include "base/arena.hpp"
#include "sun/Samples.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"
#include "tiles/layers/Buildings.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstdio>
#include <map>
#include <string>
#include <vector>

namespace {

using namespace atlas::sun;
namespace tl = atlas::tiles;
using atlas::test::LayerBytes;
using atlas::test::Ring;
using atlas::test::TileBytes;
namespace col = atlas::test::columns;

constexpr std::int32_t s0 = 523'740'000; // 52.374° N
constexpr std::int32_t w0 = 48'840'000;  // 4.884° E

/// A rectangle of `w` × `d` grid units from (south + dy, west + dx), walked counter-clockwise on the map.
Ring rect(std::int32_t dx, std::int32_t dy, std::int32_t w, std::int32_t d) {
    return {.lat = {s0 + dy, s0 + dy, s0 + dy + d, s0 + dy + d},
            .lon = {w0 + dx, w0 + dx + w, w0 + dx + w, w0 + dx},
            .keep = {}};
}

struct Fixture {
    TileBytes t;
    tl::RenderHead head;

    Fixture() {
        head.frame = tl::Frame{.lat0 = 52.374, .lon0 = 4.884, .kx = 67'908.9, .kz = 111'318.8};
        // 1 m is about 147 grid units of longitude and 90 of latitude here
        const std::vector<Ring> rings{
            rect(0, 0, 1470, 1800),     // 0: a house, 10 × 20 m, flat
            rect(3000, 0, 1176, 1260),  // 1: gabled, 8 × 14 m
            rect(6000, 0, 1470, 1080),  // 2: hipped
            rect(9000, 0, 1470, 900),   // 3: pyramidal
            rect(12000, 0, 1764, 1080), // 4: skillion, roof:direction SW
            rect(15000, 0, 2205, 1800), // 5: mansard
            rect(0, 4000, 2940, 450),   // 6: a houseboat, 20 × 5 m, near the quay
        };
        LayerBytes b;
        b.id = static_cast<std::uint8_t>(tl::LayerId::building);
        b.cells = {0, 0, 0, 0, 0, 0, 0};
        const std::uint64_t houseboat = tl::building::flag_houseboat | tl::building::flag_near_water;
        b.columns = {
            {"index", tl::Codec::delta, col::delta({0, 1, 2, 3, 4, 5, 6})},
            {"id", tl::Codec::delta, col::delta({100, 101, 102, 103, 104, 105, 106})},
            {"ring", tl::Codec::geom, col::geom(rings)},
            {"h", tl::Codec::centi, col::centi({12.0, 15.0, 14.0, 11.0, 9.5, 18.0, 2.5})},
            {"min", tl::Codec::centi, col::centi({0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0})},
            {"roof_h", tl::Codec::centi, col::centi({std::nullopt, 5.0, 4.0, 3.5, 2.0, 4.0, std::nullopt})},
            {"levels", tl::Codec::nvarz, col::nvarz({4, 3, 3, 3, 2, 4, 1})},
            {"roof", tl::Codec::str,
             col::str(t, {std::nullopt, "gabled", "hipped", "pyramidal", "skillion", "mansard", std::nullopt})},
            {"flags", tl::Codec::varu, col::varu({0, tl::building::flag_modern, 0, 0, 0, 0, houseboat})},
            {"tag:building", tl::Codec::str,
             col::str(t, {"house", "house", "house", "church", "shed", "apartments", "houseboat"})},
            {"tag:roof:direction", tl::Codec::str,
             col::str(t, {std::nullopt, std::nullopt, std::nullopt, std::nullopt, " sw ", std::nullopt, std::nullopt})},
        };
        LayerBytes p;
        p.id = static_cast<std::uint8_t>(tl::LayerId::part);
        p.cells = {0};
        p.columns = {
            {"index", tl::Codec::delta, col::delta({0})},
            {"id", tl::Codec::delta, col::delta({200})},
            {"ring", tl::Codec::geom, col::geom({rect(300, 300, 588, 540)})},
            {"h", tl::Codec::centi, col::centi({30.0})},
            {"min", tl::Codec::centi, col::centi({12.0})},
            {"levels", tl::Codec::nvarz, col::nvarz({6})},
            {"tag:building:part", tl::Codec::str, col::str(t, {"yes"})},
        };
        LayerBytes tr;
        tr.id = static_cast<std::uint8_t>(tl::LayerId::tree);
        tr.cells = {0, 0};
        tr.columns = {
            {"index", tl::Codec::delta, col::delta({0, 1})},
            {"node", tl::Codec::delta, col::delta({5, 9})},
            {"id", tl::Codec::delta, col::delta({900, 901})},
            {"pos", tl::Codec::point, col::point({{s0 + 2500, w0 + 500}, {s0 + 2600, w0 + 2500}})},
            {"h", tl::Codec::centi, col::centi({12.0, 9.0})},
            {"crown", tl::Codec::centi, col::centi({7.0, 5.0})},
        };
        // the quay south of the houseboat, 40 m long, its water to the south; a bridge road across it
        LayerBytes q;
        q.id = static_cast<std::uint8_t>(tl::LayerId::quay);
        q.cells = {0};
        q.columns = {
            {"index", tl::Codec::delta, col::delta({0})},
            {"ring", tl::Codec::varu, col::varu({0})},
            {"edge", tl::Codec::geom,
             col::geom({Ring{.lat = {s0 + 3900, s0 + 3900}, .lon = {w0 - 1000, w0 + 4880}, .keep = {}}})},
            {"flip", tl::Codec::boolean, col::boolean({true})},
        };
        LayerBytes r;
        r.id = static_cast<std::uint8_t>(tl::LayerId::road);
        r.cells = {0};
        r.columns = {
            {"index", tl::Codec::delta, col::delta({0})},
            {"id", tl::Codec::delta, col::delta({500})},
            {"line", tl::Codec::geom,
             col::geom({Ring{.lat = {s0 + 3000, s0 + 4800}, .lon = {w0 + 2000, w0 + 2000}, .keep = {}}})},
            {"w", tl::Codec::centi, col::centi({6.0})},
            {"bridge", tl::Codec::boolean, col::boolean({true})},
        };
        t.layers = {b, p, tr, q, r};
    }
};

/// A hash of every sample's bits, kinds, faces and solids, and of the faces' normals.
std::string hash_of(const TileSamples& s) {
    tl::Sha256 sha;
    const auto put = [&sha](std::uint64_t v) {
        std::array<std::byte, 8> b{};
        for (unsigned k = 0; k < 8; ++k) b[k] = static_cast<std::byte>((v >> (8U * k)) & 0xFFU);
        sha.update(b);
    };
    for (std::uint32_t i = 0; i < s.size(); ++i) {
        put(std::bit_cast<std::uint64_t>(s.x[i]));
        put(std::bit_cast<std::uint64_t>(s.y[i]));
        put(std::bit_cast<std::uint64_t>(s.z[i]));
        put((std::uint64_t{static_cast<std::uint8_t>(s.kind[i])} << 56U) | (std::uint64_t{s.edge[i]} << 32U) |
            s.part[i]);
        put((std::uint64_t{s.face[i]} << 32U) | s.skip[i]);
    }
    for (std::size_t f = 0; f < s.nx.size(); ++f) {
        put(std::bit_cast<std::uint64_t>(s.nx[f]));
        put(std::bit_cast<std::uint64_t>(s.ny[f]));
        put(std::bit_cast<std::uint64_t>(s.nz[f]));
    }
    for (const double r : s.crown_r) put(std::bit_cast<std::uint64_t>(r));
    return tl::hex(sha.finish());
}

/// The samples' hash on this Mac (Apple clang, arm64), which every host must compute.
constexpr const char* pinned = "dd223c63928667d9668c4be19080be15c696f267c31566fbe0a72bda86a9f8eb";

} // namespace

TEST_CASE("sun: a tile's samples, kind by kind, in their order") {
    Fixture f;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "sun samples test");
    tl::TileDecoder decoder;
    const std::vector<std::byte> raw = f.t.encode();
    auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    std::vector<Deck> decks;
    collect_decks(*tile, f.head, 0, decks);
    ATLAS_REQUIRE(decks.size() == 1);
    CHECK(decks[0].road);
    CHECK(decks[0].hw == (6.0 / 2) + 0.8);
    const Region region;
    const SunTile st = make_tile(*tile, f.head, region, 0, decks);
    const TileSamples& s = st.samples;
    // the solids: every building with a height and the part
    CHECK(st.solids.size() == 8);
    CHECK(st.solids.source[7] == Source::part);
    CHECK(st.solids.min[7] == 12.0);
    std::map<Kind, int> by_kind;
    for (const Kind k : s.kind) ++by_kind[k];
    for (const auto& [k, n] : by_kind) MESSAGE(std::string(name_of(k)), " ", n);
    // the house: 4 walls, 2 more cells on each long wall, its top; its windows (4 levels × 4 or 9 bays a wall)
    CHECK(s.kind[0] == Kind::wall);
    CHECK(s.source[0] == Source::building);
    CHECK(s.owner[0] == 0);
    CHECK(s.skip[0] == 0);
    for (const Kind k : {Kind::wall, Kind::cell, Kind::window, Kind::pyramid, Kind::gable, Kind::slope, Kind::hip,
                         Kind::skillion, Kind::skillion_wall, Kind::top, Kind::mansard, Kind::dormer, Kind::mansard_top,
                         Kind::hull, Kind::cabin, Kind::reflection, Kind::quay, Kind::crown}) {
        CHECK_MESSAGE(by_kind[k] > 0, std::string(name_of(k)), " has no sample");
    }
    CHECK(by_kind[Kind::pyramid] == 4);
    CHECK(by_kind[Kind::crown] == 2);
    CHECK(by_kind[Kind::quay] == 2); // the bridge's deck cuts the quay in two pieces
    CHECK(by_kind[Kind::quay_band] == 0);
    // the window group last, its own faces; every other face, solid and crown in range
    for (std::uint32_t i = 0; i < s.size(); ++i) {
        CHECK((s.kind[i] == Kind::window) == (i >= s.windows_first));
        CHECK((s.kind[i] == Kind::crown) == (i >= s.crowns_first && i < s.crowns_end));
        CHECK((s.face[i] == no_face || s.face[i] < s.nx.size()));
        CHECK((s.skip[i] == no_solid || s.skip[i] < st.solids.size()));
        if (s.face[i] != no_face) {
            const double l = (s.nx[s.face[i]] * s.nx[s.face[i]]) + (s.ny[s.face[i]] * s.ny[s.face[i]]) +
                             (s.nz[s.face[i]] * s.nz[s.face[i]]);
            CHECK(std::abs(l - 1.0) < 1e-12);
        }
    }
    // the house's first wall: its one cell's centre, 0.15 m out (the port's arithmetic, step by step)
    const double ax = f.head.frame.x(w0);
    const double az = f.head.frame.z(s0);
    const double bx = f.head.frame.x(w0 + 1470);
    const double ex = bx - ax;
    const double nl = std::sqrt((0.0 * 0.0) + (0.0 * 0.0) + (ex * ex));
    CHECK(s.x[0] == (ax + ((ex * (0.0 + 1.0)) / 2)) + ((0.0 / nl) * 0.15));
    CHECK(s.y[0] == (0.0 + (0.0 + ((12.0 - 0.0) * 1) / 1)) / 2);
    CHECK(s.z[0] == (az + ((0.0 * (0.0 + 1.0)) / 2)) + ((-ex / nl) * 0.15));
    const std::string h = hash_of(s);
    MESSAGE("samples ", s.size(), " hash ", h);
    (void)std::printf("sun samples hash %s\n", h.c_str()); // NOLINT: the line CI's logs keep
    if (std::string(pinned).empty()) {
        MESSAGE("no pinned hash yet");
    } else {
        CHECK(h == pinned);
    }
    // at a level of the ladder a tile without keep codes is whole: the same samples
    const SunTile at7 = make_tile(*tile, f.head, region, 7, decks);
    CHECK(hash_of(at7.samples) == h);
}

TEST_CASE("sun: the region's values are the pack's style's") {
    tl::Style style;
    style.water_y = -0.4;
    style.paris = true;
    style.no_shadows = true;
    const Region r = region_of(style);
    CHECK(r.water_y == -0.4);
    CHECK(r.paris);
    CHECK(!r.tone);
    CHECK(r.no_shadows);
}

namespace {

/// A bridge road's deck from (x0, z0) to (x1, z1) in metres, reaching `hw` from its centreline, its box widened by the
/// road's width `rw` (as collect_decks makes one).
Deck road_deck(double x0, double z0, double x1, double z1, double hw, double rw) {
    Deck d;
    d.road = true;
    d.hw = hw;
    d.x = {x0, x1};
    d.z = {z0, z1};
    d.box = {std::min(x0, x1) - rw, std::max(x0, x1) + rw, std::min(z0, z1) - rw, std::max(z0, z1) + rw};
    return d;
}

/// near_digest over `decks` with their own digests (deck_digest's, in their order), as a scene hands them in.
tl::Sha256Digest near_of(std::span<const QuayMark> quays, std::span<const Deck> decks) {
    std::vector<tl::Sha256Digest> digests;
    digests.reserve(decks.size());
    for (const Deck& d : decks) digests.push_back(deck_digest(d));
    return near_digest(quays, decks, digests);
}

} // namespace

TEST_CASE("sun: the decks a whole tile's quay walls are kept on: a near deck changed in any of its fields changes "
          "their digest, and a deck near or no longer; a far deck changed, gone or more, the decks in another order "
          "or one of them twice do not") {
    // two quay edges: 40 m from (0, 0) east to (40, 0), and 30 m from (100, 0) south to (100, 30)
    const std::array<QuayMark, 2> quays{quay_mark(0.0, 0.0, 40.0, 0.0), quay_mark(100.0, 0.0, 100.0, 30.0)};
    // near the first: a bridge road across it (its deck holds a stretch of the edge), and one 25 m off it, within the
    // edge's length of its middle, holding none of it; near the second alone: an area deck beside it; far: 600 m off
    const Deck across = road_deck(20.0, -10.0, 20.0, 10.0, 4.8, 8.0);
    const Deck beside = road_deck(15.0, 25.0, 30.0, 45.0, 4.8, 8.0);
    Deck area;
    area.road = false;
    area.x = {104.0, 112.0, 112.0, 104.0, 104.0};
    area.z = {10.0, 10.0, 20.0, 20.0, 10.0};
    area.box = {104.0, 112.0, 10.0, 20.0};
    const Deck far = road_deck(600.0, 600.0, 620.0, 600.0, 4.8, 8.0);
    REQUIRE(deck_near(quays[0], across));
    REQUIRE(deck_near(quays[0], beside));
    REQUIRE_FALSE(deck_near(quays[1], beside));
    REQUIRE(deck_near(quays[1], area));
    REQUIRE_FALSE(deck_near(quays[0], area));
    REQUIRE_FALSE(deck_near(quays[0], far));
    REQUIRE_FALSE(deck_near(quays[1], far));
    const std::vector<Deck> decks{across, beside, area, far};
    const tl::Sha256Digest base = near_of(quays, decks);

    // every field of each near deck in turn: another deck, so another set
    struct Change {
        const char* what;
        void (*change)(Deck&);
    };
    const std::array<Change, 11> changes{{
        {.what = "a point's x", .change = [](Deck& d) { d.x[1] += 0.5; }},
        {.what = "a point's z", .change = [](Deck& d) { d.z[0] -= 0.5; }},
        {.what = "a point more",
         .change =
             [](Deck& d) {
                 d.x.push_back(d.x.back());
                 d.z.push_back(d.z.back() + 1.0);
             }},
        {.what = "an x moved to the z's",
         .change =
             [](Deck& d) {
                 d.z.insert(d.z.begin(), d.x.back());
                 d.x.pop_back();
             }},
        {.what = "its reach", .change = [](Deck& d) { d.hw += 0.25; }},
        {.what = "its kind", .change = [](Deck& d) { d.road = !d.road; }},
        {.what = "its box's west", .change = [](Deck& d) { d.box[0] -= 0.5; }},
        {.what = "its box's east", .change = [](Deck& d) { d.box[1] += 0.5; }},
        {.what = "its box's south", .change = [](Deck& d) { d.box[2] -= 0.5; }},
        {.what = "its box's north", .change = [](Deck& d) { d.box[3] += 0.5; }},
        {.what = "a point's x by one ulp", .change = [](Deck& d) { d.x[0] = std::nextafter(d.x[0], 1e9); }},
    }};
    for (const Change& c : changes) {
        CAPTURE(c.what);
        for (const std::size_t near : {std::size_t{0}, std::size_t{1}, std::size_t{2}}) {
            CAPTURE(near);
            std::vector<Deck> other = decks;
            c.change(other[near]);
            CHECK(deck_digest(other[near]) != deck_digest(decks[near]));
            CHECK(near_of(quays, other) != base);
        }
        // the far deck changed alike: the same set
        std::vector<Deck> other = decks;
        c.change(other[3]);
        CHECK(deck_digest(other[3]) != deck_digest(decks[3]));
        CHECK(near_of(quays, other) == base);
    }
    // a near deck gone, or one more near either edge: another set
    CHECK(near_of(quays, std::vector<Deck>{across, area, far}) != base);
    CHECK(near_of(quays, std::vector<Deck>{across, beside, far}) != base);
    std::vector<Deck> more = decks;
    more.push_back(road_deck(90.0, 15.0, 95.0, 15.0, 4.8, 8.0));
    REQUIRE(deck_near(quays[1], more.back()));
    CHECK(near_of(quays, more) != base);
    // the same set: the far deck gone, another far one, the decks the other way round, a near one twice
    CHECK(near_of(quays, std::vector<Deck>{across, beside, area}) == base);
    std::vector<Deck> farther = decks;
    farther.push_back(road_deck(-900.0, 40.0, -880.0, 40.0, 4.8, 8.0));
    CHECK(near_of(quays, farther) == base);
    const std::vector<Deck> reversed(decks.rbegin(), decks.rend());
    CHECK(near_of(quays, reversed) == base);
    std::vector<Deck> twice = decks;
    twice.push_back(across);
    CHECK(near_of(quays, twice) == base);
    // no quay edge, or one of no length at the far deck's corner: no deck near, the empty set's digest
    const tl::Sha256Digest none = near_of({}, decks);
    CHECK(none != base);
    CHECK(near_of(std::array<QuayMark, 1>{quay_mark(0.0, 0.0, 40.0, 0.0)}, std::vector<Deck>{far}) == none);
    const QuayMark nan{.mx = std::nan(""), .mz = 0.0, .el = 40.0};
    CHECK(near_of(std::array<QuayMark, 1>{nan}, decks) == none);
}

TEST_CASE("sun: a part is made on the same ground where its frame's four values and its region's six are the same") {
    const tl::Frame frame{.lat0 = 52.3666, .lon0 = 4.8833, .kx = 67'960.25, .kz = 111'250.0};
    const Region region{.water_y = -1.5, .tone = true, .kit_eaves = 0.6};
    CHECK(same_ground(frame, region, frame, region));
    const std::array<void (*)(tl::Frame&), 4> frames{
        [](tl::Frame& f) { f.lat0 += 1e-4; }, [](tl::Frame& f) { f.lon0 -= 1e-4; }, [](tl::Frame& f) { f.kx += 0.5; },
        [](tl::Frame& f) { f.kz -= 0.5; }};
    for (std::size_t i = 0; i < frames.size(); ++i) {
        CAPTURE(i);
        tl::Frame f = frame;
        frames.at(i)(f);
        CHECK_FALSE(same_ground(frame, region, f, region));
        CHECK_FALSE(same_ground(f, region, frame, region));
    }
    const std::array<void (*)(Region&), 7> regions{
        [](Region& r) { r.water_y = -2.0; },  [](Region& r) { r.paris = true; },
        [](Region& r) { r.tone = false; },    [](Region& r) { r.no_shadows = true; },
        [](Region& r) { r.kit_eaves = 0.7; }, [](Region& r) { r.kit_eaves.reset(); },
        [](Region& r) { r.kit_band = 3.2; }};
    for (std::size_t i = 0; i < regions.size(); ++i) {
        CAPTURE(i);
        Region r = region;
        regions.at(i)(r);
        CHECK_FALSE(same_ground(frame, region, frame, r));
        CHECK_FALSE(same_ground(frame, r, frame, region));
    }
}
