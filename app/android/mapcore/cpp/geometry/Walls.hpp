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
//   - a houseboat drawn as a boat when the moment has weather (geometry/Houseboats.cpp), as a building when not;
//   - a tradition's kit's walls (trad_wall, with weather): a stucco ground floor's band and timber boards' corner
//     boards on each wall, its boards' lines and the shadow of its eaves (KitWall, which the frame lays each frame).
// Every primitive keeps what the frame needs to draw it as the port does: its face (base colour and normal, the light
// inputs), its sun sample (K8's index in the tile's samples, sun/Samples.hpp's order), its building's item, its painter
// slot within the item in each variant, and the conditions it is drawn under. Facing, the area test, the near plane and
// the detail band are decided per frame (the vertex stage, shaders/building/building.slangh), never here.
#pragma once

#include "geometry/RoofShapes.hpp"
#include "scene/ItemTable.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Style.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <optional>
#include <string_view>
#include <vector>

namespace atlas::geometry {

/// No sun sample (a face lit whatever the sun: a houseboat's deck), no polygon (a face of at most four corners).
inline constexpr std::uint32_t no_sample = 0xFFFF'FFFFU;
inline constexpr std::uint32_t no_polygon = 0xFFFF'FFFFU;

/// The renders' distance for a wall's cells (_draw_building: ncol × nrow cells where d < 460, else one).
inline constexpr double cells_reach = 460.0;

/// The renders' distance for the windows where a view names none (`th.get('_win_d', 390)`: cfg's win_d).
inline constexpr double windows_reach = 390.0;

/// The renders' distance for a church's own windows (`if church and d < 520`, with weather), which take its others'
/// place, and for a houseboat cabin's (draw_houseboat's `d < 520`).
inline constexpr double church_windows_reach = 520.0;

/// The renders' distances for deep windows (`deep = ... d < 160`: a reveal, the glass set back, a sill) and for sash
/// bars (`d < 140`).
inline constexpr double deep_windows_reach = 160.0;
inline constexpr double sash_reach = 140.0;

/// The renders' distances for a facade's flat detail (with weather): its plinth, cornice and shop glazing (`d < 760`),
/// the plinth's low variant (`0.6 if d < 300 else 0.9`) and the cornice (`d < 520`).
inline constexpr double facades_reach = 760.0;
inline constexpr double low_plinth_reach = 300.0;
inline constexpr double cornices_reach = 520.0;

/// The renders' distance for a hoist beam under an old gable (`d < 260`, with weather).
inline constexpr double beams_reach = 260.0;

/// A building's (or part's) owner kind in scene::owner_key: an area (AnchorItems' OwnerType.AREA).
inline constexpr std::uint8_t owner_area = 4;

/// How a face is lit, besides the theme: by its sample's sun bit (which K8 sets only where the face turns to the sun),
/// or whenever the sun is up (a houseboat's deck and cabin roof: `not night`; a kit's corner boards, whose
/// `light(..., facing and not night)` is light(..., True)'s wherever the sun is: lam = max(0, n · L) either way).
enum class Lit : std::uint8_t { sun = 0, day = 1 };

/// A face that is a detail of a tradition's kit on its wall (trad_wall's): its stucco ground floor's band, or a corner
/// board; drawn on its wall's plane (its face's: the wall's normal through the wall's test point) at a lead of its own
/// over the wall's cells, a corner board only where the view resolves it (focal / d · 0.16 > 0.5).
enum class KitPart : std::uint8_t { none = 0, band = 1, corner = 2 };

/// What lights a face (light.slang's light(): the base colour and the normal), and the point its facing is tested at.
struct Face {
    std::uint32_t base = 0; ///< 0xRRGGBB, surface_colour's (wall_rgb, roof_rgb) or the boat's own
    V3 normal;              ///< the port's unit normal
    V3 test;                ///< the point the port tests `vdot(n, C - p) > 0` at (its plane's, but a skillion roof's)
    Lit lit = Lit::sun;
    bool facing = true; ///< tested for facing (a deck and a cabin roof are drawn from either side)
    KitPart kit = KitPart::none;
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
    band,          ///< a kit's stucco ground floor on a wall (trad_wall's band)
    corner_board,  ///< a kit's corner board at either end of a wall
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
/// top, a skillion roof, a mansard's top), a houseboat's cabin roof (.4 px #2A2C30), each in the pack's style (the
/// frame block's strokes). The roofs' strokes draw them; the twin emits them.
inline constexpr std::uint16_t stroke_fine = 128;
inline constexpr std::uint16_t stroke_wide = 256;
inline constexpr std::uint16_t stroke_cabin = 512;
/// An n-gon's triangle's edges that lie on its polygon's ring, which its stroke follows: its first edge (corner 0 to
/// 1), its second (1 to 2), its third (2 back to 0); outline << k for edge k. The cuts between its triangles have none.
inline constexpr std::uint16_t outline = 1024;
/// A kit roof's slope or hip that overhangs its walls (its eaves): its depth leads them where it passes their top.
inline constexpr std::uint16_t eaves = 8192;
/// A wall with a kit's details on it (its band, its boards, its eaves' shadow): its near cells' depth leads count
/// modulo 8, under the details' leads.
inline constexpr std::uint16_t kit = 16384;
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

/// How a wall's windows are laid out (_draw_building's window rows): the plain sash windows, a modern building's wide
/// ones, or the tall French windows of a Paris stone front (WIN_STYLE == 'paris'); or a houseboat cabin's row of panes
/// (draw_houseboat's walls(cab, ..., win=True)).
enum class WindowStyle : std::uint8_t { plain = 0, modern = 1, paris = 2, cabin = 3 };

/// A facade wall's flags: what the frame decides by its moment and distance. The last three are its flat detail with
/// weather within 760 m (_draw_building's, not a part's): a dark plinth along its foot (its walls on the street), a
/// white cornice along its top (an old house's, under a mansard, where a kit gives the building one: a London
/// terrace's; a wall over 5 m, within 520 m), its shop glazing (a shop floor's wall over 2.4 m).
namespace window_flag {
inline constexpr std::uint8_t shop_floor = 1; ///< shops, cafés or hotels: no windows on the ground floor with weather
inline constexpr std::uint8_t warehouse = 2;  ///< green warehouse shutters, not glass, with weather
inline constexpr std::uint8_t church = 4;     ///< church windows instead with weather within 520 m
inline constexpr std::uint8_t plinth = 8;
inline constexpr std::uint8_t cornice = 16;
inline constexpr std::uint8_t glazing = 32;
} // namespace window_flag

/// What a building's walls show of their material from a distance (render/amsterdam.py's material_face, with weather,
/// on each wall over 2 m within 1.35 times the view's win_d, after its shop glazing and before its windows): a glass
/// curtain wall's glass, floors and mullions, the ribs of metal cladding, the iron balconies of a tall stone front, the
/// open decks of a parking garage. Which one is the building's (material_of: its material drawn and its look's kind);
/// how much of it is drawn is the view's (its storeys' height and its spacing in pixels).
enum class Material : std::uint8_t { none = 0, glass = 1, metal = 2, balconies = 3, parking = 4 };

/// The painter slots a wall's material face takes: a glass wall's glass, then its lines; any other one's path (a
/// parking garage's decks, which never meet, in one).
[[nodiscard]] constexpr std::uint32_t material_slots(Material m) noexcept {
    return m == Material::none ? 0U : (m == Material::glass ? 2U : 1U);
}

/// A wall's facade: its windows (render/amsterdam.py's _draw_building: a row per level, bays by the wall's length),
/// drawn within the view's win_d, and its flat detail (window_flag's last three). Window k = r · cols + c is the glass
/// of its row r and bay c, lit or dark by its own sun sample. The rows are every level's, the shop floor's too, as K8's
/// samples are; the frame leaves the shop floor's out. The tile's windows are its window group of sun samples, one each
/// in the same order (sun/Samples.hpp: the records, their edges, the rows, the bays), so a window's place among the
/// tile's is its sample's in the group. A wall with flat detail and no windows has no rows. A houseboat cabin's wall
/// (WindowStyle::cabin, drawn with the boat) has one row of panes, never lit: they have no samples and are not among
/// the tile's windows. A church's wall has its church windows besides (geometry/ChurchWindows.hpp: how many, and each
/// one's transoms), which the frame draws instead of its windows with weather within 520 m, at its windows' slots; a
/// church's wall with church windows and no windows of its own has its windows' slots for them. A wall with a material
/// face (Material) has its slots between its shop glazing's and its windows', and its rows are its storeys where it has
/// no windows (max(1, levels): its floors and decks), with no bays.
struct WindowWall {
    std::uint32_t wall = 0;      ///< its wall's one-cell primitive in the edged list (its corners, face and item)
    std::uint32_t first = 0;     ///< its windows among the tile's: [first, first + levels · cols) (a cabin's: none)
    std::uint16_t levels = 0;    ///< its rows (building:levels, mapped or guessed; at most max_window_levels)
    std::uint16_t cols = 0;      ///< its bays
    std::uint16_t near_slot = 0; ///< its first painter slot within the item, near and far: its shop glazing's, then
    std::uint16_t far_slot = 0;  ///< its material face's (material_slots), then its windows' (window_slots); a slot
                                 ///< only for what it has (a cabin's: one, its boat slot)
    std::uint8_t cell = 0;       ///< its building's cell in the tile
    WindowStyle style = WindowStyle::plain;
    std::uint8_t flags = 0;             ///< window_flag
    std::uint16_t church_windows = 0;   ///< a church's: its church windows
    std::uint16_t church_transoms = 0;  ///< and each one's transoms
    Material material = Material::none; ///< its material face (a wall over 2 m of a building with one)
    double fh = 0.0;        ///< its storeys' height, (wall_top - y0) / max(1, levels), in double as the port
    double elen = 0.0;      ///< its length, in double as the port divides it
    std::uint32_t ribs = 0; ///< a metal wall's ribs: tohex(mulc(base, 0.62)), 0xRRGGBB
};

/// A hoist beam under an old gable (render/amsterdam.py's _draw_building: a gabled roof's gable end on a building
/// before 1850, without a tradition's roof): a stroke 0.7 m out from the gable along its normal, a quarter of the
/// roof's height under its top, drawn with weather within 260 m where the gable turns to the eye, at its slot among its
/// roof's slopes (the port appends it to `slopes` as it meets the gable, after the slopes of the edges before). A
/// tradition's kit gives its hoist beams the same stroke (trad_roof's, where the kit gives the building one), after
/// every slope of its roof, from its gable's outward normal.
struct RoofBeam {
    std::uint32_t item = 0;      ///< its building's item
    std::uint32_t face = 0;      ///< its gable's face, whose turn it takes
    V3 from;                     ///< (apex.x, h - rh · 0.25, apex.z)
    V3 to;                       ///< from + nr · 0.7, nr the gable's normal
    std::uint16_t near_slot = 0; ///< its painter slot within the item, near and far
    std::uint16_t far_slot = 0;
};

/// A slope's tile courses in a tradition's kit (render/amsterdam.py's trad_roof, its `rows`): the lines parallel to
/// its eaves at t = j / k (j = 1 .. k - 1) from the eave corners pa and pb up to qa and qe (qb on a quad, qa on a hip's
/// triangle), drawn with weather by day on a roof of tiles (rstep 0.33) or slates (0.26) where the view resolves them
/// (focal / d · rstep > 2), its slope turned to the eye; at its slot after its roof's slopes and hoist beams.
struct RoofCourses {
    std::uint32_t item = 0; ///< its building's item
    std::uint32_t face = 0; ///< its slope's face, whose turn it takes
    V3 pa;
    V3 pb;
    V3 qa;
    V3 qe;
    std::uint32_t k = 0; ///< int(dist(pa, qa) / rstep): k - 1 courses
    double rstep = 0.0;
    std::uint32_t rgb = 0; ///< tohex(mulc(rbase, 0.55)), 0xRRGGBB: its roof's colour darkened
    std::uint16_t near_slot = 0;
    std::uint16_t far_slot = 0;
};

/// A kit roof's lit ridge (trad_roof's last element: "the ridge catches the light, as on a model"): from one end of
/// the ridge to the other at the roof's top, drawn with weather by day, at its roof's last slot.
struct RoofRidge {
    std::uint32_t item = 0;
    V3 from;
    V3 to;
    std::uint16_t near_slot = 0;
    std::uint16_t far_slot = 0;
};

/// A wall's translucent details in a tradition's kit (render/amsterdam.py's trad_wall, with weather by day, where its
/// wall turns to the eye), which the frame lays each frame on its wall's plane:
///   - its timber boards' lines, where the kit gives the building boards: nb = int(elen / spacing) boards, a line at
///     u = k / nb (k = 1 .. nb - 1) from 0.25 m over its foot to 0.05 m under its top, 0.02 m out, where the view
///     resolves them (focal / d · spacing > 2.2), .45 px wide at .32 in its wall's drawn colour darkened
///     (tohex(mulc(col_draw or #888888, 0.62))), butt-capped, the lines whose both ends project; at its slot after its
///     band's;
///   - the shadow of its eaves, where the kit's roof overhangs it (the kit's eaves on a hipped roof, or on a gabled one
///     along its ridge: |e · u| / elen > 0.7): quad_at(a, b, top - min(1.1, eaves · 1.7), top, 0, 1, 0.025, n) in
///     #141822 at .26, at its slot after its corner boards'.
struct KitWall {
    std::uint32_t item = 0;
    std::uint32_t face = 0; ///< its wall's face: its turn, and the plane its details draw on
    V2 a;                   ///< its footprint edge's start, and the edge to its end (bb - a)
    double ex = 0.0;
    double ez = 0.0;
    V3 nrm;
    double y0 = 0.0;
    double top = 0.0;         ///< wall_top
    std::uint32_t boards = 0; ///< nb: no lines below 2
    double spacing = 0.0;
    std::uint32_t rgb = 0; ///< its lines' colour, 0xRRGGBB
    bool shadow = false;
    std::array<V3, 4> shade{};    ///< its eaves' shadow's corners
    std::uint16_t lines_near = 0; ///< its lines' painter slot within the item, near and far; its shadow's
    std::uint16_t lines_far = 0;
    std::uint16_t shadow_near = 0;
    std::uint16_t shadow_far = 0;
};

/// The most rows a facade wall's record holds (windows.slangh's BUILDING_WINDOW_LEVELS).
inline constexpr std::uint16_t max_window_levels = 0x0FFF;

/// A building without a plinth or a cornice (TileBuildings::item_facades).
inline constexpr std::uint32_t no_facade = 0xFFFF'FFFFU;

/// The painter slots a wall's windows take within its item, in each variant, as the port paints its windows' paths:
/// the deep windows' reveals, the dark windows, the lit ones, the sash bars, the deep windows' sills.
inline constexpr std::uint32_t window_slots = 5;

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
    std::uint32_t record = 0; ///< its place in its layer of the tile
    std::int64_t id = 0;      ///< the OSM id
    bool relation = false;    ///< the id is a relation's
    std::int64_t outline = 0; ///< the building it draws: its own id, or a part's outline's where the part names one
    bool outline_relation = false; ///< that building is a relation
    std::uint16_t near_slots = 0;  ///< the painter slots its primitives take, near and far and as a boat
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
    /// The windowed walls, by cell (cell c's are [window_walls_first[c], window_walls_first[c + 1])), each wall's
    /// windows after the last's; how many windows in all (the tile's window group of sun samples holds as many).
    std::vector<WindowWall> window_walls;
    std::vector<std::uint32_t> window_walls_first;
    std::uint32_t windows = 0;
    /// Each item's plinth's and cornice's painter slots (the plinth's near | far << 16, the cornice's the next), after
    /// all its walls; no_facade where it has neither.
    std::vector<std::uint32_t> item_facades;
    /// The hoist beams under old gables and on a kit's gables, in the items' order.
    std::vector<RoofBeam> beams;
    /// A kit roof's tile courses, a record a slope, and its lit ridges, in the items' order.
    std::vector<RoofCourses> courses;
    std::vector<RoofRidge> ridges;
    /// A kit's walls' boards' lines and eaves' shadows, a record a wall, in the items' order.
    std::vector<KitWall> kit_walls;
    /// The tile's sun samples the buildings take (K8's count before the tile's trees, quays and windows).
    std::uint32_t samples = 0;
};

/// The region's values the buildings read (sun/Samples.hpp's Region: the port's module state after the region's load).
struct BuildingRegion {
    double water_y = -1.5;  ///< WATER_Y: a houseboat's waterline
    std::uint32_t hull = 0; ///< the style's houseboats' hull (0xRRGGBB)
    std::uint32_t deck = 0; ///< and deck
    bool paris = false;     ///< WIN_STYLE == "paris": tall French windows in stone fronts
    /// The building tradition's kit, where the pack has one (its style's `tradition`): its eaves' overhang, and its
    /// stucco ground floor and timber boards where it has them.
    std::optional<double> kit_eaves;
    std::optional<tiles::KitBand> kit_band;
    std::optional<tiles::KitBoards> kit_boards;
};

/// What stage T is given.
struct BuildingInput {
    const tiles::Tile* tile = nullptr;
    const tiles::RenderHead* head = nullptr;
    std::uint8_t level = 0; ///< the ladder index the rings are simplified at and the variants loaded at
    BuildingRegion region;
    std::uint16_t kind = 0; ///< the items' kind (the frame's Selectors row)
    /// Runs `pieces` pieces of the work, piece(0) to piece(pieces - 1), on several threads, returning once all are done
    /// (scene::TileBuild::share: the residency's idle workers, ENGINE.md gain 9): a dense tile's records are walked in
    /// as many blocks as `blocks` allows (at most 8, of about 192 records each), then joined in order. None, or one
    /// block: one walk over them all.
    std::function<void(std::size_t pieces, const std::function<void(std::size_t)>& piece)> share;
    std::size_t blocks = 1;
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
    bool has_levels = false; ///< building:levels, mapped or guessed (a building without has no window rows)
    std::int64_t levels = 0;
    std::optional<std::string_view> mat_draw;  ///< the wall material drawn (a Paris stone front's French windows)
    std::optional<std::string_view> kind;      ///< the generic look's kind (home, office, parking, ...)
    bool no_win = false;                       ///< no window rows (halls, tanks, glass walls)
    std::int64_t year = 0;                     ///< the year start_date begins with (0: none)
    std::optional<std::string_view> rmat_draw; ///< the roof material drawn (a kit roof's tile courses)
};

/// trad['eaves'] of a building with values `v` in a kit of eaves `eaves`: the kit's where the loader's roof (the
/// tile's, before a quadruple_saltbox is drawn as hipped) is gabled or hipped, else 0.
[[nodiscard]] double kit_eaves_of(const Values& v, std::optional<double> eaves) noexcept;

/// The material face a building's walls show (material_face's chain without its view's conditions, which never pass
/// a building from one kind to another): glass where its material drawn is glass; metal where it is metal and its kind
/// none or an industrial one; balconies on a stone, limestone or plaster front of 5 storeys or more of a home, an
/// office or a hotel; a parking garage's decks.
[[nodiscard]] Material material_of(const Values& v) noexcept;

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
    std::uint32_t windows = 0;              ///< the next window, in the order of the tile's window group of sun samples
    bool paris = false;                     ///< the region's WIN_STYLE is Paris's
    std::optional<double> kit_eaves;        ///< the region's tradition's eaves, where it has a kit (BuildingRegion's)
    std::optional<tiles::KitBand> kit_band; ///< and its band and boards, where it has them
    std::optional<tiles::KitBoards> kit_boards;
    std::uint32_t facade = 0; ///< the current building's plinth's and cornice's slots (TileBuildings::item_facades)
    /// A level's tile (a frame cell's L13 or L12, a region's L12): drawn beyond the finest level's reach (4.7 km), so
    /// its walls are one cell each, without the near cells (drawn within 460 m) nor the windowed walls (the windows,
    /// shop glazing, plinths and cornices, a church's windows and the material faces) and a kit's details on them
    /// (within 760 m at most).
    bool lean = false;

private:
    std::vector<std::uint32_t> triangles_;
    std::vector<std::uint8_t> used_; ///< an n-gon's ring points its triangles use
};

/// The walls of `bl` (its cells in both variants, a skillion's walls and each wall's windows) into `b`, flagged
/// `variant` (prim_flag::dry for a houseboat's building).
void walls(Builder& b, const Building& bl, std::uint16_t variant);

/// The bays of a wall `elen` m long in `style` (`int(elen / (2.9 if paris else 3.4 if modern else 2.1))`, at least
/// one).
[[nodiscard]] int window_cols(double elen, WindowStyle style) noexcept;

/// The triangles of a simple ring (earcut), as indexes into it, three a triangle; none for a degenerate ring.
void triangulate(std::span<const V2> ring, std::vector<std::uint32_t>& out);

} // namespace atlas::geometry
