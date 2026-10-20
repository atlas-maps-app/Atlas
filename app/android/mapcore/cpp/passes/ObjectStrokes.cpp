// The objects group's thin strokes' two pipelines (passes/ObjectStrokes.hpp).
#include "passes/ObjectStrokes.hpp"

#include "frame/FrameBlock.hpp"
#include "gen/spirv/fx/strokes.hpp"

#include <format>

namespace atlas::passes {

gpu::Result<void> ObjectStrokes::prepare(const frame::PassContext& context, vk::ShaderModule vertex, const char* entry,
                                         vk::PipelineLayout layout, std::string_view name) {
    release();
    ATLAS_GPU_LET(fragment, frame::make_module(context, "fx_strokes", spirv::fx_strokes));
    fragment_ = std::move(fragment);
    names_ = {std::format("{} strokes over the ground", name), std::format("{} strokes over the objects", name)};
    for (std::size_t v = 0; v < pipelines_.size(); ++v) {
        const bool ground = static_cast<Variant>(v) == Variant::ground;
        gpu::GraphicsDesc desc{
            .label = {.name = names_[v], .module = "fx_strokes", .configuration = context.configuration},
            .module = vertex,
            .vertex = entry,
            .fragment = ground ? "strokes_ground_fs" : "strokes_objects_fs",
            .fragment_module = *fragment_,
            .layout = layout,
            .target = context.pass.scene_target()};
        // At the primitive's slot: over its own fill, under every nearer primitive; nothing written but colour.
        desc.depth_test = true;
        desc.depth_write = false;
        desc.depth_op = vk::CompareOp::eGreaterOrEqual;
        // The objects bit chooses the variant, sample by sample.
        desc.stencil_test = true;
        desc.stencil = {.failOp = vk::StencilOp::eKeep,
                        .passOp = vk::StencilOp::eKeep,
                        .depthFailOp = vk::StencilOp::eKeep,
                        .compareOp = vk::CompareOp::eEqual,
                        .compareMask = shaders::OBJECTS_BIT,
                        .writeMask = 0,
                        .reference = ground ? 0U : shaders::OBJECTS_BIT};
        desc.blends[0] = gpu::Blend::over();
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        ATLAS_GPU_LET(pipeline, frame::make_pipeline(context, desc, frame::Overdraw::translucent));
        pipelines_[v] = std::move(pipeline);
    }
    return {};
}

void ObjectStrokes::release() noexcept {
    for (vk::raii::Pipeline& p : pipelines_) p.clear();
    fragment_.clear();
}

} // namespace atlas::passes
