// The buildings (plan-vector D10 "Buildings", D6, D7, D8; K10): one pass draws every building and part as the port's
// _draw_building does, in the objects slot (opaque, front to back by tile, at their true depth, writing the objects
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
//     one's dilated outline through a static index buffer's fans) and the roofs (roofs_vs, 5 points each), tile by
//     tile from the nearest, at their true depth, so early depth testing shades each visible sample about once; the
//     vertex stage decides every primitive at the frame (shaders/building/building.slangh) from its building's rank
//     entry (its first painter slot and detail band, through the tile's rows).
//   - The roofs' thin edges (each face's .25, .45 or .4 px stroke, drawn as the objects' thin strokes through
//     ObjectStrokes): stage F writes this frame slot's jobs (each drawn tile's plain runs, the farthest tile first, cut
//     into runs of at most 64); before the main pass (the decide phase) a compute stage decides each such primitive
//     once into a record of its shape at the job's slots (shaders/building/draw.slang's roof_strokes_cs); after every
//     element of the ground (the strokes phase: translucent, at ground_order::objects) one draw a variant draws a
//     segment along each edge each record's stroke follows (roof_strokes_vs), at its face's own depth carried past the
//     edge and a little nearer, so a face's stroke lies over its own fill and over what lies behind its edge, under
//     every nearer face, as the renders paint it after its fill. None in the owner mode (its map takes a face's pixels
//     where its fill drew); where the device cannot make them, the roofs are drawn without them.
//   - The windows (ENGINE.md step 4, the facades: shaders/building/windows.slangh): stage F writes this frame slot's
//     jobs (each drawn tile's runs of windowed walls, at most 64 a job); before the main pass (the decide phase)
//     windows_cs decides each window of each wall once into a record, and each wall into its own (its plane, its dark
//     and lit windows' boxes for the glass's gradients); in the objects phase, after the walls and roofs, one draw
//     draws every window's frame (its outline dilated by the frames' half width, round joins) and another its glass,
//     opaque, on its wall's plane a lead over the wall's cells. None in the owner mode (a window is its wall's
//     owner's).
//   - The detail (frame/Selectors.hpp): the cells' 460 m is the kind's band (its slots near and far); its detail
//     thresholds, each a bit of the rank entry's detail (building.slangh's BUILDING_DETAIL_), are the cells' 460 m, the
//     view's win_d, a church's own windows' 520 m, deep windows' 160 m and sash bars' 140 m.
//   - The twin (emit): every primitive the vertex stage draws, as the port's SVG element, its stroke in its style, and
//   each windowed wall's dark and lit windows' paths (twin/building/BuildingTwin.hpp).
// The roofs' half of the pass (its pipelines and draws, the strokes') is passes/Roofs.cpp; the windows' GPU half is
// passes/Windows.cpp.
#pragma once

#include "frame/FrameBlock.hpp"
#include "frame/Pass.hpp"
#include "geometry/Walls.hpp"
#include "gpu/Memory.hpp"
#include "passes/ObjectStrokes.hpp"
#include "scene/Heaps.hpp"
#include "twin/building/BuildingTile.hpp"
#include "twin/building/BuildingTwin.hpp"

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

    /// The phases: the drawing (objects); the roofs' thin edges, decided before the main pass (offscreen) and drawn
    /// after every element of the ground (translucent, at ground_order::objects: passes/Ground.hpp).
    static constexpr std::uint32_t draw_phase = 0;
    static constexpr std::uint32_t decide_phase = 1;
    static constexpr std::uint32_t strokes_phase = 2;
    static constexpr std::int32_t strokes_order = 1000;

    /// What the last frame's strokes were: their jobs and records (one a plain primitive of the drawn runs).
    struct StrokeCounts {
        std::uint32_t jobs = 0;
        std::uint32_t records = 0;
    };
    [[nodiscard]] StrokeCounts stroke_counts() const noexcept { return stroke_counts_; }
    /// Whether the roofs' thin edges are drawn (the pass made their pipelines: not in the owner mode).
    [[nodiscard]] bool strokes() const noexcept { return strokes_ != nullptr; }
    /// What the last frame's windows were: their jobs, the windowed walls and the windows they decided, and the
    /// windows of the walls nearer than 160 m (their reveals, sills and sash bars).
    struct WindowCounts {
        std::uint32_t jobs = 0;
        std::uint32_t walls = 0;
        std::uint32_t windows = 0;
        std::uint32_t nears = 0;
    };
    [[nodiscard]] WindowCounts window_counts() const noexcept { return window_counts_; }
    /// Whether the windows are drawn (the pass made their pipelines: not in the owner mode).
    [[nodiscard]] bool windows() const noexcept { return windows_ != nullptr; }
    /// The most windows a frame decides (64 bytes a record, 160 more nearer than 160 m): the jobs beyond are left out,
    /// once logged.
    static constexpr std::uint32_t max_windows = 1U << 20U;

    /// The item kind the pass's items take (its Selectors row, from kinds()).
    [[nodiscard]] std::uint16_t kind() const noexcept { return kind_; }
    /// What the last emit wrote: its elements, and how many of them their sun bit lit (the twin's count, for tests).
    struct Emitted {
        std::size_t elements = 0;
        std::size_t sunlit = 0;
    };
    [[nodiscard]] Emitted emitted() const noexcept { return emitted_; }
    /// Each element the last emit wrote, its depth as the vertex stage gives it, in the stream's order (the tests'
    /// depth raster).
    [[nodiscard]] std::span<const twin::ElementDepth> depths() const noexcept { return depths_; }
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
    static constexpr std::array<frame::Phase, 3> phase_list{{
        {.slot = frame::Slot::objects, .order = 0, .id = draw_phase},
        {.slot = frame::Slot::offscreen, .order = 0, .id = decide_phase},
        {.slot = frame::Slot::translucent, .order = strokes_order, .id = strokes_phase},
    }};

    using Run = BuildingRun;
    /// One tile's draws: its push constants (without the list's first primitive or near cell, nor its list's kind),
    /// and its runs of each list.
    struct TileDraw {
        std::array<std::byte, 64> push{};
        std::uint32_t edged_at = 0;   ///< the walls heap's uint4 index of the tile's first edged primitive
        std::uint32_t cells_at = 0;   ///< of its first near cells
        std::uint32_t plain_at = 0;   ///< of its first plain primitive
        std::uint32_t windows_at = 0; ///< of its first windowed wall
        std::uint32_t edged_runs = 0;
        std::uint32_t cell_runs = 0;
        std::uint32_t plain_runs = 0;
        std::uint32_t window_runs = 0;
        std::uint32_t runs_first = 0;   ///< its runs in runs_: edged, then near cells, then plain, then windowed walls
        std::uint32_t windows_from = 0; ///< its window group's first sun sample
        const BuildingTile* tile = nullptr;  ///< its layout (the windowed walls' windows, for the jobs)
        std::span<const std::uint32_t> rows; ///< its items' rows (their ranks say whose windows the frame draws)
        std::uint32_t reveals = 0;           ///< the walls heap's word of its reveals' colours in this frame slot
        double distance = 0;                 ///< the tile's distance from the eye (the draws go nearest first)
    };

    /// The roofs' thin edges' pipelines, sets and buffers (below; made in passes/Roofs.cpp).
    struct Strokes;
    /// The windows' (below; made in passes/Windows.cpp).
    struct Windows;

    [[nodiscard]] gpu::Result<void> prepare_roofs(const frame::PassContext& context);
    [[nodiscard]] gpu::Result<void> prepare_strokes(const frame::PassContext& context);
    [[nodiscard]] gpu::Result<void> prepare_windows(const frame::PassContext& context);
    void record_walls(const frame::Recording& recording);
    void record_roofs(const frame::Recording& recording);
    /// This frame slot's stroke jobs, from the draws (stage F).
    void write_jobs(std::uint32_t slot);
    /// This frame slot's window jobs, from the draws (stage F): the walls of the items whose windows the frame's ranks
    /// draw, with their windows' widths.
    void write_window_jobs(std::uint32_t slot, const frame::FrameState& state);
    void record_decide(const frame::Recording& recording);
    void record_window_decide(const frame::Recording& recording);
    void record_strokes(const frame::Recording& recording);
    void record_windows(const frame::Recording& recording);

    /// The rows of a tile's items, written once into its range in `heaps` (the render thread, the first frame it is
    /// drawn).
    void write_rows(scene::Heaps& heaps, const scene::ResidentTile& tile, const scene::PassTile& mine,
                    const BuildingTile& b);
    /// The tile's faces' colours for this frame's moment into frame slot `slot`'s colours, unless they are its already.
    void light(scene::Heaps& heaps, const shaders::Theme& theme, std::uint32_t slot, const scene::ResidentTile& tile,
               const scene::PassTile& mine, const BuildingTile& b);

    std::uint16_t kind_ = 0;
    mutable Emitted emitted_;
    mutable std::vector<twin::ElementDepth> depths_;
    Lighting lighting_;

    // made in prepare
    vk::raii::ShaderModule draw_module_{nullptr};
    vk::raii::Pipeline walls_{nullptr};
    vk::raii::Pipeline roofs_{nullptr};
    gpu::Buffer edged_index_;
    gpu::Buffer plain_index_;
    vk::PipelineLayout frame_layout_;
    std::unique_ptr<Strokes> strokes_;
    StrokeCounts stroke_counts_;
    std::unique_ptr<Windows> windows_;
    WindowCounts window_counts_;
    bool windows_capped_ = false; ///< a frame's windows passed max_windows (logged once)

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

struct Buildings::Strokes {
    const gpu::Device* device = nullptr;
    /// Set 2: the jobs (the compute stage's), the records as the vertex stage reads them and as the compute stage
    /// writes them; a set, a jobs buffer (written by the CPU) and a records buffer (the GPU's) a frame slot.
    vk::raii::DescriptorSetLayout set_layout{nullptr};
    vk::raii::DescriptorPool pool{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    std::array<gpu::Buffer, 2> jobs;
    std::array<gpu::Buffer, 2> records;
    /// The strokes' pipelines' layout (the frame's sets, then set 2), the compute stage's, and its pipeline.
    vk::raii::PipelineLayout layout{nullptr};
    vk::raii::PipelineLayout compute_layout{nullptr};
    vk::raii::Pipeline decide{nullptr};
    /// roof_strokes_vs's two pipelines.
    ObjectStrokes draw;
    // the render thread's, per frame: the jobs' words, the frame slot they went to, and what they hold
    std::vector<std::uint32_t> words;
    std::uint32_t slot = 0;
    std::uint32_t jobs_now = 0;
    std::uint32_t records_now = 0;
};

struct Buildings::Windows {
    const gpu::Device* device = nullptr;
    /// Set 2 of the windows' pipelines: the jobs (the compute stage's), the windows' records as the compute stage
    /// writes them and as the vertex stages read them, the walls' records alike; a set, a jobs buffer (written by the
    /// CPU) and a buffer of each record (the GPU's) a frame slot.
    vk::raii::DescriptorSetLayout set_layout{nullptr};
    vk::raii::DescriptorPool pool{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    std::array<gpu::Buffer, 2> jobs;
    std::array<gpu::Buffer, 2> records;
    std::array<gpu::Buffer, 2> walls;
    std::array<gpu::Buffer, 2> nears;
    /// The draws' layout (the frame's sets, then set 2), the compute stage's, its pipeline, and the near windows'
    /// parts', the glass's and the frames' pipelines.
    vk::raii::PipelineLayout layout{nullptr};
    vk::raii::PipelineLayout compute_layout{nullptr};
    vk::raii::Pipeline decide{nullptr};
    vk::raii::Pipeline near{nullptr};
    vk::raii::Pipeline glass{nullptr};
    vk::raii::Pipeline frames{nullptr};
    // the render thread's, per frame: the jobs' words, the frame slot they went to, and what they hold
    std::vector<std::uint32_t> words;
    std::uint32_t slot = 0;
    std::uint32_t jobs_now = 0;
    std::uint32_t walls_now = 0;
    std::uint32_t records_now = 0;
    std::uint32_t nears_now = 0;
};

} // namespace atlas::passes
