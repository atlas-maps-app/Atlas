// The trees (ENGINE.md step 4, "Trees and things"; render/closeup.py's tree_close with weather, amsterdam.py's
// draw_tree3d without): one pass draws every natural=tree node as the port does, in the objects slot (opaque, at true
// depth, writing the objects bit), its trunk's sunlit side translucent.
//   - Stage T (build, on a worker): geometry/Trees.hpp's trees of the tile, one range of the points heap
//     (shaders/tree/tree.slangh: TREE_VECTORS uint4 a tree: its header, its 36 clumps, the generic tree's 9 blobs);
//     an item a tree, of the kind "tree" (15 painter slots); the pass keeps each tree's crown radius, tint and id.
//   - Stage F (update, on the render thread): the moment's tones (tree_tones, in binary64 as the port lights them) and
//     a job for each tree the ranks draw, the nearest first, with what its item's depth decides in double
//     (tree_choice): a small crown, its circles, nb, the trunk's sunlit side, the limbs, their widths.
//   - Before the main pass (the decide phase) trees_cs decides each job's tree at the camera into its records
//     (circles, quads, round-capped limbs), then the objects phase draws them, two triangles each (trees_vs, trees_fs:
//     a circle's and a limb's samples kept exactly), and the translucent phase the trunks' sunlit sides, far to near.
//     None in the owner mode (the likeness harness's owner maps draw no trees yet).
//   - The twin (emit): every tree's elements as the port's SVG has them (twin/tree/TreeTwin.hpp), the crowns under the
//     port's `<g filter="url(#crown)">` (the filter itself comes with ENGINE.md's effects).
#pragma once

#include "frame/FrameBlock.hpp"
#include "frame/Pass.hpp"
#include "geometry/Trees.hpp"
#include "gpu/Memory.hpp"
#include "style/Theme.hpp"
#include "tiles/Style.hpp"
#include "view/Camera.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <span>
#include <vector>

namespace atlas::scene {
struct ResidentTile;
} // namespace atlas::scene

namespace atlas::shaders {
struct TreeRecord;
} // namespace atlas::shaders

namespace atlas::passes {

/// A tile's trees as the pass keeps them (PassTile::cpu): each tree's crown radius (its job's choices, in double),
/// whether its leaves stay green, and its OSM node (the twin's items).
struct TreeTile {
    std::uint32_t trees = 0;
    std::vector<double> r;
    std::vector<std::uint8_t> evergreen;
    std::vector<std::int64_t> ids;
};

/// The points heap's words of a tile's trees (TREE_VECTORS uint4 a tree), tile-local from its origin.
[[nodiscard]] std::vector<hlsl::uint4> tree_words(const geometry::TileTrees& trees, double origin_x, double origin_y,
                                                  double origin_z);

/// The first of a tile's items that are trees (the pass's items are consecutive, in its builder's order).
[[nodiscard]] std::uint32_t first_tree(const scene::ResidentTile& tile, std::uint16_t kind) noexcept;

/// What the render thread decides for a drawn tree from its item's depth `depth` (as the ranks have it), the camera's
/// focal length and its crown radius `r`, in double as the port compares: its job's flags (tree.slangh's TREE_JOB_,
/// nb in bits 8 to 15) and widths: a small crown's radius in w0; the trunk's half widths at its foot and top; the
/// limbs' width (the generic tree's trunk's in w0), as printed (`%.2f`).
struct TreeChoice {
    std::uint32_t flags = 0;
    float w0 = 0;
    float w1 = 0;
    float limb = 0;
};
[[nodiscard]] TreeChoice tree_choice(double depth, double focal, double r, bool weather, bool night, bool right);

/// Whether the trunk's sunlit side lies on its right at `camera` (tree_close's sx = 1: L[0]·r[0] + L[2]·r[2] > 0).
[[nodiscard]] bool tree_side_right(const shaders::Theme& theme, const view::Camera& camera) noexcept;

/// The moment's tones (tree.slangh's table, 0xBBGGRR, the last the sunlit side's opacity's bits): the port's light of
/// each, in binary64 through tohex, for the date's autumn tint and for an evergreen's.
[[nodiscard]] std::array<std::uint32_t, 25> tree_tones(const shaders::Theme& theme, const style::ThemeBlock& block,
                                                       const tiles::TreesStyle& style);

/// autumn's tint of a deciduous tree at the moment's date: max(0, min(0.7, ((m - 1) · 30.5 + dd - 258) / 75)).
[[nodiscard]] double tree_tint(const style::ThemeBlock& block) noexcept;

/// The trees pass.
class Trees final : public frame::Pass {
public:
    Trees() = default;

    [[nodiscard]] std::string_view name() const noexcept override { return "trees"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void kinds(frame::Selectors& selectors) override;
    void select(const frame::FrameState& state, frame::Selectors& selectors) override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& build) const override;
    void emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const override;
    /// emit's elements, keeping the records the GPU writes for them in its order into `records` and the sunlit sides
    /// (the nearest first: the GPU's list holds them the other way round) into `sides` (the tests').
    void emit_records(const frame::FrameState& state, twin::Stream& out, std::vector<shaders::TreeRecord>* records,
                      std::vector<shaders::TreeRecord>* sides) const;

    /// The phases: the drawing (objects), decided before the main pass (offscreen); the sunlit sides (translucent,
    /// after the roofs' strokes).
    static constexpr std::uint32_t draw_phase = 0;
    static constexpr std::uint32_t decide_phase = 1;
    static constexpr std::uint32_t sides_phase = 2;
    static constexpr std::int32_t sides_order = 1010;

    /// What the last frame drew: its jobs (the trees the ranks drew), their records and sunlit sides, and the records
    /// the plain fills' list names (the close trees' trunks).
    struct Counts {
        std::uint32_t jobs = 0;
        std::uint32_t records = 0;
        std::uint32_t sides = 0;
        std::uint32_t fills = 0;
    };
    [[nodiscard]] Counts counts() const noexcept { return counts_; }
    /// The item kind the pass's items take.
    [[nodiscard]] std::uint16_t kind() const noexcept { return kind_; }
    /// What the last emit wrote: its elements.
    [[nodiscard]] std::size_t emitted() const noexcept { return emitted_; }

    /// The most records a frame decides (48 bytes each): the jobs beyond are left out, once logged.
    static constexpr std::uint32_t max_records = 1U << 20U;

private:
    static constexpr std::array<frame::Phase, 3> phase_list{{
        {.slot = frame::Slot::objects, .order = 0, .id = draw_phase},
        {.slot = frame::Slot::offscreen, .order = 0, .id = decide_phase},
        {.slot = frame::Slot::translucent, .order = sides_order, .id = sides_phase},
    }};

    /// A drawn tree: its tile's place in the cover, its index in the tile, its rank entry and depth.
    struct Drawn {
        std::uint32_t cover = 0;
        std::uint32_t tree = 0;
        std::uint32_t rank = 0;
        double depth = 0;
    };
    /// The trees the ranks draw this frame, the nearest first (their slots' order, latest first).
    void drawn(const frame::FrameState& state, std::vector<Drawn>& out) const;
    void record_decide(const frame::Recording& recording);
    void record_draw(const frame::Recording& recording);
    void record_sides(const frame::Recording& recording);

    std::uint16_t kind_ = 0;
    Counts counts_;
    mutable std::size_t emitted_ = 0;
    bool capped_ = false; ///< a frame's records passed max_records (logged once)

    // made in prepare
    const gpu::Device* device_ = nullptr;
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets_{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    std::array<gpu::Buffer, 2> jobs_;
    std::array<gpu::Buffer, 2> tones_;
    std::array<gpu::Buffer, 2> records_;
    std::array<gpu::Buffer, 2> sides_;
    std::array<gpu::Buffer, 2> fills_; ///< the fills' list: the trunks' records (ENGINE.md gain 18)
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::PipelineLayout compute_layout_{nullptr};
    vk::raii::Pipeline decide_{nullptr};
    vk::raii::Pipeline draw_{nullptr};
    vk::raii::Pipeline fill_{nullptr}; ///< the trunks alone at true depth with float depth (ENGINE.md gain 18)
    vk::raii::Pipeline side_{nullptr};

    // the render thread's, per frame
    std::vector<Drawn> drawn_;
    std::vector<std::uint32_t> words_;
    std::vector<std::uint32_t> fill_words_;
    std::uint32_t slot_ = 0;
};

} // namespace atlas::passes
