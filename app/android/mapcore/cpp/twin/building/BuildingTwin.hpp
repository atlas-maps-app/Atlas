// The buildings' twin (plan-vector D16's L1, K10): what the buildings pass draws at a frame, as the port's SVG
// elements, into the twin's stream (twin/Twin.hpp). Every primitive the vertex stage draws (its shader's own text,
// twin/building/BuildingShader.hpp, over the records the GPU reads, twin/building/BuildingTile.hpp) is one element: a
// closed path of its corners as the vertex stage places them (CSS px, snapped at rest), and an n-gon (a flat top, a
// skillion roof, a mansard's top, a deck, a cabin roof) one element of its whole ring when the frame draws any of its
// triangles; filled with its face's light, stroked as the port strokes it (a wall's cell, a skillion's or a houseboat's
// wall 0.3 px in the same colour, a roof .25 or .45 px in the roofs' edge colour, a cabin roof .4 px #2A2C30: the
// frame block's strokes, its pack's style; the roofs' strokes draw those), with the objects group's round joins, at its
// painter slot, in its building's item (its OSM owner, an area). Its colours are the faces' light as the pass's CPU
// writes it (light.slang's light() and tohex in binary64, the port's bits), lit by its sample's sun bit. Plain data in,
// no Vulkan: the pass's emit and the tests call it alike.
#pragma once

#include "frame/FrameBlock.hpp"
#include "twin/Twin.hpp"
#include "twin/building/BuildingShader.hpp"
#include "twin/building/BuildingTile.hpp"

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
};

/// How the vertex stage gives an element its depth (the tests' depth raster; L1 reads none of it): the shape it draws
/// (its corners and their reciprocal depths, its face's plane, its span and lead), and whether its depth is its face
/// plane's (an edged outline: building_plane_recip) or its corners' over its fan (a plain polygon:
/// building_plain_vertex); an n-gon's, each of its triangles the frame draws, over its own corners (`shape` is the
/// first of them); and the cover tile it was drawn for (its place in the cover's list: the order the roofs' thin edges
/// are drawn in follows the tiles').
struct ElementDepth {
    shaders::BuildingShape shape{};
    bool edged = false;
    std::vector<shaders::BuildingShape> triangles;
    std::uint32_t tile = 0;
};

/// Emits the buildings of the tiles a frame draws.
class BuildingTwin {
public:
    /// For the frame whose block is `block` and rank table `ranks` (one entry per item row), into `out`, in its group
    /// `group` (the objects group).
    BuildingTwin(const shaders::FrameBlock& block, std::span<const std::uint32_t> ranks, Stream& out,
                 std::int32_t group) noexcept
        : block_(block), ranks_(ranks), out_(out), group_(group) {}

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

private:
    const shaders::FrameBlock& block_;
    std::span<const std::uint32_t> ranks_;
    Stream& out_;
    std::int32_t group_;
    std::size_t elements_ = 0;
    std::size_t sunlit_ = 0;
    std::vector<passes::BuildingRun> runs_;
    std::vector<std::int32_t> items_;     // the stream's item of each of a tile's items, once defined
    std::vector<std::uint8_t> ring_done_; // each of a tile's n-gons, once emitted
    std::vector<std::uint32_t> colours_;  // the tile's faces' colours at the frame's moment, lit and unlit
    std::vector<Point> points_;
    std::vector<shaders::BuildingShape> triangles_; // an n-gon's drawn triangles
    std::vector<ElementDepth>* depths_ = nullptr;
    std::uint32_t tile_ = 0;
};

} // namespace atlas::twin
