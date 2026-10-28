// The buildings' twin (plan-vector D16's L1, K10): what the buildings pass draws at a frame, as the port's SVG
// elements, into the twin's stream (twin/Twin.hpp). Every primitive the vertex stage draws (its shader's own text,
// twin/building/BuildingShader.hpp, over the records the GPU reads, twin/building/BuildingTile.hpp) is one element: a
// closed path of its corners as the vertex stage places them (CSS px, snapped at rest), and an n-gon (a flat top, a
// skillion roof, a mansard's top, a deck, a cabin roof) one element of its whole ring when the frame draws any of its
// triangles; filled with its face's light, stroked as the port strokes it (a wall's cell, a skillion's or a houseboat's
// wall 0.3 px in the same colour, a roof .25 or .45 px in the roofs' edge colour, a cabin roof .4 px #2A2C30: the
// frame block's strokes, its pack's style; the roofs' strokes draw those), with the objects group's round joins, at its
// painter slot, in its building's item (its OSM owner, an area). Its colours are the faces' light as the pass's CPU
// writes it (light.slang's light() and tohex in binary64, the port's bits), lit by its sample's sun bit. Each windowed
// wall the frame draws is up to five more (shaders/building/windows.slangh), at the windows' slots: close up its deep
// windows' reveals; its dark windows' path, then its lit ones', each window a closed subpath of its corners, filled
// with its glass (the gradients `glass` and `glassLit` the pass defines by day with weather, else a flat colour) and
// stroked in the frames' colour and width (wider deep); its sash bars, two open subpaths a window stroked in the
// frames' colour; its deep windows' sills; before them its shop glazing, a path of its own; and each building's plinths
// and cornices, a path each after its walls. A church's wall with weather nearer than 520 m has its church windows
// there instead, closeup.py's five paths: their stones, their reveals, their glass, by day their glass again in the
// veil at opacity .42, and their leading, each arch a closed subpath, each segment of leading an open one. Plain data
// in, no Vulkan: the pass's emit and the tests call it alike.
#pragma once

#include "frame/FrameBlock.hpp"
#include "twin/Twin.hpp"
#include "twin/building/BuildingShader.hpp"
#include "twin/building/BuildingTile.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::twin {

/// A tile's sun bits as the frame draws them (scene::CoverTile's sun, K8's BitTiles: sample i's bit is bit i % 64 of
/// word i / 64), the new and the old, and the fade weight its draw is given; none before the tile's first bits.
struct SunTexels {
    std::span<const std::uint64_t> now;
    std::span<const std::uint64_t> before;
    float weight = 1.0F;
    bool has = false;
    std::uint32_t windows_from = 0; ///< where the tile's window group of samples begins
};

/// A wall's windows of one path (its dark ones, or its lit ones) as the frame draws them, for the tests' raster: each
/// window's shape, the path's painter slot, its wall's plane (in camera coordinates), its frame's half width (CSS px)
/// and colour, its glass's paint and its path's box from top to bottom (the gradient's).
struct WindowsDepth {
    std::vector<shaders::BuildingWindowShape> windows;
    std::uint32_t slot = 0; ///< the path's painter slot
    hlsl::float4 plane{};
    float half = 0.0F;
    hlsl::float3 frame{};
    shaders::BuildingGlass glass{};
    float top = 0.0F;
    float bottom = 0.0F;
    bool shop = false; ///< a shop's glazing: its glass and frame at the glazing's leads
};

/// A wall's material face's path as the frame draws it (material_fill_vs, material_line_vs), for the tests' raster: its
/// fills, each shape once, or its lines, each bar (a line stroked its width either side) and each sample once for the
/// path, in its paint (a glass wall's gradient over the fill's own box from `top` to `bottom`) at its opacity over what
/// lies under it, on its wall's plane at a lead, at the path's slot.
struct MaterialDepth {
    std::vector<shaders::BuildingWindowShape> shapes;
    bool lines = false;
    std::uint32_t slot = 0;
    hlsl::float4 plane{};
    float lead = 0.0F;
    shaders::BuildingGlass paint{};
    float top = 0.0F;
    float bottom = 0.0F;
    float opacity = 1.0F;
};

/// A kit roof's path of lines as the frame draws them (roof_line_vs, roof_line_fs), for the tests' raster: each line's
/// segment and its depth along it, its caps (round: the ridge's; butt: the courses'), its colour and opacity, each
/// line blended over what lies under it on its own.
struct RoofLinesDepth {
    std::vector<shaders::BuildingBeam> lines;
    bool round = false;
    std::uint32_t rgb = 0;
    float opacity = 1.0F;
};

/// A wall's windows' part of one path (its deep windows' reveals or sills, or its sash bars as their quads) as the
/// frame draws it, for the tests' raster: each shape, filled in one colour on its wall's plane at a lead, at the path's
/// slot.
struct PartsDepth {
    std::vector<shaders::BuildingWindowShape> shapes;
    std::uint32_t slot = 0;
    hlsl::float4 plane{};
    float lead = 0.0F;
    hlsl::float3 colour{};
};

/// A church wall's windows' part of one path as the frame draws it (church_window_vs), for the tests' raster: each arch
/// (their stones, reveals or glass) or bar (their leading's segments as their quads) filled with the part's paint on
/// its wall's plane at a lead, at the path's slot; the glass its gradient over the box from `top` to `bottom`, the
/// day's veil folded in (a flat paint: its top).
struct ArchesDepth {
    std::vector<shaders::BuildingArch> arches;
    std::vector<shaders::BuildingWindowShape> bars;
    std::uint32_t slot = 0;
    hlsl::float4 plane{};
    float lead = 0.0F;
    shaders::BuildingGlass paint{};
    float top = 0.0F;
    float bottom = 0.0F;
};

/// How the vertex stage gives an element its depth (the tests' depth raster; L1 reads none of it): the shape it draws
/// (its corners and their reciprocal depths, its face's plane, its span and lead), and whether its depth is its face
/// plane's (an edged outline: building_plane_recip) or its corners' over its fan (a plain polygon:
/// building_plain_vertex); an n-gon's, each of its triangles the frame draws, over its own corners (`shape` is the
/// first of them); a wall's windows' path, its windows; and the cover tile it was drawn for (its place in the cover's
/// list: the order the roofs' thin edges are drawn in follows the tiles').
struct ElementDepth {
    shaders::BuildingShape shape{};
    bool edged = false;
    std::vector<shaders::BuildingShape> triangles;
    std::uint32_t tile = 0;
    std::vector<WindowsDepth> windows;        ///< one: the element is a wall's windows' path
    std::vector<PartsDepth> parts;            ///< one: the element is a wall's reveals', sash bars' or sills' path
    std::vector<ArchesDepth> arches;          ///< one: the element is a church wall's windows' path
    std::vector<MaterialDepth> materials;     ///< one: the element is a wall's material face's path
    std::vector<shaders::BuildingBeam> beams; ///< one: the element is a hoist beam (its segment, its depth along it)
    std::vector<RoofLinesDepth> roof_lines;   ///< one: the element is a kit roof's courses or its lit ridge
    bool veil = false; ///< the element is the church windows' veil, which the frame folds into their glass
};

/// The glass's gradients the frame defines (by day with weather): the stream's `glass` and `glassLit`, or none.
struct WindowGradients {
    std::int32_t glass = none;
    std::int32_t lit = none;
};

/// What the windows' widths are worked out from (passes::window_widths): each item row's depth (Ranks::depth, the
/// port's d) and the camera's focal length (CSS px), in double.
struct WindowDistances {
    std::span<const double> depth;
    double focal = 0.0;
};

/// Emits the buildings of the tiles a frame draws.
class BuildingTwin {
public:
    /// For the frame whose block is `block`, rank table `ranks` (one entry per item row) and items' distances
    /// `distances`, and its glass's gradients, into `out`, in its group `group` (the objects group).
    BuildingTwin(const shaders::FrameBlock& block, std::span<const std::uint32_t> ranks, WindowDistances distances,
                 WindowGradients gradients, Stream& out, std::int32_t group) noexcept
        : block_(block), ranks_(ranks), distances_(distances), gradients_(gradients), out_(out), group_(group) {}

    /// The primitives the frame draws of a tile over its visible cells `cells`: its buildings' `b`, its items' rows in
    /// the item table `rows` (its first item's on), the words of its range of the walls heap, its place and sun bits.
    void tile(const passes::BuildingTile& b, std::span<const std::uint32_t> rows, std::span<const std::byte> words,
              const shaders::TilePlace& place, std::uint64_t cells, const SunTexels& sun = {});

    /// Elements emitted so far, and how many of them their sun bit lit (wholly or fading in).
    [[nodiscard]] std::size_t elements() const noexcept { return elements_; }
    [[nodiscard]] std::size_t sunlit() const noexcept { return sunlit_; }
    /// Appends each element's depth to `out` as it is emitted, in the stream's order (null: none), as drawn for cover
    /// tile `tile`.
    void record_depths(std::vector<ElementDepth>* out, std::uint32_t tile = 0) noexcept {
        depths_ = out;
        tile_ = tile;
    }

    /// The paths of the tile's windowed walls the frame draws over its visible cells (the rest as `tile`'s).
    void windows(const passes::BuildingTile& b, std::span<const std::uint32_t> rows, std::span<const std::byte> words,
                 const shaders::TilePlace& place, std::uint64_t cells, const SunTexels& sun);

private:
    /// The stream's item of a tile's item `item`, defined the first time.
    [[nodiscard]] std::int32_t item_of(const passes::BuildingTile& b, std::uint32_t item);

    /// The paths of a church wall's windows the frame draws (its one-cell primitive `wall`, its face `f`, its plane
    /// `plane` in camera coordinates; `church` its windows' word, BuildingTile::window_churches): at its windows' first
    /// slot `slot` on, in item `item`, its reveals in `reveal` (0xBBGGRR), its item `depth` metres away.
    void church_windows(const shaders::BuildingPrim& wall, const shaders::BuildingFace& f,
                        const shaders::TilePlace& place, hlsl::float4 plane, std::uint32_t church, std::uint32_t slot,
                        std::int32_t item, std::uint32_t reveal, double depth);

    /// The hoist beams under old gables the frame draws of a tile (its items' rows `rows`, its place), each at its slot
    /// among its roof's slopes (beam_vs's).
    void beams(const passes::BuildingTile& b, std::span<const std::uint32_t> rows, const shaders::TilePlace& place);

    /// A kit roof's tile courses (a path a slope, where the view resolves them) and lit ridges the frame draws of a
    /// tile, with weather by day, each at its slot after its roof's slopes (roof_line_vs's).
    void roof_lines(const passes::BuildingTile& b, std::span<const std::uint32_t> rows,
                    const shaders::TilePlace& place);

    /// The paths of a wall's material face the frame draws (its one-cell primitive `wall`, its face `f`, its plane
    /// `plane`, its record `w`, `m` its BuildingTile::window_materials entry): at its material slot `slot` on, in item
    /// `item`, its item `depth` metres away.
    void material_face(const shaders::BuildingPrim& wall, const shaders::BuildingFace& f,
                       const shaders::TilePlace& place, hlsl::float4 plane, const shaders::BuildingWindowWall& w,
                       const passes::BuildingTile::MaterialWall& m, std::uint32_t slot, std::int32_t item,
                       double depth);

    const shaders::FrameBlock& block_;
    std::span<const std::uint32_t> ranks_;
    WindowDistances distances_;
    WindowGradients gradients_;
    Stream& out_;
    std::int32_t group_;
    std::size_t elements_ = 0;
    std::size_t sunlit_ = 0;
    std::vector<passes::BuildingRun> runs_;
    std::vector<std::int32_t> items_;     // the stream's item of each of a tile's items, once defined
    std::vector<std::uint8_t> ring_done_; // each of a tile's n-gons, once emitted
    std::vector<std::uint32_t> colours_;  // the tile's faces' colours at the frame's moment, lit and unlit
    std::vector<Point> points_;
    std::vector<Subpath> subpaths_;
    std::vector<shaders::BuildingShape> triangles_;                    // an n-gon's drawn triangles
    std::array<std::vector<shaders::BuildingWindowShape>, 2> windows_; // a wall's dark and lit windows
    std::vector<shaders::BuildingWindowShape> reveal_shapes_;          // its deep windows' reveals
    std::vector<shaders::BuildingWindowShape> sill_shapes_;            // and sills
    std::vector<shaders::BuildingSash> sashes_;                        // its windows' sash bars
    std::array<std::vector<shaders::BuildingArch>, 3> arches_;         // a church wall's stones, reveals and glass
    std::vector<shaders::BuildingSash> segments_;                      // and its leading's segments
    std::vector<std::uint32_t> reveals_; // the tile's windowed walls' reveals' colours at the frame's moment
    std::vector<PartsDepth> plinths_;    // a building's plinths and cornices, a wall's each, until its walls are done
    std::vector<PartsDepth> cornices_;
    std::vector<ElementDepth>* depths_ = nullptr;
    std::uint32_t tile_ = 0;
};

} // namespace atlas::twin
