// The sun's sample points (plan-vector D8, K8): exactly the points at which the port's renderer asks Shadows.lit for
// sun or shade (render/amsterdam.py's draw_building, gable roofs, mansards, houseboats, reflections, quay walls and
// tree crowns), one answer per drawn face, from a render tile's records at one level of the ladder.
//
// The renders decide per camera which of these they draw (a wall's cells by its distance, windows within win_d, a
// face toward the camera, a flat top when the eye is above it, houseboats and quays only with weather); the samples
// here are every one any camera can draw, both cell variants among them, so a frame only picks. A sample is tested
// only where its face turns to the sun (the port's `vdot(n, L) > 0`, decided per bucket): a face turned away is unlit
// by definition and needs no march. Each point is computed with the port's operations in its order (pymath for sqrt,
// hypot, dist and Python's sum), so the point, and with it the answer, is the port's bit for bit.
//
// A tile's samples, in order: for each building, then each part, of the tile (records in the tile's order) its
// walls (per ring edge of 0.3 m or more: the one cell, the ncol × nrow cells when they are not one, the triangle up
// to a skillion slope, a kit's band), its roof (by shape), a houseboat's hull and cabin walls, its reflection's walls;
// then every tree's crown; then every quay wall's pieces. Windows come after all of those, in the same order of records
// and edges, as their own group: a bucket tests them only for tiles near the camera (within win_d · 1.35).
//
// A tradition's kit (drawn with weather) adds a wall's stucco ground floor's band after its cells (trad_wall's, where
// the building stands on the street), and its roof's gables' and slopes' samples (trad_roof's) after the plain roof's,
// which the same building draws without weather; its corner boards are lit as they face the sun, with no sample. Not
// covered yet: a kit's chimneys, the eye-level wall profile (shadow_height, drawn only below 10° of pitch and within
// 110 m), and terrain towns' own ground (`_gy`), which no render tile carries.
#pragma once

#include "sun/BitTiles.hpp"
#include "sun/Grid.hpp"
#include "sun/Shadows.hpp"
#include "tiles/Container.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Style.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::sun {

/// The region's values the samples read: the port's module state after the region's load (the pack's style: its
/// `state` and `scene` tables).
struct Region {
    double water_y = -1.5;           ///< WATER_Y: the water's level below the street, metres
    bool paris = false;              ///< WIN_STYLE == "paris": tall French windows in stone fronts
    bool tone = false;               ///< TONE: tall quay walls in sun are drawn in pieces
    bool no_shadows = false;         ///< S['no_shadows']: no ground shadows
    std::optional<double> kit_eaves; ///< the tradition's eaves, where the pack has a kit (its style's `tradition`)
    std::optional<double> kit_band;  ///< its stucco ground floor's height, where its kit has one
};

/// The region's values of a pack's style.
[[nodiscard]] Region region_of(const tiles::Style& style) noexcept;

/// What a sample is: one kind of answer the renderer asks for.
enum class Kind : std::uint8_t {
    wall = 0,          ///< a wall drawn as one cell (460 m away and beyond): its centre, 0.15 m out
    cell = 1,          ///< a cell of a wall drawn as ncol × nrow cells (nearer than 460 m)
    skillion_wall = 2, ///< the triangle of wall up to a skillion roof's slope
    window = 3,        ///< a window (drawn within win_d), its centre 0.2 m out
    pyramid = 4,       ///< a pyramidal roof's face, at its centroid
    hip = 5,           ///< a hipped roof's end triangle, at its centroid
    gable = 6,         ///< a gable's wall triangle, its centroid 0.2 m out
    slope = 7,         ///< a gabled or hipped roof's slope, at its centroid
    skillion = 8,      ///< a skillion roof, its centre 0.1 m above the slope's middle
    top = 9,           ///< a flat top seen from above (or a roof the renders draw flat), 0.05 m above it
    mansard = 10,      ///< a mansard's steep face, its centroid 0.25 m out
    dormer = 11,       ///< the dormer fronts on a mansard face
    mansard_top = 12,  ///< a mansard's top seen from above
    hull = 13,         ///< a houseboat's hull wall (drawn when the moment has weather)
    cabin = 14,        ///< a houseboat's cabin wall
    reflection = 15,   ///< a wall mirrored in the water: the real wall's middle, 0.2 m out
    quay = 16,         ///< a quay wall's piece, at half the water's depth, 0.3 m out
    quay_band = 17,    ///< a piece of a tall quay wall in sun (TONE, water deeper than 2 m)
    crown = 18,        ///< a tree's crown: 3/4 up the tree, moved toward the sun by the crown's radius
    band = 19,         ///< a kit's stucco ground floor on a wall: its middle, 0.2 m out, half its height up
};

/// The kinds' names, for logs and tests.
[[nodiscard]] const char* name_of(Kind kind) noexcept;

/// The number of kinds.
inline constexpr std::size_t kinds = 20;

/// No face: a sample tested whatever the sun (flat tops, crowns).
inline constexpr std::uint32_t no_face = 0xFFFF'FFFFU;

/// No solid left out of a march, as a sample's skip.
inline constexpr std::uint32_t no_solid = 0xFFFF'FFFFU;

/// Which list a record of a tile is in.
enum class Source : std::uint8_t { building = 0, part = 1, tree = 2, quay = 3 };

/// A tile's sample points at one level, as structures of arrays (sample i at index i of each).
struct TileSamples {
    std::vector<double> x;           ///< the point (a crown: the trunk's x)
    std::vector<double> y;           ///< (a crown: 0.75 of the tree's height)
    std::vector<double> z;           ///< (a crown: the trunk's z)
    std::vector<std::uint32_t> face; ///< the face it is on (its normal in nx, ny, nz), or no_face
    std::vector<std::uint32_t> skip; ///< the solid its march leaves out, by its place in the tile's solids, or no_solid
    std::vector<Kind> kind;
    std::vector<Source> source;       ///< the list its record is in
    std::vector<std::uint32_t> owner; ///< its record's place in its layer of the tile (a tree's, a quay edge's)
    std::vector<std::uint16_t> edge;  ///< the record's ring edge (or face) it is on
    std::vector<std::uint16_t> part;  ///< its place on that edge: a cell's r · ncol + c, a window's, a piece's

    std::vector<double> nx; ///< per face: the face's unit normal, as the port computes it
    std::vector<double> ny;
    std::vector<double> nz;

    std::vector<double> crown_r; ///< per crown (samples crowns_first to crowns_end): the crown's radius

    std::uint32_t crowns_first = 0; ///< the crowns: samples [crowns_first, crowns_end)
    std::uint32_t crowns_end = 0;
    std::uint32_t windows_first = 0; ///< the window group: samples [windows_first, size())

    [[nodiscard]] std::uint32_t size() const noexcept { return static_cast<std::uint32_t>(x.size()); }

    /// Adds a face and returns its index.
    std::uint32_t add_face(double fx, double fy, double fz);
    /// Adds a sample.
    void add(Kind k, Source src, std::uint32_t own, std::uint32_t e, std::uint32_t p, double px, double py, double pz,
             std::uint32_t f, std::uint32_t s);
};

/// A tile's solids: the prisms it adds to the grid (buildings not hidden with a height, parts with a height, in the
/// tile's order), and which record each is.
struct TileSolids {
    std::vector<double> x; ///< every footprint's x, solid after solid
    std::vector<double> z;
    std::vector<std::uint32_t> first{0}; ///< where each solid's footprint begins (one more than solids)
    std::vector<double> h;
    std::vector<double> min;           ///< its walls' base (for the ground shadows)
    std::vector<Source> source;        ///< building or part
    std::vector<std::uint32_t> record; ///< its place in its layer of the tile

    [[nodiscard]] std::uint32_t size() const noexcept { return static_cast<std::uint32_t>(h.size()); }
    [[nodiscard]] std::span<const double> xs(std::uint32_t s) const noexcept {
        return std::span<const double>(x).subspan(first[s], first[s + 1] - first[s]);
    }
    [[nodiscard]] std::span<const double> zs(std::uint32_t s) const noexcept {
        return std::span<const double>(z).subspan(first[s], first[s + 1] - first[s]);
    }
};

/// A tile's trees: where each stands, its height and crown (for the crowns' samples and the ground's tree discs).
struct TileTrees {
    std::vector<double> x;
    std::vector<double> z;
    std::vector<double> h;
    std::vector<double> crown;    ///< the crown's diameter
    std::vector<double> gy;       ///< the ground under it (terrain towns; 0 elsewhere)
    std::vector<std::int64_t> id; ///< its OSM node id (its reflection's random.Random seed)
};

/// A building or part the water mirrors (Amsterdam.reflections: within 16 m of a quay edge, not hidden, with a
/// height), as the reflections read it at the level: its solid (whose footprint it mirrors), its values, its roof's
/// height where the reflections draw its roof's shape (else 0), and its footprint's longest edge (Python's max over
/// (length, k): the last of the longest).
struct Mirror {
    std::uint32_t solid = 0;
    Source source = Source::building;
    std::uint32_t record = 0;
    double h = 0.0;
    double min = 0.0;
    double rh = 0.0;
    std::int64_t levels = 0;
    std::uint32_t wall = 0; ///< surface_colour(b, None), 0xRRGGBB
    std::uint32_t longest = 0;
    bool gabled = false; ///< roof == 'gabled'
    bool across = false; ///< orient == 'across'
    bool boat = false;   ///< building == 'houseboat'
    bool part = false;   ///< 'building:part' in its tags
};

/// A bridge's deck over the water (waterLayer's decks): a bridge road (its centreline, half width plus 0.8 m) or a
/// bridge area (its ring), with its box widened as the port widens it.
struct Deck {
    std::vector<double> x;
    std::vector<double> z;
    double hw = 0.0;                ///< a road's reach from its centreline; an area has none
    bool road = false;              ///< a road (else an area)
    std::array<double, 4> box = {}; ///< min x, max x, min z, max z, widened
};

/// The decks of a tile's records at a level: its bridge roads and bridge areas, in its pack's frame (`head`'s), or in
/// `frame`'s metres (a tile of another pack drawn beside it: a point of the grid is a place in every frame).
void collect_decks(const tiles::Tile& tile, const tiles::RenderHead& head, std::uint8_t level, std::vector<Deck>& out);
void collect_decks(const tiles::Tile& tile, const tiles::Frame& frame, std::uint8_t level, std::vector<Deck>& out);

/// A tile's solids and trees at one level of the ladder: what the sun's grid holds and what casts the ground's shadows
/// (Hulls.hpp).
struct TileCasters {
    TileSolids solids;
    TileTrees trees;
    std::vector<Mirror> mirrors;
};

/// The solids and trees of `tile` at ladder `level` (0 to 13): every building not hidden with a height and every part
/// with a height, in the tile's order (buildings, then parts), each from its variant at the level where the tile
/// carries one, its ring simplified at the level; then every tree. make_tile's own, without the samples: the ground's
/// shadows (K9) read the solids the sun's march reads, and the water's reflections (K16) the solids it mirrors, in the
/// order of their reflection samples (SunTile::reflections).
[[nodiscard]] TileCasters solids_of(const tiles::Tile& tile, const tiles::RenderHead& head, std::uint8_t level);
/// The same in `frame`'s metres (a tile of another pack drawn beside it, whose solids a scene holds: SunGlobals).
[[nodiscard]] TileCasters solids_of(const tiles::Tile& tile, const tiles::Frame& frame, std::uint8_t level);

/// A tile's part of the sun's scene at one level of the ladder.
struct SunTile {
    tiles::TileKey key;
    std::uint8_t level = 0;
    TileSolids solids;
    TileTrees trees;
    TileSamples samples;
    /// Its quay walls' pieces with their samples (null: none), which its published bits carry.
    std::shared_ptr<const std::vector<QuayPiece>> quays;
    /// Each mirror's first reflection sample (TileCasters::mirrors' order; null: none), which its published bits carry.
    std::shared_ptr<const std::vector<std::uint32_t>> reflections;
};

/// The sample points, solids and trees of `tile` at ladder `level` (0 to 13): the rings simplified at it, each
/// building's and part's attributes from its variant at it where the tile carries one. `decks` are the bridges near
/// the tile (its own and its halo's, collect_decks), for its quay walls.
[[nodiscard]] SunTile make_tile(const tiles::Tile& tile, const tiles::RenderHead& head, const Region& region,
                                std::uint8_t level, std::span<const Deck> decks);

/// The scene's tallest solid at `level`: Python's max of the heights of every building not hidden with a height and
/// every part with a height in the pack's finest level, each from its variant at `level` where it has one. Decodes
/// every tile of that level once; 0 for a pack without solids.
[[nodiscard]] tiles::Result<double> scene_maxh(const tiles::Pack& pack, std::uint8_t level);

} // namespace atlas::sun
