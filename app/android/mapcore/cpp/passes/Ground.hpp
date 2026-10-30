// The ground (plan-vector D10 "Ground" and "Water", D5.12's passes 3 and 4, D6, D8's A1 shadows; K9): one registered
// pass, "ground", drawing everything the renders draw on the ground before the objects: the sky's band, the ground
// plate, the land cover and its canopy, the water (its two clips, its gradient and its tint), the areas, the roads,
// the region's rails, big roads and marine works, the tram tracks and the bridges' lines, and the sun's shadows on the
// ground as a stencil union. Its parts live in files of their own (Ground.cpp: the pass, the fills, the plate, the
// decisions and the painter order; Roads.cpp: the roads' and the fills' twin; Lines.cpp: the lines; Water.cpp: the
// water; Shadows.cpp: the shadows; Sky.cpp: the sky), and share one painter order, so every element of the ground takes
// the slot the renders' order gives it (render/amsterdam.py:615 render; geometry/Ground.hpp's groups).
//
// Each frame, on the render thread:
//   - select: the renders' per-camera decisions over the fills of the cover's visible cells (stage F, in double, the
//     port's arithmetic): a fill is drawn where cam.poly keeps it (its near-clipped projection reaches within the
//     margin of the frame) and its area exceeds the least (0.2 or 0.3 CSS px²); the land kinds take the order of their
//     first drawn ring; the water's rings are decided whole, over their pieces; the lines by draw_over's and the
//     marks' rules; the shadows by the radius cut. Exact when the camera rests (every fill projected, as the port does,
//     after a box test that only drops what surely lies beyond the margin), once for a camera: the decisions are kept
//     per tile while the camera stays (DecisionKey), so a frame at rest decides only a tile that lands, and keeps
//     everything when nothing changed. While it moves the box test alone, so a fill it keeps is drawn even under the
//     least area (a fifth of a CSS pixel). The ground's painter slots are laid out from the decisions (ground_slots);
//   - update: the frame's words (the tile table, the slot table, the theme's colours, each tile's visibility bits, the
//     lines' runs and segments), written into this frame slot's buffer;
//   - record: the opaque ground top first (above the water, the water, the land, the plate, the sky), then the
//     translucent elements in the renders' order (the canopy, the water's tint and the end of its clip, the lines, the
//     shadows), each phase at its order among every pass's (ground_order below). Where the moment blurs the shadows,
//     an offscreen phase first draws them into a mask on the port's filter grid and blurs it (Shadows.cpp).
//
// The twin (emit) writes the same elements as the port's SVG elements, at the frame's painter slots.
#pragma once

#include "frame/FrameBlock.hpp"
#include "frame/Pass.hpp"
#include "geometry/Ground.hpp"
#include "gpu/Descriptors.hpp"
#include "gpu/Memory.hpp"
#include "style/Theme.hpp"
#include "sun/BitTiles.hpp"
#include "twin/Emit.hpp"
#include "twin/Twin.hpp"
#include "view/Camera.hpp"

#include <array>
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <span>
#include <string_view>
#include <utility>
#include <vector>

namespace atlas::scene {
struct ResidentTile;
struct PassTile;
} // namespace atlas::scene

namespace atlas::passes {

/// The orders of the ground's translucent phases among every pass's (frame::Phase::order in the translucent slot,
/// the renders' order): other packages' ground elements take theirs between (K16 the water's reflections, glitter and
/// rings, K17 its glints, K15 the setts, K13 the canal names, K16 the quays, K18 the night's pools); the objects'
/// translucent elements come after `objects`.
namespace ground_order {
inline constexpr std::int32_t canopy = 100;
inline constexpr std::int32_t water_reflections = 210; ///< K16
inline constexpr std::int32_t water_tint = 220;
inline constexpr std::int32_t water_glints = 230;  ///< K17
inline constexpr std::int32_t water_glitter = 240; ///< K16
inline constexpr std::int32_t water_rings = 250;   ///< K16
inline constexpr std::int32_t water_end = 290;
inline constexpr std::int32_t over = 500;
inline constexpr std::int32_t designators = 510; ///< the runways' designators (passes/GroundText.cpp)
inline constexpr std::int32_t marks_road = 600;
inline constexpr std::int32_t marks_setts = 610; ///< K15
inline constexpr std::int32_t marks_tram = 620;
inline constexpr std::int32_t canal_names = 650; ///< K13
inline constexpr std::int32_t shadows = 700;
inline constexpr std::int32_t quays = 800; ///< K16
inline constexpr std::int32_t pools = 900; ///< K18
inline constexpr std::int32_t objects = 1000;
} // namespace ground_order

/// The stencil bits the ground uses (painter.slang's GROUND_STENCIL_BITS): the water's winding count (three bits,
/// wrapping) and its w0 clip, the shadows' union, the canopy's union.
namespace ground_stencil {
inline constexpr std::uint32_t water_count = 0x07;
inline constexpr std::uint32_t shadows = 0x08;
inline constexpr std::uint32_t canopy = 0x10;
inline constexpr std::uint32_t water_w0 = 0x40;
} // namespace ground_stencil

/// How many vertices the record of a quay wall's or a reflection's face draws (ground.slang's FACE_VERTICES: its
/// polygon grown by its stroke and a pixel, a fan of up to three triangles; a circle's or a streak's box, two).
inline constexpr std::uint32_t face_vertices = 9;

/// A draw's flags (shaders/ground/ground.slang's GROUND_ flags).
namespace ground_flag {
inline constexpr std::uint32_t night = 1;       ///< the night colours
inline constexpr std::uint32_t water_level = 2; ///< water_vs: the pieces at their level (wclip)
inline constexpr std::uint32_t visible_all = 4; ///< every fill drawn
inline constexpr std::uint32_t layer = 8;       ///< into the reflections' layer (Ripple.cpp): upright, own colours
} // namespace ground_flag

/// A water piece seen from the frame's camera at one height: at rest Camera.poly's point count, shoelace sum and box
/// (no points where its box surely lies beyond the margin); while the camera moves, whether its box may reach the
/// frame.
struct PieceView {
    std::uint32_t points = 0;
    bool maybe = false;
    double sum = 0;
    double min_x = 0;
    double max_x = 0;
    double min_y = 0;
    double max_y = 0;
};

/// One water piece of a tile, at the street (w0, or the plain water) and at its level (wclip).
struct WaterPiece {
    std::uint32_t fill = 0;
    PieceView street;
    PieceView level;
};

/// One tile's decisions at one camera: its fills' decision bytes (the water's come from every tile's pieces together),
/// its water pieces' views, its strokes' and their widths. While the camera rests the pass keeps them, each with the
/// tile's ground (held, so that its address names that build while it is kept) and the cells they were made for, and
/// decides only the tiles that land.
struct TileDecisions {
    std::shared_ptr<const void> ground;
    std::uint64_t cells = 0;
    std::vector<std::uint8_t> drawn;
    std::vector<WaterPiece> water;
    std::vector<std::uint8_t> stroke_drawn;
    std::vector<double> stroke_width;
    std::vector<std::uint8_t> shadow_drawn; ///< per shadow: within the radius cut (empty: no shadows this frame)
    bool used = false;
};

/// One tile the frame draws the ground of: its resident tile, its ground (stage T's, PassTile::cpu) and heap ranges,
/// its visible cells, its place relative to the eye, its decisions and where they lie in the frame's.
struct DrawnTile {
    const scene::ResidentTile* tile = nullptr;
    const scene::PassTile* pass = nullptr;
    const geometry::GroundTile* ground = nullptr;
    std::uint64_t cells = 0;
    shaders::TilePlace place{};
    const TileDecisions* decided = nullptr;
    std::uint32_t decisions = 0; ///< the first of its fills' bytes in GroundFrame::drawn
    std::uint32_t strokes = 0;   ///< the first of its strokes' in GroundFrame::stroke_drawn
    std::uint32_t shadows = 0;   ///< the first of its shadows' in GroundFrame::shadow_drawn
    /// Its sun bits this frame (the cover's: K8's, on the CPU, with its quay walls' pieces), and where its texels begin
    /// in set 1's sun_bits with their fade's weight (scene::no_sun: none yet).
    std::optional<sun::TileView> sun;
    std::uint32_t sun_base = 0xFFFF'FFFFU;
    float sun_weight = 1.0F;
};

/// One element of the quay walls (water_layer's quay walls, in water_edges' order, a piece's in turn), each its own
/// path: a piece's wall (its own .3 stroke edging it), a tall wall's lit or unlit bands (TONE: each band of each of its
/// columns its sample lights or not, one path of them), the dark band at its waterline, its coping; its drawn tile, its
/// edge there, its piece and samples (the sun's), its colour as drawn now.
struct QuayFace {
    enum class Kind : std::uint8_t { wall, lit_bands, dark_bands, waterline, coping };
    Kind kind = Kind::wall;
    std::uint32_t tile = 0;
    std::uint32_t edge = 0;
    double ax = 0;
    double az = 0;
    double bx = 0;
    double bz = 0;
    std::uint32_t bands = 0; ///< a bands' path's first sample, `columns` × 3 of them
    std::uint32_t columns = 0;
    std::uint32_t colour = 0; ///< 0xRRGGBB, as the twin draws it (its sample's bit now)
    /// Its light lit and unlit (0xRRGGBB; a waterline's alike), and its sample's place where its light follows one (a
    /// wall's, a coping's), for the GPU's fade between a tile's bits.
    std::uint32_t lit = 0;
    std::uint32_t unlit = 0;
    std::uint32_t sample = 0;
    bool sampled = false;
};

/// One element of the water's reflections (Amsterdam.reflections, drawn far to near in `<g filter="url(#ripple)"
/// opacity=".92">`, .95 at night), each its own path: a building's mirrored wall (its own .3 stroke edging it), a
/// gable's mirrored triangle, a building's window rows (one path at .55), or a tree's five circles (`<g
/// filter="url(#leaf)">`); then at night the streaks, each an ellipse of the streak gradient; its drawn tile, its
/// faces, circles or streak (GroundFrame::reflection_faces, reflection_circles and reflection_streaks: a wall's or a
/// gable's one face, the rows' several), its colour as drawn now, and where its light follows a sample (a wall's, a
/// gable's), its lit and unlit colours and that sample.
struct Reflection {
    enum class Kind : std::uint8_t { wall, gable, windows, tree, streak };
    Kind kind = Kind::wall;
    std::uint32_t tile = 0;
    std::uint32_t first = 0;
    std::uint32_t count = 0;
    std::uint32_t colour = 0; ///< 0xRRGGBB, as the twin draws it (its sample's bit now)
    std::uint32_t lit = 0;
    std::uint32_t unlit = 0;
    std::uint32_t sample = 0;
    bool sampled = false;
    /// A tree's leaf filter region in whole device px (x0, y0 in it; x1, y1 past it): `leaf`'s -15% and 130% of its
    /// five circles' box, as SvgFilterRaster takes a region.
    std::array<std::int32_t, 4> region{};
    /// A tree's place in the leaves' field (x | y << 16, each under 4096: leaf_anchor), which its region's centre
    /// reads, so its leaves move with it.
    std::uint32_t anchor = 0;
};

/// Where a reflected tree reads the leaves' field: a hash of its id (x | y << 16, each under 4096). The renders read it
/// at the screen's own pixels, which a moving map slides under the trees; the frame reads each tree's from its own
/// place (the user, 27 October: "The leafs and water streaks should be drawn with the object like buildings are").
[[nodiscard]] std::uint32_t leaf_anchor(std::int64_t id) noexcept;

/// The water's streaks' map: where the glints and the ripple read their fields, fixed to the water as a building is
/// fixed to its place (the user, 27 October: "The leafs and water streaks should be drawn with the object like
/// buildings are"). The renders read them at the screen's own pixels, which a moving map slides under the water; the
/// frame lays them on the water's plane (height `water_y`) as the Westerkerk home sees them at its target
/// (amsterdam.py's main camera: yaw 52, pitch 38, dist 430, fov 36, H 915: 3.27 CSS px a metre across its view and
/// that times sin 38 down it, a field's device px per CSS px), so panning, turning and zooming carry them with the
/// water and the home shows them at the renders' scale. For an upright device point (x, y) of camera `cam`'s frame at
/// `density` the fields' point is (r0 · (x, y, 1), r1 · (x, y, 1)) / (r2 · (x, y, 1)), the rows' nine floats returned
/// (r2's negative below the horizon, where alone the plane lies), reduced by whole mirror periods of the fields (twice
/// their `width` and `height`, device px: fx_wrap) about the frame's middle so that the floats keep their precision.
[[nodiscard]] std::array<float, 9> water_map(const view::Camera& cam, double water_y, double density,
                                             std::uint32_t width, std::uint32_t height);

/// The fields' point (device px) of the water under upright device point (x, y) by map `m` (water_map's), in float as
/// the shaders work it out (fx_water_point), and whether the water's plane lies there (below the horizon).
struct WaterPoint {
    float x = 0.0F;
    float y = 0.0F;
    bool on = false;
};
[[nodiscard]] WaterPoint water_point(const std::array<float, 9>& m, float x, float y) noexcept;

struct GroundFrame;
/// The water's height where frame `f` draws it (its first tile's water level; 0 without a tile), the streaks' map's
/// plane.
[[nodiscard]] double water_level(const GroundFrame& f) noexcept;

/// A face of a reflection: its corners in the frame's metres (a gable's three, else four), and the box of its
/// Camera.poly's points (CSS px: min x, min y, max x, max y), which the ripple's layer covers.
struct ReflectionFace {
    std::array<view::Vec3, 4> q{};
    std::uint32_t n = 4;
    std::array<double, 4> box{};
};

/// Amsterdam.reflections' mirrors of a ground tile whatever the camera, as the tile is built (into its mirror heads and
/// walls: each mirrored building's and part's centroid and box, its walls' heights mirrored in the water's plane, its
/// walls of 0.3 m or more with their normals, their samples' places, their gables and window rows), so a frame takes
/// only the radius cut, the depth, the mirror's box against the frame, the facing test and face() of each.
void prepare_reflections(geometry::GroundTile& g);

/// The reflections' work that does not move with the camera, kept from frame to frame (Reflections.cpp): for the drawn
/// tiles (their grounds in the frame's order) their mirrors, trees, street lamps and lit roads each in its scene's
/// order across them, the trees within 9 m of their quay edges and the lit roads' points within 12 m; for each drawn
/// tile its walls' colours in the moment's light, each made as the wall is first drawn in it, its reflected trees'
/// draws, and from its first night its water rings with their sides by bands.
struct ReflectionsKept {
    /// A mirror, tree, street lamp or lit road of a drawn tile: its place in the frame's tiles, its index there.
    struct Ref {
        std::uint32_t tile = 0;
        std::uint32_t index = 0;
    };
    /// A water ring of a drawn tile (a water fill that is no island) with its sides filed by bands of z, so the night
    /// streaks' test of a point on the water reads only the sides of the point's band: its fill, its bands (from z0,
    /// `step` metres each, [at, at + bands] of the tile's band_at, each band's first side there and the next band's
    /// after it), the sides by their place i in the ring (its other end the one before).
    struct WaterRing {
        std::uint32_t fill = 0;
        double z0 = 0;
        double step = 1;
        std::uint32_t bands = 1;
        std::uint32_t at = 0;
    };
    /// A drawn tile's own: its ground (held while it is drawn); each wall's colour lit and unlit (0xRRGGBB, made in the
    /// moment's generation `made`, 0: not yet); whether each of its lit roads' points lies within 12 m of a quay edge;
    /// each tree's five circles' fifteen draws from random.Random(its id) (its first in `draws`, -1: not drawn yet);
    /// its water rings by bands (made the first night it is drawn).
    struct Tile {
        std::shared_ptr<const void> ground;
        std::vector<std::uint32_t> lit;
        std::vector<std::uint32_t> unlit;
        std::vector<std::uint32_t> made;
        std::vector<std::uint8_t> road_near;
        std::vector<std::int32_t> draws_at;
        std::vector<double> draws;
        bool banded = false;
        std::vector<WaterRing> rings;
        std::vector<std::uint32_t> band_at;
        std::vector<std::uint32_t> sides;
        bool used = false;
    };
    std::vector<const void*> grounds; ///< the drawn tiles' grounds the lists were laid for, in the frame's order
    std::vector<Tile*> drawn;         ///< each drawn tile's own, in the frame's order
    std::vector<std::unique_ptr<Tile>> tiles;
    std::vector<Ref> mirrors; ///< S['buildings'] + S['parts'], each in its source order (a tile's mirror heads)
    std::vector<Ref> trees;   ///< S['trees'] in its source order, those within 9 m of a quay edge
    std::vector<Ref> lamps;   ///< S['nodes']'s street lamps in their order
    std::vector<Ref> roads;   ///< S['roads']'s lit roads in their order
    /// The moment the walls' colours are made in (its theme block, its sun, night and overcast, as bytes), and its
    /// generation.
    std::vector<std::uint8_t> moment;
    std::uint32_t generation = 0;
};

/// A circle of a tree's reflection, CSS px (as the SVG prints them at rest).
struct ReflectionCircle {
    double cx = 0;
    double cy = 0;
    double r = 0;
};

/// A night streak on the water: its ellipse (CSS px) and opacity, as the SVG prints them at rest.
struct ReflectionStreak {
    double cx = 0;
    double cy = 0;
    double rx = 0;
    double ry = 0;
    double opacity = 0;
};

/// A glint of the sun's glitter on the water (water_layer's, in `<g fill="#FFF7E2" style="mix-blend-mode:screen">`):
/// its ellipse (CSS px) and opacity, as the SVG prints them at rest.
struct Glint {
    double cx = 0;
    double cy = 0;
    double rx = 0;
    double ry = 0;
    double opacity = 0;
};

/// A street lamp's pool at night (amsterdam.py:847: `<ellipse fill="url(#pool)">` of 10 m about its foot): its centre
/// and radii in CSS px, as the SVG prints them at rest.
struct LampPool {
    double cx = 0;
    double cy = 0;
    double rx = 0;
    double ry = 0;
};

/// A lit street's run at night (render's night light, amsterdam.py:821: a lit road's points ahead of the near plane
/// within the radius cut, `<path d="M…" stroke-width="%.1f"/>` in the glow's group): its points in
/// GroundFrame::lit_run_points, [first, first + count) (CSS px, as the SVG prints them at rest), its width (CSS px,
/// '%.1f' at rest), and its wet core's ('%.1f' of that width times 0.3).
struct LitRun {
    std::uint32_t first = 0;
    std::uint32_t count = 0;
    double width = 0;
    double core = 0;
};

/// A band of the drizzle's rings (water_layer's `<rect fill="url(#rings)">`, one per glint band at eye level): its rows
/// (CSS px, as the SVG prints the rect at rest) and the pattern's transform there, translate(tx 0) scale(kx ky).
struct RingBand {
    double y0 = 0;
    double y1 = 0;
    double tx = 0;
    double kx = 1;
    double ky = 1;
    std::int32_t index = -1; ///< its glint band's place (the pattern `rings%d`); -1: the whole frame's `rings`
};

/// A fill's decision byte: drawn (at the street's height for the water), drawn at its water level.
namespace drawn_bit {
inline constexpr std::uint8_t street = 1;
inline constexpr std::uint8_t level = 2;
} // namespace drawn_bit

/// One element of the coaster tracks' shadows (draw_over's, in coasters.draw's order), each its own path: a post's,
/// from its foot along the ground (its track's point `first`), or a run of its track's points' shadows ahead of the
/// near plane ([first, first + count)); its drawn tile and its track there, its width (CSS px, as printed at rest).
struct TrackShadow {
    std::uint32_t tile = 0;
    std::uint32_t track = 0;
    std::uint32_t first = 0;
    std::uint32_t count = 0;
    double width = 0;
    bool post = false;
};

/// What the decisions of a frame at rest depend on besides the tiles: the double camera (its eye, axes, focal,
/// principal point, size and near plane), the radius cut (its radius and target), the plate's half side, and the
/// moment's switches; bit for bit.
struct DecisionKey {
    std::array<std::uint64_t, 24> bits{};
    [[nodiscard]] bool operator==(const DecisionKey&) const noexcept = default;
};

/// What the ground decided for a frame (select), shared by its parts and by the twin.
struct GroundFrame {
    /// The parts the map leaves out this frame (the camera state's, style::camera_part): the ground decides none of
    /// its own of them, so none is laid or drawn.
    std::uint64_t left_out = 0;
    [[nodiscard]] bool out(std::uint64_t part) const noexcept { return (left_out & part) != 0U; }
    bool valid = false;
    bool exact = false; ///< the decisions are the port's (the camera rests)
    bool weather = false;
    bool night = false;
    bool engine = false; ///< the renders' radius cut applies
    double radius = 0;
    double target_x = 0;
    double target_z = 0;
    double ground_r = 1500;
    bool plate = false; ///< the plate is drawn
    std::vector<DrawnTile> tiles;
    std::vector<std::uint8_t> drawn;        ///< per fill of the drawn tiles (DrawnTile::decisions): drawn_bit
    std::vector<std::uint8_t> stroke_drawn; ///< per stroke of the drawn tiles (DrawnTile::strokes)
    std::vector<double> stroke_width;       ///< per stroke: its width this frame, CSS px (as printed at rest)
    std::vector<std::uint8_t> shadow_drawn; ///< per shadow of the drawn tiles (DrawnTile::shadows): drawn
    /// Whether the drawn tiles come from more than one scene with a window among them (a window beside another pack's
    /// tiles, or two windows: a frame cell's pack holds a scene a window): the ground is then ordered by its elements
    /// across them (geometry::OrderKey), each drawn fill and stroke at its rank among its group's, as one scene holding
    /// them all would order them; else each at its own scene's order.
    bool mixed = false;
    std::vector<std::uint32_t> fill_rank;   ///< per fill of the drawn tiles, where mixed: its rank in its group
    std::vector<std::uint32_t> stroke_rank; ///< per stroke of the drawn tiles, where mixed: its rank in its group
    /// The land kinds' ranks (-1: none drawn), by the kind's place in the pack's `land` table.
    std::array<std::int32_t, geometry::group::land_kinds> kind_rank{};
    /// Each group's slot base relative to the pass's first ground slot, or its first tail slot for a group of the
    /// tail (slot = base + order · stride + sub), and stride.
    std::array<std::int64_t, geometry::group::count> base{};
    std::array<std::uint32_t, geometry::group::count> stride{};
    std::array<bool, geometry::group::count> used{};
    std::array<bool, geometry::group::count> tail{}; ///< laid out in the tail: over the canal names (the shadows)
    std::uint32_t slots = 0;                         ///< the ground's painter slots this frame
    std::uint32_t tail_slots = 0;                    ///< and its tail's (frame::Pass::ground_tail_slots)
    bool water = false;                              ///< any water drawn (at the street's height)
    bool canopy = false;
    /// The ground shadows: the moment casts them (by day, the sun up, the region's), the sun toward which (the moment's
    /// bucket, sun::bucket_of), and whether any is drawn.
    bool casting = false;
    double sun_x = 0;
    double sun_y = -1;
    double sun_z = 0;
    bool shadows = false;
    /// draw_over's park shadows: by day with the moment's own sun (th['_L'], `light`) over 0.05; the woods' drawn
    /// (their trees' bits among each tile's shadows'), a union of its own; the coaster tracks', element by element.
    bool park_sun = false;
    std::array<double, 3> light{};
    bool woods = false;
    std::vector<TrackShadow> track_shadows;
    /// The quay walls' elements this frame (with weather alone).
    std::vector<QuayFace> quays;
    /// The water's reflections this frame (with weather, where the water is drawn), far to near, and their faces and
    /// circles.
    std::vector<Reflection> reflections;
    std::vector<ReflectionFace> reflection_faces;
    std::vector<ReflectionCircle> reflection_circles;
    std::vector<ReflectionStreak> reflection_streaks;
    /// The sun's glitter on the water this frame (by day under a clear sky, with weather, where the water is drawn).
    std::vector<Glint> glints;
    /// The drizzle's rings on the water this frame (th['wet']): its bands.
    std::vector<RingBand> ring_bands;
    /// The street lamps' pools at night (S['nodes']'s order across the drawn tiles): each ellipse (CSS px).
    std::vector<LampPool> lamp_pools;
    /// The lit streets at night (S['roads']'s order across the drawn tiles): their runs and the runs' points; the
    /// glow's opacity (.3 with weather, .4 without); and whether their wet cores are drawn (th['wet']).
    std::vector<LitRun> lit_runs;
    std::vector<view::Vec2> lit_run_points;
    double lit_opacity = 0;
    bool wet = false;
    /// The glints' rect this frame (with weather, where the water is drawn, at a map pitch, in full mode), and its
    /// opacity.
    bool wind_glints = false;
    double wind_glints_opacity = 0;
    /// Whether this frame's decisions are the last frame's, kept: the camera rests as it did, over the same tiles.
    bool kept = false;
    /// render's #sky and #wgrad rows (y1 and y2 as the SVG prints them), the horizon's row.
    double horizon = 0;
    double sky_y1 = 0;
    double sky_y2 = 0;
    /// sky_layer's sun (away from eye level, with weather and the horizon on the frame, by day, its point within the
    /// margins): its point ('%.1f'), its glow's radius (170, 230 when overcast) and whether it is overcast (the grey
    /// glow's opacities, and no disc).
    bool glow = false;
    double glow_x = 0;
    double glow_y = 0;
    double glow_r = 0;
    bool overcast = false;
    /// sky_layer's city glow at night: its gradient's rows (y1 and y2, '%.0f') and its rect's height (`hb`, '%.1f').
    bool city_glow = false;
    double city_y1 = 0;
    double city_y2 = 0;
    double sky_bottom = 0;
    double wgrad_y1 = 0;
    double wgrad_y2 = 0;
};

/// render/city.py:41 Camera.poly and :72 area in double, the port's arithmetic: the near-clipped polygon's point count,
/// whether its box reaches within the margin of the frame, its box, and its shoelace sum (area: |sum| / 2).
struct ProjectedPoly {
    std::size_t points = 0;
    bool inside = false;
    double sum = 0;
    double min_x = 0;
    double max_x = 0;
    double min_y = 0;
    double max_y = 0;
    /// One edge of Camera.poly's near clip: a, if ahead, and the crossing to b.
    static void clip_edge(const view::Vec3& a, const view::Vec3& b, double near, std::vector<view::Vec3>& out);
};

/// Camera.poly of `pts` at height y with margin `margin`; `cs` and `sp` are scratch (the clipped polygon's camera
/// coordinates and screen points stay in them).
[[nodiscard]] ProjectedPoly project_poly(const view::Camera& cam, std::span<const geometry::P2> pts, double y,
                                         double margin, std::vector<view::Vec3>& cs, std::vector<view::Vec2>& sp);

/// The twin's Camera.poly: a tile-local ring at height y as the vertex stage projects it (twin::Projector: the float
/// camera, snapped at rest), clipped at the near plane in its camera coordinates as Camera.poly clips. Appends its CSS
/// points to `out`; returns how many.
std::size_t twin_ring(const twin::Projector& projector, const shaders::TilePlace& place,
                      std::span<const geometry::P2> ring, geometry::P2 origin, double y, std::vector<twin::Point>& out);

/// The twin's line_d: a polyline's runs of points ahead of the near plane, each a subpath of two points or more.
/// Appends the points to `points` and each run's end (one past its last point) to `ends`.
void twin_runs(const twin::Projector& projector, const shaders::TilePlace& place, std::span<const geometry::P2> line,
               geometry::P2 origin, double y, std::vector<twin::Point>& points, std::vector<std::uint32_t>& ends);

/// Where the twin puts a tile's points (L1 holds them to the port's prints): for a tile in the frame drawn, the port's
/// own arithmetic, the frame's double camera (view::Camera) with Camera.poly's near clip and line_d's runs, each point
/// as the SVG prints it ('%.1f') when the camera rests, as the vertex stage snaps it; for a tile still in another frame
/// while the frames blend, the vertex stage's float maths (twin::Projector over the tile's place).
class TwinPlacer {
public:
    /// The placer of a frame whose view is `cam` (state.view's camera).
    TwinPlacer(const frame::FrameState& state, const view::Camera& cam);
    /// Camera.poly of tile `t`'s ring at height y (no margin test): appends its CSS points, returns how many (0 below
    /// 3).
    std::size_t ring(const DrawnTile& t, std::span<const geometry::P2> ring, double y, std::vector<twin::Point>& out);
    /// A ring in the frame's own metres (the plate's).
    std::size_t frame_ring(std::span<const geometry::P2> ring, double y, std::vector<twin::Point>& out);
    /// line_d of tile `t`'s polyline at height y: its runs of points ahead of the near plane, each of two points or
    /// more; appends the points and each run's end.
    void runs(const DrawnTile& t, std::span<const geometry::P2> line, double y, std::vector<twin::Point>& points,
              std::vector<std::uint32_t>& ends);
    /// The tram tracks' and the bridges' loops: every point ahead of the near plane, joined (`[p for p in pts if p]`),
    /// one subpath of two points or more.
    void joined(const DrawnTile& t, std::span<const geometry::P2> line, double y, std::vector<twin::Point>& points,
                std::vector<std::uint32_t>& ends);
    /// Tile `t`'s point p at height y, as the vertex stage places it (none behind the near plane).
    [[nodiscard]] std::optional<twin::Point> at(const DrawnTile& t, geometry::P2 p, double y) const;
    /// Camera.poly of tile `t`'s face of corners `q` (no margin test): appends its CSS points, returns how many (0
    /// below 3).
    std::size_t poly(const DrawnTile& t, std::span<const view::Vec3> q, std::vector<twin::Point>& out);

private:
    [[nodiscard]] bool exact(const DrawnTile& t) const noexcept;
    std::size_t exact_ring(std::span<const geometry::P2> ring, double y, std::vector<twin::Point>& out);
    /// A point of the port's arithmetic, printed at rest.
    [[nodiscard]] twin::Point point(view::Vec2 s) const;

    const frame::FrameState& state_;
    const view::Camera& cam_;
    twin::Projector projector_;
    std::vector<view::Vec3> cs_;
};

/// render's `far` in engine mode: math.hypot(x - TX, z - TZ) > RAD, the squares deciding where they tell.
[[nodiscard]] bool beyond_radius(double x, double z, double tx, double tz, double radius) noexcept;

/// face()'s test (amsterdam.py:610) of a face's corners: Camera.poly (camera coordinates clipped at the near plane,
/// each edge as the port clips it) keeps three points or more, their box within `margin` of the frame, their shoelace
/// area over `least` (`strictly` over it, else at least it). `cs` is scratch (the clipped camera coordinates stay in
/// it); `box`, where given, takes the points' box (CSS px: min x, min y, max x, max y) of a face it keeps.
[[nodiscard]] bool face_kept(const view::Camera& cam, std::span<const view::Vec3> q, double margin, double least,
                             bool strictly, std::vector<view::Vec3>& cs, std::array<double, 4>* box = nullptr);

/// Whether sample `i` of a drawn tile's bits is lit now (none before its bits come).
[[nodiscard]] bool sun_bit(const DrawnTile& t, std::uint32_t i) noexcept;

/// How lit sample `i` of a drawn tile draws now (K8's lit_amount: its bits before and now, the tile's fade).
[[nodiscard]] double sun_amount(const DrawnTile& t, std::uint32_t i) noexcept;

/// A colour between unlit and lit (0xRRGGBB) by `k`, as building_colour mixes them, packed RGBA8 opaque (r in the low
/// byte).
[[nodiscard]] std::uint32_t lit_mix(std::uint32_t lit, std::uint32_t unlit, double k) noexcept;

/// A fill's or stroke's colour this frame as the SVG prints it (ARGB): its literal, or the theme's (a road's at night
/// five times darker again).
[[nodiscard]] std::uint32_t paint_argb(const geometry::Paint& paint, bool night, const style::ThemeBlock& th) noexcept;

/// Python's '%.<decimals>f' of v read back as the SVG's reader reads it (the exact binary value rounded to nearest,
/// ties to even): what the renders print.
[[nodiscard]] double printed(double v, int decimals);

/// What a box says of Camera.poly's margin test: it surely drops what lies in it, it cannot tell, or (a cell's) what
/// lies in it surely projects into the frame.
enum class Box : std::uint8_t { outside, unknown, inside };
[[nodiscard]] Box box_test(const view::Camera& cam, const geometry::Fill& f, double y, double margin);
/// draw_over's near(pts) in engine mode: the line's samples' box (`near`, geometry::near_box) wholly within the radius
/// or wholly beyond it, else near_radius over its samples.
[[nodiscard]] bool near_line(const GroundFrame& f, std::span<const geometry::P2> line,
                             const std::array<double, 4>& near);

/// A tile cell's box (its fills' boxes at their heights): outside the frame by the widest of their margins, wholly
/// inside the frame, or neither.
[[nodiscard]] Box cell_test(const view::Camera& cam, const geometry::CellBox& c);

/// A rectangle of the upright view's device px, [x0, x1) × [y0, y1).
struct PixelBox {
    std::int32_t x0 = 0;
    std::int32_t y0 = 0;
    std::int32_t x1 = 0;
    std::int32_t y1 = 0;

    [[nodiscard]] bool empty() const noexcept { return x0 >= x1 || y0 >= y1; }
    [[nodiscard]] bool contains(std::int32_t x, std::int32_t y) const noexcept {
        return x >= x0 && x < x1 && y >= y0 && y < y1;
    }
    friend bool operator==(const PixelBox&, const PixelBox&) = default;
};

/// One of the ripple's blur passes this frame: vImage's square box of `side`, or one of WebKit's separable boxes along
/// `axis` (0 x, 1 y), over `region`.
struct RipplePass {
    bool square = false;
    std::uint32_t side = 0;
    std::uint32_t axis = 0;
    std::uint32_t left = 0;
    std::uint32_t right = 0;
    PixelBox region;
};

/// What the ripple's stages cover this frame (Ripple.cpp): the layer's content (its draw's render area), the
/// displacement's region, each blur pass's, and the result's (the composite's). Beyond the result's region the filter's
/// result is transparent: the layer's content moved by the displacement's reach and spread by the boxes' reaches.
struct RipplePlan {
    PixelBox layer;
    PixelBox displace;
    std::vector<RipplePass> passes;
    PixelBox out;
    std::int32_t rx = 0; ///< the blur passes' reaches summed along x
    std::int32_t ry = 0; ///< and along y: the fused stages take them where neither passes ripple.slang's RIPPLE_REACH
};

/// What the lit streets' layers and the glow's filter cover this frame (LitGlow.cpp), in the layers' px (the view's
/// upright device px and the margin about them): whether the glow is drawn (none where its group's box has no width or
/// height, or its filter's output misses the frame); whether its filter runs (in full mode with the byte tables bound:
/// else its layer is screened as it is, as the port draws the group with the filters off); its layer's drawn box (the
/// render pass's area); the filter's input (whose edge texels repeat past it) and its three passes' regions, each what
/// the next reads; the composite's region (the filter's output within the frame: beyond it the result is
/// transparent); and the cores' layer's box (the frame, on a wet night; empty otherwise).
struct GlowPlan {
    bool glow = false;
    bool filtered = false;
    PixelBox layer;
    PixelBox input;
    std::array<PixelBox, 3> passes{};
    PixelBox out;
    PixelBox cores;
    std::uint32_t side = 0; ///< vImage's square box
    friend bool operator==(const GlowPlan&, const GlowPlan&) = default;
};

struct SoftShadows; // below

/// The soft shadows' mask pass (passes/Shadows.cpp): the shadows' union drawn into 4x R8 samples cleared at its start
/// and resolved into an R8 mask, which it leaves in the colour layout; `clear_resolve`: the mask cleared at the start
/// too, VK facts' blur check's other way (passes/BlurCheck.hpp).
[[nodiscard]] gpu::Result<vk::raii::RenderPass> soft_mask_pass(const vk::raii::Device& d, bool clear_resolve);
/// The blur's set (shaders/ground/shadow_blur.slang): the grid, the mask, the last pass's texels and this pass's.
[[nodiscard]] std::array<gpu::Binding, 4> soft_blur_bindings() noexcept;
struct RippleLayer; // below
struct GlowLayer;   // below

class Ground final : public frame::Pass {
public:
    Ground();
    ~Ground() override;
    Ground(const Ground&) = delete;
    Ground& operator=(const Ground&) = delete;
    Ground(Ground&&) = delete;
    Ground& operator=(Ground&&) = delete;

    [[nodiscard]] std::string_view name() const noexcept override { return "ground"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void select(const frame::FrameState& state, frame::Selectors& selectors) override;
    [[nodiscard]] std::uint32_t ground_slots(const frame::FrameState& state) const override;
    [[nodiscard]] std::uint32_t ground_tail_slots(const frame::FrameState& state) const override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& build) const override;
    void emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const override;

    /// The last frame's decisions (tests, and the twin).
    [[nodiscard]] const GroundFrame& decided() const noexcept { return frame_; }
    /// Whether this frame draws the canopy through its filter (canopy_fs): it is drawn, and the fields are bound in
    /// full mode; else its union is black at .55, as a frame without filters draws it.
    [[nodiscard]] bool canopy_filtered(const frame::FrameState& state) const noexcept;
    /// What the last select cost, milliseconds of the render thread's CPU time.
    [[nodiscard]] double select_ms() const noexcept { return select_ms_; }
    /// The absolute painter slot of a group's element this frame (from update on).
    [[nodiscard]] std::uint32_t slot(std::uint16_t group, std::uint32_t order, std::uint32_t sub) const noexcept;
    /// The order a drawn tile's fill `i` (stroke `i`) takes in its group this frame: its scene's, or where the frame
    /// draws tiles of more than one scene, its rank across them (GroundFrame::mixed).
    [[nodiscard]] std::uint32_t fill_order(const DrawnTile& t, std::uint32_t i) const noexcept;
    [[nodiscard]] std::uint32_t stroke_order(const DrawnTile& t, std::uint32_t i) const noexcept;

    /// The tests' look at the soft shadows: from the next prepare on, their grid and blurred mask lie in memory the
    /// CPU reads, and each frame copies the resolved mask there too (SoftShadows::mask_copy).
    void keep_soft_shadows(bool keep) noexcept { keep_soft_ = keep; }
    /// The soft shadows' objects (null where prepare made none).
    [[nodiscard]] const SoftShadows* soft_shadows() const noexcept;
    /// Whether the last frame drew its shadows soft (the moment blurs them).
    [[nodiscard]] bool drew_soft() const noexcept { return drew_soft_; }
    /// The tests' look at a sett road's courses: from the next frame on, the pass draws them alone over the frame's
    /// clear colour (no sky, plate, fills, other lines, water or shadows), each road's lines one layer.
    void draw_only_setts(bool only) noexcept { only_setts_ = only; }
    /// The tests' look at the shadows: from the next frame on, no quay walls (their light changes with the sun's, as
    /// the shadows do).
    void draw_quays(bool draw) noexcept { no_quays_ = !draw; }
    /// The tests' look at the water: from the next frame on, no reflections (or their light, which changes with the
    /// sun's).
    void draw_reflections(bool draw) noexcept { no_reflections_ = !draw; }
    /// The tests' look at the sun's glitter and the drizzle's rings: from the next frame on, none.
    void draw_glitter(bool draw) noexcept { no_glitter_ = !draw; }
    void draw_rings(bool draw) noexcept { no_rings_ = !draw; }
    void draw_pools(bool draw) noexcept { no_pools_ = !draw; }
    /// The tests' look at the lit streets: from the next frame on, neither their glow nor their wet cores.
    void draw_lit(bool draw) noexcept { no_lit_ = !draw; }
    /// Whether the reflected trees take their leaf filter (where the ripple draws their layer).
    void draw_leaves(bool draw) noexcept { no_leaves_ = !draw; }
    /// The tests' look at the reflections' ripple: from the next frame on, the reflections drawn without it (as where
    /// the fields are not bound).
    void draw_ripple(bool draw) noexcept { no_ripple_ = !draw; }
    /// The tests' look at the water: from the next frame on, no glints (K17's rect after the tint).
    void draw_wind_glints(bool draw) noexcept { no_wind_glints_ = !draw; }
    /// The tests' look at the ripple: from the next prepare on, its buffers lie in memory the CPU reads, and each
    /// frame copies its resolved layer there too (RippleLayer::layer_copy).
    void keep_ripple(bool keep) noexcept { keep_ripple_ = keep; }
    /// The tests' switch: the ripple's stages apart, even where the fused stages could take them (on by default).
    void fuse_ripple(bool fuse) noexcept { fuse_ripple_ = fuse; }
    /// The ripple's objects (null where prepare made none).
    [[nodiscard]] const RippleLayer* ripple_layer() const noexcept;
    /// Whether the last frame drew its reflections rippled.
    [[nodiscard]] bool drew_ripple() const noexcept { return drew_ripple_; }
    /// The tests' look at the lit streets' glow: from the next prepare on, its buffers lie in memory the CPU reads, and
    /// each frame copies its layers there too (GlowLayer::glow_copy, core_copy).
    void keep_glow(bool keep) noexcept { keep_glow_ = keep; }
    /// The glow's objects (null where prepare made none), and whether the last frame drew its layers.
    [[nodiscard]] const GlowLayer* glow_layer() const noexcept;
    [[nodiscard]] bool drew_glow() const noexcept { return drew_glow_; }

    /// The phases' ids.
    enum PhaseId : std::uint8_t {
        phase_opaque = 0,
        phase_canopy = 1,
        phase_water_tint = 2,
        phase_water_end = 3,
        phase_over = 4,
        phase_marks = 5,
        phase_shadows = 6,
        phase_shadow_mask = 7,
        phase_water_reflections = 8,
        phase_water_glitter = 9,
        phase_water_rings = 10,
        phase_ripple = 11,
        phase_water_glints = 12,
        phase_pools = 13,
        phase_lit = 14,
    };

    /// The pipelines, their layout and the pass's descriptor sets (Ground.cpp).
    struct Pipelines;
    /// The frame's words and the buffers they go to (Ground.cpp).
    struct Words;

private:
    /// The shadows' mask, the ripple's layer and the lit streets' layers go after every pass's decide phase (order 0):
    /// each hands a render pass's colour to compute stages, and that barrier holds every later compute stage until all
    /// earlier colour output is done, the last frame's main pass among it. A decide phase after them would carry that
    /// wait into the main pass's vertex stage (its records' barrier), so the frame's binning would wait for the last
    /// frame's shading.
    static constexpr std::array<frame::Phase, 15> phase_list{{
        {.slot = frame::Slot::offscreen, .order = 1, .id = phase_shadow_mask},
        {.slot = frame::Slot::offscreen, .order = 2, .id = phase_ripple},
        {.slot = frame::Slot::offscreen, .order = 3, .id = phase_lit},
        {.slot = frame::Slot::ground, .order = 0, .id = phase_opaque},
        {.slot = frame::Slot::translucent, .order = ground_order::canopy, .id = phase_canopy},
        {.slot = frame::Slot::translucent, .order = ground_order::water_reflections, .id = phase_water_reflections},
        {.slot = frame::Slot::translucent, .order = ground_order::water_tint, .id = phase_water_tint},
        {.slot = frame::Slot::translucent, .order = ground_order::water_glints, .id = phase_water_glints},
        {.slot = frame::Slot::translucent, .order = ground_order::water_glitter, .id = phase_water_glitter},
        {.slot = frame::Slot::translucent, .order = ground_order::water_rings, .id = phase_water_rings},
        {.slot = frame::Slot::translucent, .order = ground_order::water_end, .id = phase_water_end},
        {.slot = frame::Slot::translucent, .order = ground_order::over, .id = phase_over},
        {.slot = frame::Slot::translucent, .order = ground_order::marks_road, .id = phase_marks},
        {.slot = frame::Slot::translucent, .order = ground_order::shadows, .id = phase_shadows},
        {.slot = frame::Slot::translucent, .order = ground_order::pools, .id = phase_pools},
    }};

    // Ground.cpp
    /// draw_over's runway_ends' designations at the drawn runways' ends, laid on the ground for the ground's text pass
    /// (FrameState::ground_runs) at their slots.
    void lay_designators(const frame::FrameState& state) const;
    /// The frame's decisions; false when they are the last frame's (kept at rest), so the slots stay as laid out.
    [[nodiscard]] bool decide(const frame::FrameState& state);
    void decide_tile(const view::Camera& cam, const DrawnTile& t, TileDecisions& d);
    void lay_out();
    /// Where the drawn tiles come from more than one scene, a window among them (GroundFrame::mixed), every fill and
    /// stroke of them ranked in its group by its element across them (geometry::OrderKey, then its pack, then its
    /// scene's order), kept while the same tiles are drawn; each pack's ordinal among the drawn ones
    /// (`pack_ordinals_`).
    void rank_across_scenes();
    [[nodiscard]] gpu::Result<void> make_pipelines(const frame::PassContext& context);
    [[nodiscard]] gpu::Result<void> make_fill_pipelines(const frame::PassContext& context, Pipelines& p) const;
    [[nodiscard]] gpu::Result<void> make_water_pipelines(const frame::PassContext& context, Pipelines& p) const;
    [[nodiscard]] gpu::Result<void> make_sky_pipelines(const frame::PassContext& context, Pipelines& p) const;
    [[nodiscard]] gpu::Result<void> make_line_pipelines(const frame::PassContext& context, Pipelines& p) const;
    [[nodiscard]] gpu::Result<void> make_shadow_pipelines(const frame::PassContext& context, Pipelines& p) const;
    void write_words(const frame::FrameState& state);
    void push(const frame::Recording& r, std::uint32_t flags, std::uint32_t slot) const;
    void record_fills(const frame::Recording& r, bool above);
    void record_plate(const frame::Recording& r);
    /// The filter's 256 results (fx::canopy_table) into the frame's words, then in the app's map
    /// (FrameState::grounded) the ground's map its field is read by.
    void write_canopy(std::vector<std::uint32_t>& words, const frame::FrameState& state);
    void record_canopy(const frame::Recording& r);
    // Roads.cpp: the fills' and the plate's twin
    void emit_fills(const frame::FrameState& state, twin::Stream& out, std::int32_t mat, std::int32_t above) const;
    // Water.cpp
    [[nodiscard]] WaterPiece view_water(const view::Camera& cam, const geometry::GroundTile& g, std::uint32_t i);
    void decide_water(const view::Camera& cam);
    void record_water_opaque(const frame::Recording& r);
    void record_water_tint(const frame::Recording& r);
    void record_water_end(const frame::Recording& r);
    void emit_water(const frame::FrameState& state, twin::Stream& out) const;
    // Sky.cpp
    void decide_sky(const view::Camera& cam, const frame::FrameState& state);
    void record_sky(const frame::Recording& r);
    void record_sky_layer(const frame::Recording& r);
    void emit_sky(const frame::FrameState& state, twin::Stream& out) const;
    // Lines.cpp
    void decide_strokes(const view::Camera& cam, const DrawnTile& t, TileDecisions& d) const;
    void write_lines(const frame::FrameState& state, std::vector<std::uint32_t>& words, std::uint32_t& runs,
                     std::array<std::uint32_t, 2>& instances);
    void record_lines(const frame::Recording& r, std::uint32_t phase);
    void emit_lines(const frame::FrameState& state, twin::Stream& out, std::int32_t above) const;
    // Shadows.cpp
    void decide_shadows(const DrawnTile& t, TileDecisions& d) const;
    /// woods.draw's trees whose shadows are drawn: by the radius cut, from 4 m, their crowns' centres within 30 px of
    /// the picture (their bits after the casters').
    void decide_woods(const view::Camera& cam, const DrawnTile& t, TileDecisions& d) const;
    /// coasters.draw's shadows: each track's walk (passes/CoasterWalk.hpp) for its posts', and its points' shadows by
    /// runs, in S['coasters']'s order (frame_.track_shadows).
    void decide_tracks(const view::Camera& cam);
    /// The woods' shadows' union at their slot, composited at shadow_op, within the over lines' draw.
    void record_woods(const frame::Recording& r);
    void emit_park_shadows(const frame::FrameState& state, twin::Stream& out, std::int32_t above) const;
    // Quays.cpp
    /// water_layer's quay walls at the frame: each piece facing the camera within the radius cut, its wall lit by its
    /// sample, its bands, its waterline and its coping, as face() keeps them (frame_.quays).
    void decide_quays(const view::Camera& cam, const frame::FrameState& state);
    void emit_quays(const frame::FrameState& state, twin::Stream& out) const;
    /// The quay walls' faces' records into the frame's words (each band of a bands' path its own), their colours lit
    /// and unlit mixed by their samples' bits and the tiles' fades.
    void write_quays(std::vector<std::uint32_t>& words);
    void record_quays(const frame::Recording& r);
    // Reflections.cpp
    /// Amsterdam.reflections at the frame: each mirrored building's walls facing the camera, their gables and window
    /// rows, and the trees beside the water, as face() keeps them, far to near; then at night the streaks of the street
    /// lamps and the lit roads (frame_.reflections).
    void decide_reflections(const view::Camera& cam, const frame::FrameState& state);
    /// The reflections' lists for the drawn tiles (reflections_kept_), laid again where the tiles are not those they
    /// were laid for; each tile's own kept while it is drawn.
    void lay_reflections();
    /// The reflections' night streaks, after the sorted elements: the street lamps' and the lit roads' points within
    /// 12 m of a quay edge, where the eye sees their reflection through the water and on the picture.
    void decide_streaks(const view::Camera& cam);
    /// The reflections' records into the frame's words (each face its own, a tree's circles each), their colours lit
    /// and unlit mixed by their samples' bits and the tiles' fades.
    void write_reflections(std::vector<std::uint32_t>& words);
    void record_reflections(const frame::Recording& r);
    /// The reflections' group (`<g filter="url(#ripple)" opacity=".92">`) in the water's clip group `water`.
    void emit_reflections(const frame::FrameState& state, twin::Stream& out, std::int32_t water) const;
    // Ripple.cpp
    [[nodiscard]] gpu::Result<void> make_ripple(const frame::PassContext& context, Pipelines& p) const;
    /// Whether this frame draws the reflections rippled: some are drawn, the fields are bound in full mode
    /// (FRAME_FIELDS), the ripple is wanted, and prepare made its objects.
    [[nodiscard]] bool rippled(const frame::FrameState& state) const noexcept;
    /// The displacement's 256 offsets at the frame (fx::ripple_displacement through an exact fma) into its words.
    void write_ripple(std::vector<std::uint32_t>& words, const frame::FrameState& state);
    /// Before the main pass: the reflections into the layer, then the filter's stages over it.
    void record_ripple_layer(const frame::Recording& r);
    /// The rippled reflections composited inside the water's clip at the group's opacity.
    void record_ripple(const frame::Recording& r);
    // Glints.cpp
    /// water_layer's wind glints at the frame: with weather where the water is drawn, at a map pitch, in full mode,
    /// one rect over the frame at the wind's opacity (frame_.wind_glints).
    void decide_wind_glints(const view::Camera& cam, const frame::FrameState& state);
    /// Whether this frame draws the glints' filter: they are drawn, and the fields are bound in full mode.
    [[nodiscard]] bool glints_filtered(const frame::FrameState& state) const noexcept;
    /// The filter's 256 results at the moment (fx::glint_table) into the frame's words.
    void write_wind_glints(std::vector<std::uint32_t>& words, const frame::FrameState& state);
    void record_wind_glints(const frame::Recording& r);
    /// The glints' rect (`<rect filter="url(#glint0)" opacity=rip/>`) in the water's clip group `water`.
    void emit_wind_glints(const frame::FrameState& state, twin::Stream& out, std::int32_t water) const;
    // Glitter.cpp
    /// water_layer's sun glitter at the frame: by day under a clear sky, 260 glints about the point of the water that
    /// mirrors the sun to the eye, where it projects within reach of the picture (frame_.glints).
    void decide_glitter(const view::Camera& cam, const frame::FrameState& state);
    /// The glints' records into the frame's words, and each one's list of the glints whose boxes meet its.
    void write_glitter(std::vector<std::uint32_t>& words);
    void record_glitter(const frame::Recording& r);
    /// The glitter's group (`<g fill="#FFF7E2" style="mix-blend-mode:screen">`) in the water's clip group `water`.
    void emit_glitter(const frame::FrameState& state, twin::Stream& out, std::int32_t water) const;
    // Rings.cpp
    /// water_layer's drizzle on the canals at the frame: with th['wet'], the rings' pattern over the frame, or at eye
    /// level a band of it per glint band, scaled by the band's distance (frame_.ring_bands).
    void decide_rings(const view::Camera& cam, const frame::FrameState& state);
    /// The rings' tile (its rings and their grid) and the bands into the frame's words.
    void write_rings(std::vector<std::uint32_t>& words);
    // LitGlow.cpp
    /// render's lit streets at night (not at eye level): each lit road's runs of points ahead of the near plane within
    /// the radius cut, their widths from the road's own at the runs' mean depth (frame_.lit_runs).
    void decide_lit(const view::Camera& cam);
    [[nodiscard]] gpu::Result<void> make_glow(const frame::PassContext& context, Pipelines& p) const;
    /// Whether this frame blurs the glow: the frame is in full mode with the byte tables bound (FRAME_FIELDS), and
    /// prepare made the glow's objects.
    [[nodiscard]] bool glow_filtered(const frame::FrameState& state) const noexcept;
    /// The runs' points, records and segments into the frame's words, and what the layers and the filter cover.
    void write_lit(std::vector<std::uint32_t>& words, const frame::FrameState& state);
    /// Before the main pass: the runs into the glow's layer, the filter's passes over it, the cores into theirs.
    void record_lit_layer(const frame::Recording& r);
    /// The glow and the cores screened over the ground at their slots.
    void record_lit(const frame::Recording& r);
    void emit_lit(twin::Stream& out) const;
    // Pools.cpp
    void decide_pools(const view::Camera& cam);
    void write_pools(std::vector<std::uint32_t>& words);
    void record_pools(const frame::Recording& r);
    void emit_pools(twin::Stream& out) const;
    [[nodiscard]] gpu::Result<void> make_pool_pipelines(const frame::PassContext& context, Pipelines& p) const;
    void record_rings(const frame::Recording& r);
    /// The rings' rects (`<rect fill="url(#rings)" opacity=".55">`) in the water's clip group `water`.
    void emit_rings(const frame::FrameState& state, twin::Stream& out, std::int32_t water) const;
    [[nodiscard]] gpu::Result<void> make_soft_shadows(const frame::PassContext& context, Pipelines& p) const;
    /// Whether this frame draws its shadows soft: some are drawn, the frame is in full mode, the moment blurs them,
    /// and prepare made the soft shadows' objects.
    [[nodiscard]] bool soft(const frame::FrameState& state) const noexcept;
    void record_shadow_mask(const frame::Recording& r);
    void record_shadows(const frame::Recording& r);
    void emit_shadows(const frame::FrameState& state, twin::Stream& out) const;

    GroundFrame frame_;
    /// The ranks across scenes of a drawn tile's fills and strokes, by its handle (rank_across_scenes).
    struct TileRanks {
        std::uint32_t handle = 0;
        std::vector<std::uint32_t> fills;
        std::vector<std::uint32_t> strokes;
    };
    std::vector<TileRanks> ranks_;
    std::vector<std::uint32_t> ranked_handles_; ///< the drawn tiles ranks_ was made for, sorted
    std::vector<std::uint32_t> pack_ordinals_;  ///< per drawn tile: its pack's place among the drawn ones' ids, sorted
    bool keep_soft_ = false;
    bool drew_soft_ = false;
    bool only_setts_ = false;
    bool no_quays_ = false;
    bool no_reflections_ = false;
    bool no_glitter_ = false;
    bool no_rings_ = false;
    bool no_pools_ = false;
    bool no_lit_ = false;
    bool no_leaves_ = false;
    bool no_ripple_ = false;
    bool no_wind_glints_ = false;
    bool keep_ripple_ = false;
    bool fuse_ripple_ = true;
    bool drew_ripple_ = false;
    bool keep_glow_ = false;
    bool drew_glow_ = false;
    /// What the last glow's layers and filter were made of (write_lit), and whether this frame's differ; what they
    /// cover this frame.
    std::vector<std::uint32_t> glow_key_;
    bool glow_stale_ = true;
    GlowPlan glow_plan_;
    /// What the last ripple's result was made of (write_ripple), and whether this frame's differs; what its stages
    /// cover this frame.
    std::vector<std::uint32_t> ripple_key_;
    bool ripple_stale_ = true;
    RipplePlan ripple_plan_;
    double select_ms_ = 0;
    /// The tiles' decisions, kept while the camera rests (their grounds held), reused as scratch while it moves.
    std::vector<std::unique_ptr<TileDecisions>> decisions_;
    DecisionKey key_;
    bool keep_ = false; ///< decisions_ hold the decisions at rest for key_
    /// The last frame's tiles, in order: their grounds and cells, and their sun bits (the quay walls' pieces and light
    /// come with them).
    std::vector<std::pair<const void*, std::uint64_t>> last_tiles_;
    std::vector<const void*> last_suns_;
    /// The reflections' work that does not move with the camera (Reflections.cpp).
    ReflectionsKept reflections_kept_;
    std::unique_ptr<Pipelines> pipelines_;
    std::unique_ptr<Words> words_;
    // scratch, reused frame to frame
    std::vector<view::Vec3> cam_;
    std::vector<view::Vec2> sp_;
};

/// The ground's GPU objects: its module, its pipeline layout (the frame's sets, then its own), its descriptor sets and
/// the frame slots' buffers of words, and every pipeline its parts draw with.
struct Ground::Pipelines {
    const gpu::Device* device = nullptr;
    vk::raii::ShaderModule module{nullptr};
    vk::raii::DescriptorSetLayout set_layout{nullptr};
    vk::raii::DescriptorPool pool{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    std::array<gpu::Buffer, 2> buffers;
    /// Set 3, the soft shadows' (Shadows.cpp): the grid, the blurred mask, and the grid as words for the compute
    /// stages, a set a frame slot (each its slot's: SoftShadowSlot); the ripple's buffers, the same in both. Before the
    /// layouts made with it, so that it goes after them.
    vk::raii::DescriptorSetLayout soft_set_layout{nullptr};
    vk::raii::DescriptorPool soft_pool{nullptr};
    std::array<vk::raii::DescriptorSet, 2> soft_sets{vk::raii::DescriptorSet{nullptr},
                                                     vk::raii::DescriptorSet{nullptr}};
    vk::raii::PipelineLayout layout{nullptr};
    // Ground.cpp
    vk::raii::Pipeline fill{nullptr};
    vk::raii::Pipeline plate{nullptr};
    vk::raii::Pipeline canopy_union{nullptr};
    vk::raii::Pipeline canopy_composite{nullptr};
    vk::raii::Pipeline canopy_filtered{nullptr};
    // Water.cpp
    vk::raii::Pipeline water_count{nullptr};
    vk::raii::Pipeline water_convert{nullptr};
    vk::raii::Pipeline water_count_level{nullptr};
    vk::raii::Pipeline water_surface{nullptr};
    vk::raii::Pipeline water_flat{nullptr};
    vk::raii::Pipeline water_tint{nullptr};
    vk::raii::Pipeline water_end{nullptr};
    // Sky.cpp
    vk::raii::Pipeline sky{nullptr};
    vk::raii::Pipeline sun_glow{nullptr};
    vk::raii::Pipeline sun_disc{nullptr};
    vk::raii::Pipeline city_glow{nullptr};
    // Lines.cpp
    vk::raii::Pipeline line{nullptr};
    // Quays.cpp
    vk::raii::Pipeline quay{nullptr};
    // Reflections.cpp
    vk::raii::Pipeline reflection{nullptr};
    // Glitter.cpp: per sample where the device can (sample_shading)
    vk::raii::Pipeline glitter{nullptr};
    bool glitter_per_sample = false;
    // Rings.cpp
    vk::raii::Pipeline rings{nullptr};
    // Pools.cpp
    vk::raii::Pipeline lamp_pool{nullptr};
    // Ripple.cpp: the composite of the rippled reflections; the layer, its buffers and stages (null where the pass
    // draws none: the owner mode, or where the device could not make them)
    vk::raii::Pipeline ripple_composite{nullptr};
    std::unique_ptr<RippleLayer> ripple;
    // LitGlow.cpp: the lit streets' layers, the glow's buffers and filter, their composite (null where the pass draws
    // none: the owner mode, or where the device could not make them)
    std::unique_ptr<GlowLayer> glow;
    // Glints.cpp
    vk::raii::Pipeline wind_glints{nullptr};
    // Shadows.cpp
    vk::raii::Pipeline shadow_union{nullptr};
    vk::raii::Pipeline shadow_composite{nullptr};
    /// The soft shadows: the compute stages' layout; the measuring, the grid, the mask and the composite; the images,
    /// buffers and blur (null where the pass draws none: the owner mode).
    vk::raii::PipelineLayout compute_layout{nullptr};
    vk::raii::Pipeline shadow_box{nullptr};
    vk::raii::Pipeline shadow_grid{nullptr};
    vk::raii::Pipeline shadow_mask{nullptr};
    vk::raii::Pipeline shadow_soft{nullptr};
    std::unique_ptr<SoftShadows> soft;
};

/// A frame slot's soft shadows (ENGINE.md's gain 17): what a frame's mask, grid and blur write and its main pass reads,
/// which only the frame before it in the slot read, and that frame has finished by the time the slot is recorded again
/// (gpu::Frames::begin). So the frame's barriers order its own work alone: its box, grid and binning need not wait for
/// the last frame's shading.
struct SoftShadowSlot {
    gpu::Image mask; ///< the union's resolve (R8), the blur's first source
    vk::raii::ImageView mask_view{nullptr};
    vk::raii::Framebuffer framebuffer{nullptr}; ///< the samples, resolved into this mask
    gpu::Buffer grid;                           ///< one ShadowGrid (shared/shadow_grid.slang)
    std::array<gpu::Buffer, 2> blurred;         ///< the blur's passes, back and forth; the last writes the first
    gpu::Buffer mask_copy;                      ///< the kept mask, a row of extent.width bytes after another
    /// The blur's passes' sets (from SoftShadows::blur_pool): the grid, the mask, a pass's two buffers.
    std::array<vk::raii::DescriptorSet, 3> blur_sets{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr},
                                                     vk::raii::DescriptorSet{nullptr}};
};

/// The soft shadows' images and buffers, made for an output, and their blur (Shadows.cpp; shaders/ground/
/// shadow_blur.slang). The mask's texels are filter px from the grid's input corner: as many as the view's device px
/// and a reach around them, the most the grid's input can cover.
struct SoftShadows {
    vk::Extent2D extent{};    ///< the mask's texels
    std::uint32_t stride = 0; ///< the blurred mask's words a row (four texels a word)
    bool kept = false;        ///< the tests' look: the grid and the blurred mask readable, the mask copied each frame
    /// The union's 4x samples (R8, transient), one for both slots: the mask's pass clears them and leaves them
    /// undefined, and its colour output waits for all earlier colour output anyway (its dependency on what came before
    /// it), so a copy a slot would let nothing start sooner.
    gpu::Image samples;
    vk::raii::ImageView samples_view{nullptr};
    vk::raii::RenderPass pass{nullptr};
    vk::raii::DescriptorSetLayout blur_set_layout{nullptr};
    vk::raii::DescriptorPool blur_pool{nullptr};
    std::array<SoftShadowSlot, 2> slots; ///< a frame slot's each
    std::uint32_t last = 0;              ///< the slot of the last frame that drew them soft (the tests' look)
    vk::raii::PipelineLayout blur_layout{nullptr};
    vk::raii::ShaderModule blur_module{nullptr};
    vk::raii::Pipeline blur{nullptr};
};

/// The ripple's images, buffers and stages, made for an output (Ripple.cpp; shaders/ground/ripple.slang): the
/// reflections drawn into 4x samples (their slots' depths beside them), resolved into the layer, the view's upright
/// device px; the filter's two buffers, a word a pixel, which its stages write back and forth.
struct RippleLayer {
    vk::Extent2D extent{}; ///< the view's upright device px
    bool kept = false;     ///< the tests' look: the buffers readable, the layer copied each frame
    gpu::Image samples;    ///< the reflections' 4x samples (RGBA8, transient)
    gpu::Image depth;      ///< their slots' depths (the main pass's depth format, transient)
    gpu::Image layer;      ///< their resolve (RGBA8): the group's layer, premultiplied sRGB
    vk::raii::ImageView samples_view{nullptr};
    vk::raii::ImageView depth_view{nullptr};
    vk::raii::ImageView layer_view{nullptr};
    vk::raii::RenderPass pass{nullptr};
    vk::raii::Framebuffer framebuffer{nullptr};
    std::array<gpu::Buffer, 2> buffers; ///< a and b: premultiplied linear RGB, a r g b
    gpu::Buffer layer_copy;             ///< the kept layer, a row of extent.width texels after another (RGBA8)
    vk::raii::DescriptorSetLayout set_layout{nullptr};
    vk::raii::DescriptorPool pool{nullptr};
    /// [0] reads a and writes b, [1] reads b and writes a (both read the layer)
    std::array<vk::raii::DescriptorSet, 2> sets{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    vk::raii::PipelineLayout compute_layout{nullptr};
    vk::raii::ShaderModule module{nullptr};
    vk::raii::Pipeline displace{nullptr};
    vk::raii::Pipeline square{nullptr};
    vk::raii::Pipeline line{nullptr};
    vk::raii::Pipeline fused{nullptr}; ///< the displacement and the boxes in one dispatch (ripple_fused_cs)
    /// The module on the water (ripple_water.slang: the app's map, FrameState::grounded), its displacement apart and
    /// fused; the boxes are the exact module's.
    vk::raii::ShaderModule water_module{nullptr};
    vk::raii::Pipeline water_displace{nullptr};
    vk::raii::Pipeline water_fused{nullptr};
    vk::raii::Pipeline draw{nullptr}; ///< the reflections into the samples
    std::uint32_t result = 0;         ///< the buffer the last frame's blur left its result in (0 a, 1 b)
    bool ran_fused = false;           ///< the last frame's stages ran fused (ripple_fused_cs)
    PixelBox out;                     ///< and the result's region (the buffers hold nothing of the frame beyond it)
    PixelBox drawn;                   ///< and the layer's (its render area: the layer holds nothing beyond it)
};

/// The lit streets' images, buffers and stages, made for an output (LitGlow.cpp; shaders/ground/litglow.slang): the
/// runs drawn into 4x samples, resolved into the glow's layer and, on a wet night, the cores' (the view's upright
/// device px and `margin` about them); the glow's filter's two buffers, a word a texel, which its passes write back
/// and forth (the third leaves its result in the first); the composite that screens them over the ground.
struct GlowLayer {
    vk::Extent2D view{};     ///< the view's upright device px
    vk::Extent2D extent{};   ///< the layers': the view and the margin either side
    std::int32_t margin = 0; ///< device px
    bool kept = false;       ///< the tests' look: the buffers readable, the layers copied each frame
    gpu::Image samples;      ///< the runs' 4x samples (RGBA8, transient)
    gpu::Image glow;         ///< the glow's layer (RGBA8): premultiplied sRGB
    gpu::Image cores;        ///< the cores' layer
    vk::raii::ImageView samples_view{nullptr};
    vk::raii::ImageView glow_view{nullptr};
    vk::raii::ImageView cores_view{nullptr};
    vk::raii::RenderPass pass{nullptr};
    vk::raii::Framebuffer glow_framebuffer{nullptr};
    vk::raii::Framebuffer cores_framebuffer{nullptr};
    std::array<gpu::Buffer, 2> buffers; ///< a and b: premultiplied linear RGB, a r g b
    gpu::Buffer glow_copy;              ///< the kept layers, a row of extent.width texels after another (RGBA8)
    gpu::Buffer core_copy;
    vk::raii::DescriptorSetLayout set_layout{nullptr};
    vk::raii::DescriptorPool pool{nullptr};
    /// [0] reads a and writes b, [1] reads b and writes a (both read the glow's layer)
    std::array<vk::raii::DescriptorSet, 2> sets{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    vk::raii::PipelineLayout compute_layout{nullptr};
    vk::raii::ShaderModule module{nullptr};
    vk::raii::Pipeline box{nullptr};       ///< one square box (glow_box_cs)
    vk::raii::Pipeline draw{nullptr};      ///< the runs into the samples
    vk::raii::Pipeline composite{nullptr}; ///< the groups screened over the ground (lit_composite_fs)
    GlowPlan drew;                         ///< what the last frame's layers and passes covered
    /// Each layer (the glow's, the cores') laid out to be read since it was made: the composite names both (its
    /// descriptors), drawn or not.
    std::array<bool, 2> laid_out{};
};

/// A pipeline of the ground's module named `name`, with its entry points, opaque on the scene's colour (Ground.cpp).
[[nodiscard]] gpu::GraphicsDesc ground_desc(const frame::PassContext& context, const Ground::Pipelines& p,
                                            std::string_view name, const char* vertex, const char* fragment);
/// No colour written (a stencil-only pipeline).
void colour_off(gpu::GraphicsDesc& d);
/// Source over, depth tested, not written: a translucent element at its painter depth.
void translucent(gpu::GraphicsDesc& d);
/// Depth tested and written: an opaque element at its painter depth.
void opaque_depth(gpu::GraphicsDesc& d);

/// The frame's words (shaders/ground/ground.slang's `words`) and where their tables lie.
struct Ground::Words {
    std::vector<std::uint32_t> w;
    std::uint32_t tiles = 0;                  ///< the tile table's first word (20 words a tile, the plate's place last)
    std::uint32_t plate_tile = 0;             ///< the plate's entry in it
    std::uint32_t slots = 0;                  ///< the slot table's
    std::uint32_t theme = 0;                  ///< the theme colours'
    std::uint32_t runs = 0;                   ///< the lines' runs'
    std::array<std::uint32_t, 2> instances{}; ///< the over and marks phases' segments: first word and count, packed
    std::array<std::uint32_t, 2> instance_count{};
    std::uint32_t woods_split = 0; ///< the over phase's segments before the woods' shadows' slot
    std::uint32_t quays = 0;       ///< the quay walls' faces' records (QUAY_WORDS each), and how many
    std::uint32_t quay_count = 0;
    std::uint32_t reflections = 0; ///< the reflections' records (QUAY_WORDS each), and how many
    std::uint32_t reflection_count = 0;
    std::uint32_t glints = 0; ///< the glints' records (GLINT_WORDS each), how many, and their lists of neighbours
    std::uint32_t glint_count = 0;
    std::uint32_t glint_lists = 0;
    std::uint32_t lamp_pools = 0; ///< the lamps' pools' ellipses (four floats each), and how many
    std::uint32_t lamp_pool_count = 0;
    std::uint32_t lit_runs = 0;     ///< the lit streets' runs' records (ground.slang's LIT_RUN_WORDS each)
    std::uint32_t lit_segments = 0; ///< their segments' instance words (run << 14 | segment), and how many
    std::uint32_t lit_segment_count = 0;
    std::uint32_t rings = 0; ///< the drizzle's tile and bands (ground.slang's RINGS_ layout), and how many bands
    std::uint32_t ring_bands = 0;
    std::uint32_t ripple_offsets = 0; ///< the ripple's 256 displacement offsets (ripple.slang)
    std::uint32_t leaf_offsets = 0; ///< the leaf filter's 256 offsets, then its reach (reflection_fs), with the ripple
    std::uint32_t ripple_plan = 0;  ///< the ripple's plan as ripple_fused_cs reads it (Ripple.cpp's write_ripple)
    std::uint32_t ripple_map = 0;   ///< the water's map the ripple reads its field by (the app's map), 0: the screen's
    std::uint32_t wind_glints = 0;  ///< the glints' filter's 256 results (glints_fs)
    std::uint32_t canopy = 0;       ///< the canopy's filter's 256 results, then the ground's map (canopy_fs)
    std::uint32_t slot = 0;         ///< the frame slot whose buffer holds them
    bool ready = false;
};

} // namespace atlas::passes
