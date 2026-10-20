// The objects group's thin strokes (plan-vector D5.4, D7 "Thin strokes and lines", N13; K17a): the roofs' fine edges
// (K10), the railings, posts, trunks and lattices (K14, K15), drawn over what the scene drew with the exact area of
// each segment in each pixel, and thinned over the ground as the renders' `mat` thins them. A helper each pass owns,
// not a pass: the pass keeps its stroke vertex stage (shaders/fx/strokes.slang's stroke_corner and stroke_none, six
// vertices a segment), so its per-frame decisions stay its own; ObjectStrokes makes the two pipelines of that stage
// with the module's fragment stages and records the pass's draws once through each:
//   - over a sample without the objects bit (stencil bit 7: the stroke alone over the ground), alpha min(1, 1.1·a)·a in
//     mat's edge colour;
//   - over a sample with it (the stroke on the group's own paint), alpha a in mat's colour;
// in structural mode, or before the fields are bound (FRAME_FIELDS), alpha a in the stroke's colour through both. Each
// draws at its primitive's painter slot with the depth test LESS_OR_EQUAL (over its own fill, under every nearer
// primitive) and no depth or stencil writes, premultiplied source-over. The pass records them in a phase of its own in
// Slot::translucent, its strokes in its own back-to-front order.
//
// The first version's residual: the group's strokes have round joins (stroke-linejoin="round"), and untrimmed butt
// segments cover a join's inside twice and miss its round wedge outside; CP/tests/fx/strokes_vk_test.cpp counts it at
// the corners. Trimming each segment by its neighbours, and the wedge, are the next refinement.
#pragma once

#include "frame/Pass.hpp"

#include <array>
#include <cstdint>
#include <string>
#include <string_view>

namespace atlas::passes {

/// The two pipelines of a pass's thin strokes.
class ObjectStrokes {
public:
    /// The variants, in the order record draws them.
    enum class Variant : std::uint8_t { ground = 0, objects = 1 };

    /// Makes the pipelines of the pass's stroke vertex stage `entry` in `vertex` (returning strokes.slang's
    /// StrokeVertex, triangle lists, six vertices a segment), in the pass's pipeline layout `layout` (sets 0 and 1 and
    /// the frame's push constants first, as every pass's), for the main pass's scene subpass; `name` names them to the
    /// log and the crash guard ("<name> strokes over the ground", "<name> strokes over the objects"). What an earlier
    /// prepare made goes first.
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context, vk::ShaderModule vertex,
                                            const char* entry, vk::PipelineLayout layout, std::string_view name);
    /// Lets go of the pipelines and the fragment module (the GPU has finished with them).
    void release() noexcept;
    /// Whether prepare made them.
    [[nodiscard]] bool prepared() const noexcept { return static_cast<bool>(*pipelines_[1]); }
    [[nodiscard]] vk::Pipeline pipeline(Variant v) const noexcept { return *pipelines_[static_cast<std::size_t>(v)]; }

    /// Records the pass's draws once a variant: binds the variant's pipeline, then calls `draw(pipeline)`, which binds
    /// what else the draws need and draws. The stencil gives each sample to one variant. Nothing before prepare.
    template <class Draw>
    void record(const vk::raii::CommandBuffer& cmd, const Draw& draw) const {
        if (!prepared()) return;
        for (const vk::raii::Pipeline& p : pipelines_) {
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *p);
            draw(*p);
        }
    }

private:
    vk::raii::ShaderModule fragment_{nullptr};
    std::array<vk::raii::Pipeline, 2> pipelines_{vk::raii::Pipeline{nullptr}, vk::raii::Pipeline{nullptr}};
    std::array<std::string, 2> names_;
};

} // namespace atlas::passes
