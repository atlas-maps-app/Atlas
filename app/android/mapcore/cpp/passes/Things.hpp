// The street furniture (ENGINE.md step 4, "Trees and things"; render/amsterdam.py's draw_lamp, draw_bench, draw_box,
// draw_bollard, draw_rack): one pass draws every street lamp, bench, bicycle rack, waste basket and bollard as the port
// does, on the trees' records and pipelines (shaders/tree/: things_cs writes them, trees_vs and trees_fs draw them), in
// the objects slot at true depth, a lamp's glint and a bollard's head translucent.
//   - Stage T (build, on a worker): geometry/Things.hpp's things of the tile, one uint4 each in the points heap; an
//   item
//     each, of the kind "thing" (its kind's painter slots); the pass keeps each thing's kind and id.
//   - Stage F (update): the moment's colours (thing_tones, the port's by day or at night) and a job for each thing the
//     ranks draw, the nearest first, with what its item's depth decides in double (thing_choice), but none for one
//     under the port's furniture_px (1.6 px of focal length over its depth).
//   - Before the main pass things_cs writes each job's records; the objects phase draws them, the translucent phase the
//     glints and heads, far to near. None in the owner mode.
//   - The twin (emit): every thing's elements as the port's SVG has them (twin/tree/ThingTwin.hpp).
#pragma once

#include "frame/FrameBlock.hpp"
#include "frame/Pass.hpp"
#include "geometry/Things.hpp"
#include "gpu/Memory.hpp"
#include "style/Theme.hpp"
#include "tiles/Style.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::scene {
struct ResidentTile;
} // namespace atlas::scene

namespace atlas::shaders {
struct TreeRecord;
} // namespace atlas::shaders

namespace atlas::passes {

/// A tile's things as the pass keeps them (PassTile::cpu): each thing's kind, OSM node (a railing's: its road's way)
/// and a railing's piece's posts' gaps.
struct ThingTile {
    std::uint32_t things = 0;
    std::vector<geometry::ThingKind> kinds;
    std::vector<std::int64_t> ids;
    std::vector<std::uint8_t> posts;
};

/// The points heap's words of a tile's things (things.slangh's THING_VECTORS each: its foot tile-local, its kind; a
/// railing's piece's second end).
[[nodiscard]] std::vector<hlsl::uint4> thing_words(const geometry::TileThings& things, double origin_x, double origin_y,
                                                   double origin_z);

/// What the render thread decides for a drawn thing of `kind` from its item's depth `depth` and the camera's focal
/// length, in double as the port compares: whether it is drawn at all (furniture_px; a railing with weather and
/// within 220 m), its job's flags (things.slangh's THING_JOB_; a railing's piece's `posts`) and values: a lamp's mast
/// width (as printed), hw, hh and glint width (as printed); a bollard's post width (as printed), its head's offset and
/// radius (as printed); a railing's width (as printed).
struct ThingChoice {
    bool drawn = false;
    std::uint32_t flags = 0;
    float a = 0;
    float b = 0;
    float c = 0;
    float d = 0;
};
[[nodiscard]] ThingChoice thing_choice(geometry::ThingKind kind, std::uint32_t posts, double depth, double focal,
                                       bool night, bool weather);

/// The moment's colours (things.slangh's table, 0xBBGGRR; then the glint's and the head's opacities and the racks'
/// width, their floats' bits; then the railings'): the port's by day or at night, a box's at night 0.3 of its colour,
/// its leg 0.8 of it.
[[nodiscard]] std::array<std::uint32_t, 17> thing_tones(bool night, const tiles::ThingsStyle& style);

/// The first of a tile's items that are things.
[[nodiscard]] std::uint32_t first_thing(const scene::ResidentTile& tile, std::uint16_t kind) noexcept;

/// The street furniture's pass.
class Things final : public frame::Pass {
public:
    Things() = default;

    [[nodiscard]] std::string_view name() const noexcept override { return "things"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void kinds(frame::Selectors& selectors) override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& build) const override;
    void emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const override;
    /// emit's elements, keeping the records the GPU writes for them in its order into `records` and the translucent
    /// ones (the nearest first: the GPU's list holds them the other way round) into `sides` (the tests').
    void emit_records(const frame::FrameState& state, twin::Stream& out, std::vector<shaders::TreeRecord>* records,
                      std::vector<shaders::TreeRecord>* sides) const;

    /// The phases: the drawing (objects), decided before the main pass (offscreen); the glints and heads
    /// (translucent, after the trees' sunlit sides).
    static constexpr std::uint32_t draw_phase = 0;
    static constexpr std::uint32_t decide_phase = 1;
    static constexpr std::uint32_t sides_phase = 2;
    static constexpr std::int32_t sides_order = 1011;

    /// What the last frame drew: its jobs (the things drawn), their records and translucent ones.
    struct Counts {
        std::uint32_t jobs = 0;
        std::uint32_t records = 0;
        std::uint32_t sides = 0;
    };
    [[nodiscard]] Counts counts() const noexcept { return counts_; }
    [[nodiscard]] std::uint16_t kind() const noexcept { return kind_; }

private:
    static constexpr std::array<frame::Phase, 3> phase_list{{
        {.slot = frame::Slot::objects, .order = 0, .id = draw_phase},
        {.slot = frame::Slot::offscreen, .order = 0, .id = decide_phase},
        {.slot = frame::Slot::translucent, .order = sides_order, .id = sides_phase},
    }};

    /// A drawn thing: its tile's place in the cover, its index in the tile, its rank entry and depth.
    struct Drawn {
        std::uint32_t cover = 0;
        std::uint32_t thing = 0;
        std::uint32_t rank = 0;
        double depth = 0;
    };
    /// The things the ranks draw this frame, the nearest first.
    void drawn(const frame::FrameState& state, std::vector<Drawn>& out) const;
    void record_decide(const frame::Recording& recording);
    void record_draw(const frame::Recording& recording);
    void record_sides(const frame::Recording& recording);

    std::uint16_t kind_ = 0;
    Counts counts_;

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
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::PipelineLayout compute_layout_{nullptr};
    vk::raii::Pipeline decide_{nullptr};
    vk::raii::Pipeline draw_{nullptr};
    vk::raii::Pipeline side_{nullptr};

    // the render thread's, per frame
    std::vector<Drawn> drawn_;
    std::vector<std::uint32_t> words_;
    std::uint32_t slot_ = 0;
};

} // namespace atlas::passes
