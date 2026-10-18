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

TEST_CASE("sun: the region's values from a pack's style tables, the port's defaults without them") {
    const tl::StyleTables none;
    const Region r = region_of(none);
    CHECK(r.water_y == -1.5);
    CHECK(!r.paris);
    CHECK(!r.tone);
    CHECK(!r.no_shadows);
}
