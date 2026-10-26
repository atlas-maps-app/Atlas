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
};

/// A face of a reflection: its corners in the frame's metres (a gable's three, else four).
struct ReflectionFace {
    std::array<view::Vec3, 4> q{};
    std::uint32_t n = 4;
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
    std::array<std::uint64_t, 23> bits{};
    [[nodiscard]] bool operator==(const DecisionKey&) const noexcept = default;
};

/// What the ground decided for a frame (select), shared by its parts and by the twin.
struct GroundFrame {
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
/// it).
[[nodiscard]] bool face_kept(const view::Camera& cam, std::span<const view::Vec3> q, double margin, double least,
                             bool strictly, std::vector<view::Vec3>& cs);

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
};

struct SoftShadows; // below
struct RippleLayer; // below

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
    /// The tests' look at the reflections' ripple: from the next frame on, the reflections drawn without it (as where
    /// the fields are not bound).
    void draw_ripple(bool draw) noexcept { no_ripple_ = !draw; }
    /// The tests' look at the water: from the next frame on, no glints (K17's rect after the tint).
    void draw_wind_glints(bool draw) noexcept { no_wind_glints_ = !draw; }
    /// The tests' look at the ripple: from the next prepare on, its buffers lie in memory the CPU reads, and each
    /// frame copies its resolved layer there too (RippleLayer::layer_copy).
    void keep_ripple(bool keep) noexcept { keep_ripple_ = keep; }
    /// The ripple's objects (null where prepare made none).
    [[nodiscard]] const RippleLayer* ripple_layer() const noexcept;
    /// Whether the last frame drew its reflections rippled.
    [[nodiscard]] bool drew_ripple() const noexcept { return drew_ripple_; }

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
    };

    /// The pipelines, their layout and the pass's descriptor sets (Ground.cpp).
    struct Pipelines;
    /// The frame's words and the buffers they go to (Ground.cpp).
    struct Words;

private:
    static constexpr std::array<frame::Phase, 13> phase_list{{
        {.slot = frame::Slot::offscreen, .order = 0, .id = phase_shadow_mask},
        {.slot = frame::Slot::offscreen, .order = 1, .id = phase_ripple},
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
    void decide_sky(const view::Camera& cam);
    void record_sky(const frame::Recording& r);
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
    /// The reflections' night streaks, after the sorted elements: the street lamps' and the lit roads' where the eye
    /// sees their reflection through the water and on the picture; `near_water` tests the drawn tiles' quay edges.
    void decide_streaks(const view::Camera& cam, const std::function<bool(double, double, double)>& near_water);
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
    bool no_ripple_ = false;
    bool no_wind_glints_ = false;
    bool keep_ripple_ = false;
    bool drew_ripple_ = false;
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
    /// stages. Before the layouts made with it, so that it goes after them.
    vk::raii::DescriptorSetLayout soft_set_layout{nullptr};
    vk::raii::DescriptorPool soft_pool{nullptr};
    vk::raii::DescriptorSet soft_set{nullptr};
    vk::raii::PipelineLayout layout{nullptr};
    // Ground.cpp
    vk::raii::Pipeline fill{nullptr};
    vk::raii::Pipeline plate{nullptr};
    vk::raii::Pipeline canopy_union{nullptr};
    vk::raii::Pipeline canopy_composite{nullptr};
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
    // Ripple.cpp: the composite of the rippled reflections; the layer, its buffers and stages (null where the pass
    // draws none: the owner mode, or where the device could not make them)
    vk::raii::Pipeline ripple_composite{nullptr};
    std::unique_ptr<RippleLayer> ripple;
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

/// The soft shadows' images and buffers, made for an output, and their blur (Shadows.cpp; shaders/ground/
/// shadow_blur.slang). The mask's texels are filter px from the grid's input corner: as many as the view's device px
/// and a reach around them, the most the grid's input can cover.
struct SoftShadows {
    vk::Extent2D extent{};    ///< the mask's texels
    std::uint32_t stride = 0; ///< the blurred mask's words a row (four texels a word)
    bool kept = false;        ///< the tests' look: the grid and the blurred mask readable, the mask copied each frame
    gpu::Image samples;       ///< the union's 4x samples (R8, transient)
    gpu::Image mask;          ///< their resolve (R8), the blur's first source
    vk::raii::ImageView samples_view{nullptr};
    vk::raii::ImageView mask_view{nullptr};
    vk::raii::RenderPass pass{nullptr};
    vk::raii::Framebuffer framebuffer{nullptr};
    gpu::Buffer grid;                   ///< one ShadowGrid (shared/shadow_grid.slang)
    std::array<gpu::Buffer, 2> blurred; ///< the blur's passes, back and forth; the last writes the first
    gpu::Buffer mask_copy;              ///< the kept mask, a row of extent.width bytes after another
    vk::raii::DescriptorSetLayout blur_set_layout{nullptr};
    vk::raii::DescriptorPool blur_pool{nullptr};
    std::array<vk::raii::DescriptorSet, 3> blur_sets{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr},
                                                     vk::raii::DescriptorSet{nullptr}};
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
    vk::raii::Pipeline draw{nullptr}; ///< the reflections into the samples
    std::uint32_t result = 0;         ///< the buffer the last frame's blur left its result in (0 a, 1 b)
    PixelBox out;                     ///< and the result's region (the buffers hold nothing of the frame beyond it)
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
    std::uint32_t rings = 0; ///< the drizzle's tile and bands (ground.slang's RINGS_ layout), and how many bands
    std::uint32_t ring_bands = 0;
    std::uint32_t ripple_offsets = 0; ///< the ripple's 256 displacement offsets (ripple.slang)
    std::uint32_t wind_glints = 0;    ///< the glints' filter's 256 results (glints_fs)
    std::uint32_t slot = 0;           ///< the frame slot whose buffer holds them
    bool ready = false;
};

} // namespace atlas::passes
