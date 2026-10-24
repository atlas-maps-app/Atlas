// The ground of a render tile (plan-vector D10 "Ground" and "Water", D6, K9): stage T, built once per tile on a
// residency worker. What the renders draw on the ground (render/amsterdam.py:615 render, render/regions.py
// draw_under and draw_over), each feature as the port's own geometry in double (the land, the areas, the roads'
// ribbons, the region's rails, big roads and marine works, the water's pieces, the tram tracks, the bridges' lines),
// so the frame decides it per frame as the port does (passes/Ground.cpp: the margin cull, the area test, the kinds'
// order), and as the GPU's triangles and lines, written once into the geometry heaps; and the ground shadows before
// the sun (the solids' footprint hulls and raised outlines, the trees' crowns, from sun::solids_of), which the vertex
// stage moves by the moment's sun (passes/Shadows.cpp).
//
// The painter order: every element carries its group and its order in the group (slot_of below), from the ground
// keys the packer wrote (rendertiles' GroundKeys: stage, class and data order) and the kinds' and sub-elements' places,
// so the frame lays out its painter slots without sorting (passes/Ground.cpp).
#pragma once

#include "geometry/Ribbons.hpp"
#include "tiles/Container.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Style.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::sun {
struct TileCasters;
} // namespace atlas::sun

namespace atlas::geometry {

// ---------------------------------------------------------------- the ground's painter order

/// The ground's painter groups, in the renders' order (render's ground layers, amsterdam.py:615, then the marks and
/// the shadows): each group's elements take consecutive painter slots, `order · stride + sub` from the group's start.
/// A land kind is a group of its own (its rank among the kinds drawn is the frame's), each other group holds one
/// stage and class of the packer's ground keys.
namespace group {
inline constexpr std::uint16_t sky = 0;     ///< the sky's gradient (sub 0), the sun's glow (1), the city's glow (2)
inline constexpr std::uint16_t plate = 1;   ///< the ground plate
inline constexpr std::uint16_t canopy = 2;  ///< the forests' canopy (structural: a black fill at .55, K17's in full)
inline constexpr std::uint16_t water = 3;   ///< the water (sub: its group's elements, water_sub)
inline constexpr std::uint16_t area = 4;    ///< S['areas'], data order
inline constexpr std::uint16_t shadow = 5;  ///< the ground shadows' group
inline constexpr std::uint16_t road = 8;    ///< + class (ROAD_ORDER, 12): S['roads'], data order within a class
inline constexpr std::uint16_t over = 24;   ///< + class (rail 0, big 1, aero 2, marine 3, K15 4, park 5)
inline constexpr std::uint16_t marks = 32;  ///< + class (road marks 0, trams 1, canal names 2)
inline constexpr std::uint16_t quay = 40;   ///< the quay walls (K16), by their edge's order
inline constexpr std::uint16_t land = 64;   ///< + the land kind's place in the pack's `land` table
inline constexpr std::uint16_t count = 128; ///< groups the frame's table holds
inline constexpr std::uint16_t land_kinds = count - land;
} // namespace group

/// The water group's elements, in the renders' order inside `<g clip-path="url(#wclip)">` (amsterdam.py:457).
namespace water_sub {
inline constexpr std::uint8_t surface = 0;     ///< wgrad's rect (with weather), or every water ring's fill (without)
inline constexpr std::uint8_t reflections = 1; ///< K16
inline constexpr std::uint8_t tint = 2;        ///< the water colour's rect at .12 (.04 at night)
inline constexpr std::uint8_t glints = 3;      ///< K17
inline constexpr std::uint8_t glitter = 4;     ///< K16
inline constexpr std::uint8_t rings = 5;       ///< K16
inline constexpr std::uint8_t count = 8;
} // namespace water_sub

/// How many painter slots an element of a group's order takes (its sub-elements): a rail's ballast and its rails, a
/// big road's asphalt, edge lines and lane lines, a runway's pavement, centre line and ends, a road's marks (its
/// bridge's lines, then its setts, K15).
[[nodiscard]] constexpr std::uint32_t stride_of(std::uint16_t g) noexcept {
    if (g == group::sky) return 3;
    if (g == group::water) return water_sub::count;
    if (g == group::over + 0) return 2;
    if (g == group::over + 1 || g == group::over + 2) return 3;
    if (g == group::marks + 0) return 2;
    return 1;
}

/// A ground key's group (rendertiles' GroundKey: stage << 28 | class << 22 | order), for every stage but land's, whose
/// group is its kind's.
[[nodiscard]] std::optional<std::uint16_t> group_of_key(std::uint64_t key) noexcept;

// ---------------------------------------------------------------- colours

/// A colour the ground paints, 8 bits a channel: a literal (a = 255) or the moment's theme (a = 0, r its theme_colour).
struct Rgba {
    std::uint8_t r = 0;
    std::uint8_t g = 0;
    std::uint8_t b = 0;
    std::uint8_t a = 255;
    friend constexpr bool operator==(const Rgba&, const Rgba&) = default;
    [[nodiscard]] constexpr std::uint32_t packed() const noexcept {
        return std::uint32_t{r} | (std::uint32_t{g} << 8U) | (std::uint32_t{b} << 16U) | (std::uint32_t{a} << 24U);
    }
};

/// The theme's colours the ground reads, resolved per frame (passes/Ground.cpp): th['ground'] (the plate), th['road']
/// (a road without a mapped surface; at night five times darker again, as every road), th['water'] (the water without
/// weather, and its tint).
enum class ThemeColour : std::uint8_t { ground = 0, road = 1, water = 2 };

/// A theme colour as an Rgba.
[[nodiscard]] constexpr Rgba theme_colour(ThemeColour c) noexcept {
    return {.r = static_cast<std::uint8_t>(c), .g = 0, .b = 0, .a = 0};
}

/// A colour of the style (0xRRGGBB), opaque.
[[nodiscard]] constexpr Rgba rgba(tiles::StyleRgb c) noexcept {
    return {.r = static_cast<std::uint8_t>(c >> 16U),
            .g = static_cast<std::uint8_t>(c >> 8U),
            .b = static_cast<std::uint8_t>(c),
            .a = 255};
}

/// city.py's tohex(mulc(hx(c), k)): each channel times k, rounded as Python rounds (ties to even), clamped to 0-255.
[[nodiscard]] Rgba darken(Rgba c, double k) noexcept;

/// An element's paint by day and at night (the renders' night rule already applied where it is a fixed one).
struct Paint {
    Rgba day;
    Rgba night;
};

// ---------------------------------------------------------------- the elements

/// What a fill is, for the frame's decisions and the twin.
enum class FillKind : std::uint8_t {
    land,   ///< draw_under: gpoly(cam, poly, 0.01), margin 600, area over 0.3; grouped by kind
    canopy, ///< draw_under's canopy: gpoly(cam, poly, 0.02) of forest, wood and scrub
    area,   ///< S['areas']: gp(poly, colour, 0.02), margin 900, area over 0.2
    road,   ///< S['roads']: gp(ribbon(pts, w / 2), colour, 0.05, 0.45 on a bridge)
    rail,   ///< draw_over: a rail's ballast, gpoly(ribbon(pts, 1.6), 0.04) (crane rails: tested, not drawn)
    big,    ///< draw_over: a big road's asphalt, gpoly(ribbon(pts, w / 2), 0.06 + 0.5 · layer)
    marine, ///< draw_over: a breakwater, groyne, pier or quay, gpoly(closed ? pts : ribbon(pts, wdt / 2), 0.3)
    water,  ///< a water ring's piece in this tile (S['water'] or an island of S['water_holes'])
};

/// An element's place in the renders' ground order across scenes (ENGINE.md step 3: the ground's order across windows):
/// in its group, its list's own sort where the renders sort the list (the land's draw order, then its area, largest
/// first; a big road's layer; a road's marks by its class), then its OpenStreetMap element (type << 62 | id << 18 |
/// the ring's place among the element's), compared in that order. A tile that does not carry its elements (a pack of
/// one scene) has them 0 where its columns lack them: the frame takes its scene's order there.
struct OrderKey {
    std::uint64_t sort = 0;
    std::uint64_t sort2 = 0;
    std::uint64_t element = 0;
    friend constexpr auto operator<=>(const OrderKey&, const OrderKey&) = default;
};

/// One filled element of a tile.
struct Fill {
    FillKind kind = FillKind::area;
    std::uint16_t group = 0;
    std::uint8_t sub = 0;
    /// The tile cell its feature's reference point lies in (the cover draws a tile's visible cells).
    std::uint8_t cell = 0;
    std::uint32_t order = 0;
    /// Its ring for the frame's tests and the twin, [first, first + count) of the tile's points, at height y.
    std::uint32_t first = 0;
    std::uint32_t count = 0;
    double y = 0;
    /// The renders' cull: the margin (CSS px) and the least area (CSS px², strictly more is drawn).
    double margin = 900;
    double min_area = 0.2;
    /// Its ring's box on the ground (metres), for the frame's quick tests.
    double x0 = 0;
    double x1 = 0;
    double z0 = 0;
    double z1 = 0;
    /// The line it stands for (a ribbon's centreline, a region line's points), [line, line + line_count) of the
    /// tile's points, for draw_over's radius cut (near_radius); empty for the others.
    std::uint32_t line = 0;
    std::uint32_t line_count = 0;
    std::array<double, 4> near{0, -1, 0, 0}; ///< near_box of the line (empty while near[1] < near[0])
    /// Its paint (not drawn: crane rails, whose ballast is tested only).
    Paint paint;
    bool drawn = true;
    /// The water: its ring's place in S['water'] (or S['water_holes']), whether an island, its level (WATER_Y when the
    /// tile names none).
    std::uint32_t ring = 0;
    bool hole = false;
    double level = 0;
    /// Its index in the tile's GPU feature table (its visibility bit).
    std::uint32_t gpu = 0;
    /// Its place in the ground's order across scenes.
    OrderKey key;
};

/// A stroked element's width rule: a fixed width (CSS px), or regions.py's px(w) at the line's middle point.
struct Width {
    double css = 0;    ///< the fixed width; 0 for px
    double metres = 0; ///< px's w: max(0.25, w · focal / max(depth, 1)) at `at`
    P2 at;
};

/// What a stroke is, for the frame's decisions and the twin.
enum class StrokeKind : std::uint8_t {
    rail,   ///< draw_over: a rail's two rails (offset_line ±0.72 at 0.08), stroke .7
    edges,  ///< draw_over: a big road's edge lines (offset_line ±(w / 2 − 0.5) at y + 0.01), px(0.18) when over 0.3
    lanes,  ///< draw_over: a big road's lane lines (dashes 3 on 9 off), px(0.18), with more than one lane
    tram,   ///< groundMarks: a tram line's rails (ribbon(rl, 0.72)'s sides at 0.08), stroke .9
    bridge, ///< the road loop: a bridge's lines (ribbon(pts, w / 2 + 0.3)'s sides at 1.1), stroke 1.2, by day without
            ///< weather
    setts,  ///< the road loop: a sett road's courses across it every 0.6 m at y + 0.01, stroke .4 at opacity .6, by day
            ///< not at eye level, its first point within 500 m
};

/// A tile cell's box over its fills (metres, at their heights) and the widest of their margins: the frame tests a
/// whole cell before its fills (passes/Ground.cpp).
struct CellBox {
    double x0 = 0;
    double x1 = -1; ///< empty while x1 < x0
    double z0 = 0;
    double z1 = 0;
    double y0 = 0;
    double y1 = 0;
    double margin = 0;
    [[nodiscard]] bool empty() const noexcept { return x1 < x0; }
};

/// The cells a tile has at most (scene/Cells.hpp: 8 × 8).
inline constexpr std::size_t max_cells = 64;

/// One stroked element (one SVG path) of a tile: its polylines, painted alike.
struct Stroke {
    StrokeKind kind = StrokeKind::rail;
    std::uint16_t group = 0;
    std::uint8_t sub = 0;
    std::uint8_t cell = 0;
    std::uint32_t order = 0;
    /// Its polylines, [first, first + count) of the tile's polylines.
    std::uint32_t first = 0;
    std::uint32_t count = 0;
    Width width;
    Paint paint;
    /// Drawn only where the fill it belongs to is (a big road's lines: their asphalt's index in the tile's fills).
    std::optional<std::uint32_t> fill;
    /// For draw_over's radius cut: its feature's line, as Fill::line.
    std::uint32_t line = 0;
    std::uint32_t line_count = 0;
    std::array<double, 4> near{0, -1, 0, 0};
    /// A sett road's first point: its courses are drawn while its depth at the ground is under setts_depth.
    P2 from;
    /// Its place in the ground's order across scenes (its feature's).
    OrderKey key;
};

/// One polyline of a stroke: [first, first + count) of the tile's points at height y; a dash is a polyline of two.
/// A sett road's courses are one polyline of pairs, a line each (its two ends), with each line's reaches back: how many
/// lines before it lie within each of setts_tolerances of it (six bits a level), the lines the line stage looks back
/// at (shaders/ground/setts.slangh), [reach, reach + count / 2) of the tile's reaches, after its points in the lines
/// heap.
struct Polyline {
    std::uint32_t first = 0;
    std::uint32_t count = 0;
    double y = 0;
    bool pairs = false;
    std::uint32_t reach = 0;
};

/// The words a polyline takes in the lines heap: its points' two each, then a pair polyline's reaches.
[[nodiscard]] constexpr std::uint32_t polyline_words_of(const Polyline& p) noexcept {
    return (2 * p.count) + (p.pairs ? p.count / 2 : 0);
}

/// A sett road's courses (amsterdam.py's road loop): every setts_step metres along each segment, from its start.
inline constexpr double setts_step = 0.6;
/// The most lines a line looks back at (its reaches' six bits), and the world distances of its reaches (metres).
inline constexpr std::uint32_t setts_back = 63;
inline constexpr std::array<double, 5> setts_tolerances{0.5, 1.0, 2.0, 4.0, 8.0};
/// The depth of a sett road's first point under which its courses are drawn: min(500, cfg.get('sett_d', 500)), which no
/// view sets.
inline constexpr double setts_depth = 500.0;

/// How the sun moves a ground shadow's polygon by its offset −L.xz / L.y · h (render/amsterdam.py:742–771).
enum class ShadowMove : std::uint8_t {
    /// A prism's footprint hull (city.py's hull2, turned positive), swept along the offset: the hull of it and its
    /// moved copy, which the renders draw (hull2 of the footprint and its offset top).
    sweep = 0,
    /// A raised part's top outline, or a tree's crown disc (16 points), moved by the offset.
    moved = 1,
};

/// One caster's ground shadow before the sun (D8): its polygon, how the sun moves it and by which height's shadow,
/// the height it is drawn at, and where the renders' radius cut measures it from.
struct Shadow {
    ShadowMove move = ShadowMove::sweep;
    bool tree = false;
    /// Its points, [first, first + count) of the tile's shadow points.
    std::uint32_t first = 0;
    std::uint32_t count = 0;
    double h = 0;            ///< the height whose shadow moves it: the solid's top, 0.7 of a tree's height
    double y = 0.1;          ///< the height it is drawn at: 0.1, a tree's ground + 0.1
    P2 at;                   ///< the solid's centroid (render/real.py:92), the tree's trunk
    std::uint32_t owner = 0; ///< its solid's place in the tile's casters' solids, or its tree's in their trees
};

// ---------------------------------------------------------------- the GPU's words

/// One fill's record for the GPU (shaders/ground/ground.slang's FillRecord), five words.
struct FillGpu {
    float y = 0;                 ///< its height; a water piece's level
    std::uint32_t group_sub = 0; ///< group | sub << 16
    std::uint32_t order = 0;
    std::uint32_t day = 0; ///< Rgba::packed
    std::uint32_t night = 0;
};
static_assert(sizeof(FillGpu) == 20);

/// One polyline's record for the GPU (ground.slang's LineRecord), four words: its first point's word in the lines
/// heap's range, its point count, its height.
struct LineGpu {
    std::uint32_t first = 0; ///< words from the range's start: points are two f32, x then z, from the tile's origin
    std::uint32_t count = 0;
    float y = 0;
    std::uint32_t pad = 0;
};
static_assert(sizeof(LineGpu) == 16);

/// A word range of a heap range: [first, first + count) words, or of records.
struct Words {
    std::uint32_t first = 0;
    std::uint32_t count = 0;
};

/// A tile's ground as the GPU reads it: the ground heap's words (vertices: x, z as f32 from the tile's origin and the
/// fill's index; triangles by draw, top first; the fills' records) and the lines heap's (points, polylines).
struct GroundGpu {
    std::vector<std::uint32_t> ground;
    std::vector<std::uint32_t> lines;
    Words vertices; ///< three words each
    Words above;    ///< the opaque fills above the water (draw_over's, the roads, the areas), top first: indices
    Words below;    ///< the land below the water, top first: indices
    Words canopy;   ///< the canopy's triangles
    Words fans;     ///< the water's pieces' fans
    Words fills;    ///< FillGpu records
    Words points;   ///< two words each
    Words lines_at; ///< LineGpu records (the polylines, in the tile's order)
    /// The shadows (ground heap): their points (x, z as f32 from the tile's origin), their records (the first point,
    /// the count, h as f32, the flags, y as f32: five words) and their triangles over their corners, (shadow << 16) |
    /// corner (a swept hull's count + 2 corners: its points, its two turning points twice).
    Words shadow_points;
    Words shadows;
    Words shadow_draw;
};

/// The flags of a shadow's GPU record.
namespace shadow_flag {
inline constexpr std::uint32_t moved = 1; ///< ShadowMove::moved
inline constexpr std::uint32_t tree = 2;
} // namespace shadow_flag

/// Where a tile's ground lies in its heap ranges (GroundGpu's words without the words).
struct GroundLayout {
    Words vertices;
    Words above;
    Words below;
    Words canopy;
    Words fans;
    Words fills;
    Words points;
    Words lines_at;
    Words shadow_points;
    Words shadows;
    Words shadow_draw;
};

/// The ground of one tile.
struct GroundTile {
    /// Every ring's and line's points, metres in the pack's frame.
    std::vector<P2> points;
    std::vector<Fill> fills;
    std::vector<Stroke> strokes;
    std::vector<Polyline> polylines;
    /// The tile's south-west corner at street level in the pack's frame (its GPU positions are relative to it).
    P2 origin;
    /// Where its words lie in its heap ranges (the pass's, once packed).
    GroundLayout layout;
    /// Each polyline's first point's word in the lines range (pack's layout: the polylines' points in order).
    std::vector<std::uint32_t> polyline_words;
    /// The pair polylines' lines' reaches (Polyline::reach).
    std::vector<std::uint32_t> reaches;
    /// The ground shadows' casters (sun::solids_of: the solids the sun's march reads, then the trees), for the twin's
    /// shadows (sun::shadows_of: the port's own hulls for the frame's sun), and the region's no_shadows.
    std::shared_ptr<const sun::TileCasters> casters;
    std::size_t casters_bytes = 0;
    bool no_shadows = false;
    /// Each caster's place in the renders' shadow loops ((loop << 40) | its source order: S['buildings'] 0,
    /// S['parts'] 1, S['trees'] 2), by its place in the casters' solids and trees: the twin's order over every tile.
    std::vector<std::uint64_t> solid_order;
    std::vector<std::uint64_t> tree_order;
    /// The shadows before the sun: the solids' in the casters' order, then the trees'; their points (emptied once
    /// packed: the GPU and the twin draw them from their own); the box of their radius cut's points.
    std::vector<Shadow> shadows;
    std::vector<P2> shadow_points;
    std::array<double, 4> shadow_at{0, -1, 0, 0}; ///< x0, x1, z0, z1 of every Shadow::at (empty while x1 < x0)
    /// Each cell's box over its fills (the water's pieces aside, which every frame decides whole).
    std::array<CellBox, max_cells> cells{};
    /// Bytes it holds (the tile's native bytes).
    [[nodiscard]] std::size_t bytes() const noexcept;
};

/// The ground shadows of a tile's casters, before the sun: a prism's footprint hull (hull2, turned positive; two
/// points at least: a flat footprint's shadow is the hull of its offset), a raised part's top outline, a tree's crown
/// 16-gon around its trunk (render/amsterdam.py:742–771).
void build_shadows(const sun::TileCasters& casters, GroundTile& out);

/// Packs a tile's ground for the GPU: each fill's ring points as vertices, its triangles (a ribbon's quads, a ring's
/// earcut, a water piece's fan), its record; each polyline's points and record; each shadow's points, record and
/// triangles (a swept hull's fan over its points twice, a moved outline's earcut, a crown's fan).
[[nodiscard]] GroundGpu pack(const GroundTile& ground);

// ---------------------------------------------------------------- the ground's check on the phones' Bionic

/// The ground's check over the bundled Westerkerk (the app's tiles/amsterdam.atlas), for the emulator's and the phone's
/// Bionic (D16's L0 c, stage T compared by hash): every tile of its finest level built and packed at ladder level 0
/// (the living map's), its GPU words (the ground heap's, then the lines heap's) in the tiles' order, hashed with
/// SHA-256 (its first 8 bytes). The host build computes the same (tests/ground/geometry_test.cpp), and
/// `ground_check_expected` is its hash.
[[nodiscard]] tiles::Result<std::uint64_t> ground_check(const tiles::Pack& pack);

/// The host build's hash of the ground's check.
inline constexpr std::uint64_t ground_check_expected = 0xE891'6967'DF16'6E8AULL;

/// `ground <hash> ok`, or `ground <hash> FAIL expected <hash>`, or `ground unavailable: <why>`, for the bundled pack in
/// `pack`.
[[nodiscard]] std::string ground_line(const tiles::Pack& pack);

/// ground_line for the pack in `length` bytes of the open file `fd` from `offset` (an APK asset's descriptor).
[[nodiscard]] std::string ground_line(int fd, std::uint64_t offset, std::uint64_t length);

/// The ground of `tile` at ladder `level` (its areas simplified there; land, water and lines whole), in `head`'s frame,
/// in its pack's `style`, and its shadows' casters at that level (sun::solids_of). `origin` is the tile's south-west
/// corner at street level (the residency's).
[[nodiscard]] GroundTile build_ground(const tiles::Tile& tile, const tiles::RenderHead& head, const tiles::Style& style,
                                      std::uint8_t level, P2 origin);

} // namespace atlas::geometry
