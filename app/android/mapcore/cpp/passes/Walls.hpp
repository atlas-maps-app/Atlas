// The buildings (plan-vector D10 "Buildings", D6, D7, D8; K10): one pass draws every building and part as the port's
// _draw_building does, in the objects slot (opaque, front to back by tile, at their painter slots, writing the objects
// bit).
//   - Stage T (build, on a worker): geometry/Walls.hpp's primitives, faces and items of the tile at its level, written
//     into one range of the walls heap (twin/building/BuildingTile.hpp): the primitives (4 × uint4: a wall as one cell,
//     a skillion's or a houseboat's wall, a roof's face, an n-gon's triangle), the near cells (two words each, cut from
//     their wall in the vertex stage), each face's geometry (2 × uint4), its colours for each of the two frame slots,
//     and the tile's rows (one per item, written once the tile is taken). The items join the item table; the pass keeps
//     each tile's counts, its cells' ranges and its faces' light inputs (PassTile::cpu).
//   - Stage F (update, on the render thread): for each tile the cover draws, its rows once; its faces' colours for the
//     frame's moment, lit and unlit, on the CPU in binary64 (the port's bits on every device) into this frame slot's
//     colours whenever that slot's are of another moment; and its draws over its visible cells, with its sun bits'
//     first texel in set 1's sun_bits and their fade (scene::CoverTile's, K8's bits: no bits yet, every face unlit).
//   - The objects phase draws the walls and the near cells (shaders/building/draw.slang's walls_vs, 16 points of each
//     one's dilated outline through a static index buffer's fans) and the roofs (roofs_vs, 5 points each); the vertex
//     stage decides every primitive at the frame (shaders/building/building.slangh) from its building's rank entry (its
//     first painter slot and detail band, through the tile's rows).
//   - The twin (emit): every primitive the vertex stage draws, as the port's SVG element
//   (twin/building/BuildingTwin.hpp).
// The roofs' half of the pass (its pipeline and draws) is passes/Roofs.cpp. The roofs' thin edge strokes are the
// objects' thin strokes' (K17a's ObjectStrokes), drawn from this pass once that lands; the twin emits them already.
#pragma once

#include "frame/FrameBlock.hpp"
#include "frame/Pass.hpp"
#include "geometry/Walls.hpp"
#include "gpu/Memory.hpp"
#include "scene/Heaps.hpp"
#include "twin/building/BuildingTile.hpp"

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <span>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace atlas::scene {
class TileBuild;
struct PassTile;
struct ResidentTile;
} // namespace atlas::scene

namespace atlas::passes {

/// The first of a tile's items that are the buildings pass's (its items are consecutive, in its builder's order).
[[nodiscard]] std::uint32_t first_item(const scene::ResidentTile& tile, std::uint16_t kind) noexcept;

/// The buildings pass.
class Buildings final : public frame::Pass {
public:
    Buildings() = default;

    [[nodiscard]] std::string_view name() const noexcept override { return "buildings"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void kinds(frame::Selectors& selectors) override;
    void select(const frame::FrameState& state, frame::Selectors& selectors) override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& build) const override;
    void emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const override;

    /// The phase: the drawing (objects).
    static constexpr std::uint32_t draw_phase = 0;

    /// The item kind the pass's items take (its Selectors row, from kinds()).
    [[nodiscard]] std::uint16_t kind() const noexcept { return kind_; }
    /// What the last emit wrote: its elements, and how many of them their sun bit lit (the twin's count, for tests).
    struct Emitted {
        std::size_t elements = 0;
        std::size_t sunlit = 0;
    };
    [[nodiscard]] Emitted emitted() const noexcept { return emitted_; }
    /// The CPU's light so far: the tiles whose colours it wrote for a moment (a frame slot each), their faces, the
    /// time it took, and the longest a tile took.
    struct Lighting {
        std::uint64_t tiles = 0;
        std::uint64_t faces = 0;
        double ms = 0.0;
        double longest_ms = 0.0;
    };
    [[nodiscard]] Lighting lighting() const noexcept { return lighting_; }
    void reset_lighting() noexcept { lighting_ = {}; }

    /// The primitives per index buffer chunk (65,536 points of u16 indexes), and each one's indexes.
    static constexpr std::uint32_t edged_chunk = 4096;
    static constexpr std::uint32_t edged_indices = 42; // a fan of 14 triangles over 16 points
    static constexpr std::uint32_t plain_chunk = 13107;
    static constexpr std::uint32_t plain_indices = 9; // a fan of 3 triangles over 5 points

private:
    static constexpr std::array<frame::Phase, 1> phase_list{
        frame::Phase{.slot = frame::Slot::objects, .order = 0, .id = draw_phase}};

    using Run = BuildingRun;
    /// One tile's draws: its push constants (without the list's first primitive or near cell, nor its list's kind),
    /// and its runs of each list.
    struct TileDraw {
        std::array<std::byte, 64> push{};
        std::uint32_t edged_at = 0; ///< the walls heap's uint4 index of the tile's first edged primitive
        std::uint32_t cells_at = 0; ///< of its first near cells
        std::uint32_t plain_at = 0; ///< of its first plain primitive
        std::uint32_t edged_runs = 0;
        std::uint32_t cell_runs = 0;
        std::uint32_t plain_runs = 0;
        std::uint32_t runs_first = 0; ///< its runs in runs_: edged, then near cells, then plain
    };

    [[nodiscard]] gpu::Result<void> prepare_roofs(const frame::PassContext& context);
    void record_walls(const frame::Recording& recording);
    void record_roofs(const frame::Recording& recording);
    /// The rows of a tile's items, written once into its range (the render thread, the first frame it is drawn).
    void write_rows(const scene::ResidentTile& tile, const scene::PassTile& mine, const BuildingTile& b);
    /// The tile's faces' colours for this frame's moment into frame slot `slot`'s colours, unless they are its already.
    void light(const shaders::Theme& theme, std::uint32_t slot, const scene::ResidentTile& tile,
               const scene::PassTile& mine, const BuildingTile& b);

    std::uint16_t kind_ = 0;
    mutable Emitted emitted_;
    Lighting lighting_;
    // stage T's: the pass's place in the registry and the world's heaps, as the first build sees them
    mutable std::atomic<std::uint32_t> pass_index_{0xFFFF'FFFFU};
    mutable std::atomic<scene::Heaps*> heaps_{nullptr};

    // made in prepare
    vk::raii::ShaderModule draw_module_{nullptr};
    vk::raii::Pipeline walls_{nullptr};
    vk::raii::Pipeline roofs_{nullptr};
    gpu::Buffer edged_index_;
    gpu::Buffer plain_index_;
    vk::PipelineLayout frame_layout_;

    // the render thread's, per frame
    std::vector<TileDraw> draws_;
    std::vector<Run> runs_;
    std::unordered_set<std::uint32_t> rows_written_;
    std::vector<std::uint32_t> drawn_handles_;
    /// The moments the CPU's light has seen (a moment: the frame block's theme), and each tile's moment in each of the
    /// two frame slots' colours.
    std::array<std::byte, sizeof(shaders::Theme)> theme_{};
    std::uint64_t moment_ = 0;
    std::unordered_map<std::uint32_t, std::array<std::uint64_t, 2>> lit_;
    std::vector<std::uint32_t> colours_;
};

} // namespace atlas::passes
