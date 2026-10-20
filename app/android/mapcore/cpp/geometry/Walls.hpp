// Stage T of the buildings (plan-vector D2 "What the core builds", D10 "Buildings", K10): a render tile's buildings and
// parts at one level of the ladder become the polygons render/amsterdam.py's _draw_building fills with face(), in world
// space and in the port's own arithmetic, so the 3D points equal the port's (L0 (c), held by
// MT/buildings/StageTParityTest):
//   - the walls (geometry/Walls.cpp): per footprint edge of 0.3 m or more, its cells in both variants the renders
//     choose by distance (one cell from 460 m on, ncol × nrow cells nearer), each with its same-colour 0.3 px edge, and
//     the triangle of wall up to a skillion slope;
//   - the roofs (geometry/Roofs.cpp): by shape after CLOSEST (and quadruple_saltbox as hipped): a gable roof's ridge,
//     gable ends and slopes, a hip's ends, a pyramid's faces, a skillion's slope, a mansard's brisis faces and top, and
//     a flat top (drawn when the eye is above it);
//   - a houseboat drawn as a boat when the moment has weather (geometry/Houseboats.cpp), as a building when not.
// Every primitive keeps what the frame needs to draw it as the port does: its face (base colour and normal, the light
// inputs), its sun sample (K8's index in the tile's samples, sun/Samples.hpp's order), its building's item, its painter
// slot within the item in each variant, and the conditions it is drawn under. Facing, the area test, the near plane and
// the detail band are decided per frame (the vertex stage, shaders/building/building.slangh), never here.
#pragma once

#include "geometry/RoofShapes.hpp"
#include "scene/ItemTable.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

namespace atlas::geometry {

/// No sun sample (a face lit whatever the sun: a houseboat's deck), no polygon (a face of at most four corners).
inline constexpr std::uint32_t no_sample = 0xFFFF'FFFFU;
inline constexpr std::uint32_t no_polygon = 0xFFFF'FFFFU;

/// The renders' distance for a wall's cells (_draw_building: ncol × nrow cells where d < 460, else one).
inline constexpr double cells_reach = 460.0;

/// A building's (or part's) owner kind in scene::owner_key: an area (AnchorItems' OwnerType.AREA).
inline constexpr std::uint8_t owner_area = 4;

/// How a face is lit, besides the theme: by its sample's sun bit (which K8 sets only where the face turns to the sun),
/// or whenever the sun is up (a houseboat's deck and cabin roof: `not night`).
enum class Lit : std::uint8_t { sun = 0, day = 1 };

/// What lights a face (light.slang's light(): the base colour and the normal), and the point its facing is tested at.
struct Face {
    std::uint32_t base = 0; ///< 0xRRGGBB, surface_colour's (wall_rgb, roof_rgb) or the boat's own
    V3 normal;              ///< the port's unit normal
    V3 test;                ///< the point the port tests `vdot(n, C - p) > 0` at (its plane's, but a skillion roof's)
    Lit lit = Lit::sun;
    bool facing = true; ///< tested for facing (a deck and a cabin roof are drawn from either side)
};

/// What a primitive is, as the port draws it.
enum class PrimKind : std::uint8_t {
    cell,          ///< a wall's cell (one per wall from 460 m on)
    skillion_wall, ///< the wall up to a skillion slope
    hull_wall,     ///< a houseboat's hull, waterline to deck
    cabin_wall,    ///< a houseboat's cabin
    top,           ///< a flat top (or a roof drawn flat), when the eye is above it
    slope,         ///< a gabled or hipped roof's slope
    gable,         ///< a gable end, in the wall's material
    hip,           ///< a hipped roof's end triangle
    pyramid,       ///< a pyramidal roof's face
    skillion,      ///< a skillion roof
    mansard,       ///< a mansard's brisis face
    mansard_top,   ///< a mansard's top, when the eye is above it
    deck,          ///< a houseboat's deck
    cabin_roof,    ///< a houseboat's cabin roof
};

/// The kind's name, for tests and logs.
[[nodiscard]] std::string_view name_of(PrimKind kind) noexcept;

/// The conditions a primitive is drawn under, and how.
namespace prim_flag {
inline constexpr std::uint16_t near = 1;      ///< drawn when its building lies nearer than cells_reach
inline constexpr std::uint16_t far = 2;       ///< drawn at cells_reach and beyond
inline constexpr std::uint16_t weather = 4;   ///< drawn only when the moment has weather (a houseboat as a boat)
inline constexpr std::uint16_t dry = 8;       ///< drawn only when it has none (a houseboat as a building)
inline constexpr std::uint16_t edge = 16;     ///< with its same-colour 0.3 px edge (stroke-width .3 in its fill)
inline constexpr std::uint16_t triangle = 32; ///< three corners (the fourth repeats the third)
inline constexpr std::uint16_t polygon = 64;  ///< one triangle of an n-gon: its area is the polygon's
/// Its stroke in the port's attributes, beside a cell's same-colour edge: the roofs' fine edge (.25 px in the edge
/// colour: a gable roof's slopes, gable ends and hip ends, a mansard's brisis faces), their wide one (.45 px: a flat
/// top, a skillion roof, a mansard's top), a houseboat's cabin roof (.4 px #2A2C30). The objects' thin strokes draw
/// them (K17a's ObjectStrokes); the twin emits them.
inline constexpr std::uint16_t stroke_fine = 128;
inline constexpr std::uint16_t stroke_wide = 256;
inline constexpr std::uint16_t stroke_cabin = 512;
/// Every variant: drawn at any distance.
inline constexpr std::uint16_t any = near | far;
} // namespace prim_flag

/// The port's thin stroke of a plain primitive of `kind` (prim_flag's stroke_fine, stroke_wide, stroke_cabin, or 0).
[[nodiscard]] std::uint16_t stroke_of(PrimKind kind) noexcept;

/// One polygon the port fills with face() (or one triangle of an n-gon it fills), with everything the frame needs.
struct Prim {
    std::array<V3, 4> p{}; ///< its corners in the port's order and arithmetic (frame metres); a triangle repeats p[2]
    PrimKind kind = PrimKind::cell;
    std::uint8_t cell = 0;              ///< its building's cell in the tile (the cover draws by cell)
    std::uint16_t flags = 0;            ///< prim_flag
    std::uint32_t face = 0;             ///< its Face in the tile's list
    std::uint32_t sample = no_sample;   ///< its sun sample in the tile's samples (K8's order)
    std::uint32_t item = 0;             ///< its building's item among the tile's building items
    std::uint32_t polygon = no_polygon; ///< the n-gon a triangle fills
    std::uint16_t near_slot = 0;        ///< its painter slot within the item when the item is near, and when far
    std::uint16_t far_slot = 0;
    std::uint16_t edge = 0; ///< the footprint edge (or side) it stands on, as K8's sample names it
    std::uint16_t part = 0; ///< its place on that edge: a cell's r · ncol + c
    /// A near cell's wall: its columns and rows (the frame draws it from its wall's one-cell quad); 0 for the rest.
    std::uint16_t ncol = 0;
    std::uint16_t nrow = 0;
};

/// An n-gon the port fills as one path (a flat top, a skillion roof, a mansard's top, a deck, a cabin roof).
struct Polygon {
    std::vector<V3> ring; ///< its corners in the port's order and arithmetic
    double area = 0.0;    ///< its area in space, m² (its projected area at a camera is estimated from it)
    std::uint32_t face = 0;
    std::uint32_t first = 0; ///< its triangles: plain primitives [first, first + count)
    std::uint32_t count = 0;
    std::uint32_t item = 0; ///< its building's item, its kind and its triangles' conditions (as theirs)
    PrimKind kind = PrimKind::top;
    std::uint16_t flags = 0;
};

/// Which list a building's record is in.
enum class Source : std::uint8_t { building = 0, part = 1 };

/// One building's (or part's) item: the painter unit its primitives draw in (scene/ItemTable.hpp), and who it is.
struct BuildingItem {
    scene::ItemSpec spec;
    Source source = Source::building;
    std::uint32_t record = 0;     ///< its place in its layer of the tile
    std::int64_t id = 0;          ///< the OSM id
    bool relation = false;        ///< the id is a relation's
    std::uint16_t near_slots = 0; ///< the painter slots its primitives take, near and far and as a boat
    std::uint16_t far_slots = 0;
    std::uint16_t boat_slots = 0;
};

/// A tile's buildings at one level: the primitives in two lists the frame draws apart, each sorted by the tile's cells
/// (cell c's primitives are [first[c], first[c + 1])); the faces; the n-gons; the items.
struct TileBuildings {
    std::uint8_t level = 0;
    std::uint8_t cells_per_side = 0;
    /// Quads drawn with their same-colour edge: walls' cells, skillion walls, a houseboat's walls.
    std::vector<Prim> edged;
    std::vector<std::uint32_t> edged_first;
    /// Polygons drawn without one: roof faces and the triangles of n-gons.
    std::vector<Prim> plain;
    std::vector<std::uint32_t> plain_first;
    std::vector<Face> faces;
    std::vector<Polygon> polygons;
    std::vector<BuildingItem> items;
    /// The tile's sun samples the buildings take (K8's count before the tile's trees, quays and windows).
    std::uint32_t samples = 0;
};

/// The region's values the buildings read (sun/Samples.hpp's Region: the port's module state after the region's load).
struct BuildingRegion {
    double water_y = -1.5; ///< WATER_Y: a houseboat's waterline
};

/// What stage T is given.
struct BuildingInput {
    const tiles::Tile* tile = nullptr;
    const tiles::RenderHead* head = nullptr;
    std::uint8_t level = 0; ///< the ladder index the rings are simplified at and the variants loaded at
    BuildingRegion region;
    std::uint16_t kind = 0; ///< the items' kind (the frame's Selectors row)
};

/// The tile's buildings and parts at the input's level.
[[nodiscard]] TileBuildings build_buildings(const BuildingInput& in);

// ---------------------------------------------------------------- stage T's builder, shared by walls, roofs and boats

/// Whether a nullable string holds `s`.
[[nodiscard]] bool is(const std::optional<std::string_view>& v, std::string_view s) noexcept;

/// A building's (or part's) values as the scene at the level holds them (the variant's where the tile has one). A null
/// height or roof height reads 0, so its Python truth is `!= 0`.
struct Values {
    bool has_h = false; ///< draw_building reads h: a building without one is never drawn
    double h = 0.0;
    double min = 0.0;
    double roof_h = 0.0;
    std::optional<std::string_view> roof;
    std::optional<std::string_view> orient;
    std::uint32_t wall_rgb = 0;
    std::uint32_t roof_rgb = 0;
    bool relation = false;
    std::optional<std::string_view> btype;
    bool part_tag = false;
    std::optional<std::string_view> direction;
    bool hidden = false;
    std::uint64_t flags = 0;
};

/// One building as _draw_building draws it: its values, its footprint at the level, and what the walls and the roof
/// share (the walls' base and top, the roof's shape and height, a skillion's slope).
struct Building {
    Source source = Source::building;
    Values v;
    std::vector<V2> poly;
    double h = 0.0;                       ///< the top
    double y0 = 0.0;                      ///< the walls' base
    double rh = 0.0;                      ///< the roof's height where its shape is pitched and it has one, else 0
    double wall_top = 0.0;                ///< h - rh
    std::optional<std::string_view> roof; ///< the shape drawn (quadruple_saltbox as hipped)
    bool skillion = false;                ///< a skillion with a height: its slope
    V2 dirv;                              ///< its downhill direction
    double p0 = 0.0; ///< the least projection of the footprint on it, and the slope's span (at least 0.5)
    double span = 1.0;

    /// A skillion's top above footprint point p: h - rh * ((p · dirv) - p0) / span.
    [[nodiscard]] double sk_top(const V2& p) const noexcept;
};

/// Appends one tile's primitives, faces and n-gons, with the current building's cell, item, sun sample and slots.
class Builder {
public:
    explicit Builder(TileBuildings& o) noexcept : out(o) {}

    /// A face; its index.
    std::uint32_t face(const Face& f);
    /// Where a primitive is on its building, as K8's sample names it: the edge, and the place on it.
    struct At {
        std::uint32_t edge = 0;
        std::uint32_t part = 0;
        std::uint32_t ncol = 0; ///< a near cell's wall's columns and rows
        std::uint32_t nrow = 0;
    };
    /// A quad drawn with its same-colour edge.
    void edged(const std::array<V3, 4>& p, PrimKind kind, std::uint16_t flags, std::uint32_t face, std::uint32_t sun,
               std::uint16_t near, std::uint16_t far, At at);
    /// A face of three or four corners drawn without one.
    void plain(const std::array<V3, 4>& p, std::uint8_t corners, PrimKind kind, std::uint16_t flags, std::uint32_t face,
               std::uint32_t sun, std::uint16_t near, std::uint16_t far, At at);
    /// An n-gon `ring` (its plan `plan`, the same corners on the ground) filled as its triangles, of area `area`.
    void polygon(std::vector<V3> ring, std::span<const V2> plan, PrimKind kind, std::uint16_t flags, std::uint32_t face,
                 std::uint32_t sun, std::uint16_t near, std::uint16_t far, double area);
    /// A painter slot within an item, checked to fit the primitives' 16 bits.
    [[nodiscard]] static std::uint16_t slot(std::uint32_t value) noexcept;

    TileBuildings& out;
    std::uint8_t cell = 0;
    std::uint32_t item = 0;
    std::uint32_t sample = 0; ///< the next sun sample, in K8's order
    std::uint32_t near_slot = 0;
    std::uint32_t far_slot = 0;
    std::uint32_t boat_slot = 0;

private:
    std::vector<std::uint32_t> triangles_;
};

/// The walls of `b` (its cells in both variants and a skillion's walls), flagged `variant` (prim_flag::dry for a
/// houseboat's building).
void walls(Builder& out, const Building& b, std::uint16_t variant);

/// The triangles of a simple ring (earcut), as indexes into it, three a triangle; none for a degenerate ring.
void triangulate(std::span<const V2> ring, std::vector<std::uint32_t>& out);

} // namespace atlas::geometry
