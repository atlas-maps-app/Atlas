// The buildings pass's roofs (passes/Walls.hpp): the plain primitives' pipeline and draws (roof faces and n-gons'
// triangles, shaders/building/draw.slang's roofs_vs).
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "passes/Walls.hpp"
#include "twin/building/BuildingShader.hpp"

#include <algorithm>
#include <cstring>

namespace atlas::passes {

gpu::Result<void> Buildings::prepare_roofs(const frame::PassContext& context) {
    gpu::GraphicsDesc desc{
        .label = {.name = "roofs", .module = "building_draw", .configuration = context.configuration},
        .module = *draw_module_,
        .vertex = "roofs_vs",
        .fragment = "building_fs",
        .layout = frame_layout_,
        .target = context.pass.scene_target(),
        .depth_test = true,
        .depth_write = true,
        .depth_op = vk::CompareOp::eLess,
        .stencil_test = true,
        .stencil = {.failOp = vk::StencilOp::eKeep,
                    .passOp = vk::StencilOp::eReplace,
                    .depthFailOp = vk::StencilOp::eKeep,
                    .compareOp = vk::CompareOp::eAlways,
                    .compareMask = 0,
                    .writeMask = shaders::OBJECTS_BIT,
                    .reference = shaders::OBJECTS_BIT}};
    if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
    ATLAS_GPU_LET(roofs, frame::make_pipeline(context, desc, frame::Overdraw::opaque));
    roofs_ = std::move(roofs);
    // the polygons' fans: 3 triangles over each primitive's 5 points
    if (!plain_index_) {
        ATLAS_GPU_LET(index,
                      context.device.memory().buffer(std::uint64_t{plain_chunk} * plain_indices * 2,
                                                     vk::BufferUsageFlagBits::eIndexBuffer, gpu::MemoryUse::upload));
        auto* out = reinterpret_cast<std::uint16_t*>(index.bytes().data()); // NOLINT: the mapped indexes
        std::size_t k = 0;
        for (std::uint32_t p = 0; p < plain_chunk; ++p) {
            const std::uint32_t v = p * shaders::BUILDING_PLAIN_VERTICES;
            for (std::uint32_t t = 0; t + 2 < shaders::BUILDING_PLAIN_VERTICES; ++t) {
                out[k++] = static_cast<std::uint16_t>(v);
                out[k++] = static_cast<std::uint16_t>(v + t + 1);
                out[k++] = static_cast<std::uint16_t>(v + t + 2);
            }
        }
        index.flush();
        plain_index_ = std::move(index);
    }
    return {};
}

void Buildings::record_roofs(const frame::Recording& recording) {
    if (draws_.empty()) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *roofs_);
    cmd.bindIndexBuffer(plain_index_.handle(), 0, vk::IndexType::eUint16);
    for (TileDraw& d : draws_) {
        if (d.plain_runs == 0) continue;
        const std::uint32_t at = d.plain_at;
        const std::uint32_t mode = 0;
        std::memcpy(d.push.data() + 32, &at, sizeof at);     // BuildingPush.at.x
        std::memcpy(d.push.data() + 60, &mode, sizeof mode); // BuildingPush.sun.w: primitives
        const std::uint32_t first_run = d.runs_first + d.edged_runs + d.cell_runs;
        cmd.pushConstants(frame_layout_, frame::push_stages, 0,
                          vk::ArrayProxy<const std::byte>(static_cast<std::uint32_t>(d.push.size()), d.push.data()));
        for (std::uint32_t r = 0; r < d.plain_runs; ++r) {
            const Run& run = runs_[first_run + r];
            for (std::uint32_t first = run.first; first < run.end; first += plain_chunk) {
                const std::uint32_t n = std::min(plain_chunk, run.end - first);
                cmd.drawIndexed(n * plain_indices, 1, 0,
                                static_cast<std::int32_t>(first * shaders::BUILDING_PLAIN_VERTICES), 0);
                recording.counters.draw("roofs", std::uint64_t{n} * (shaders::BUILDING_PLAIN_VERTICES - 2));
            }
        }
    }
}

} // namespace atlas::passes
