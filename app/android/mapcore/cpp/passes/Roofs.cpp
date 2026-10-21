// The buildings pass's roofs (passes/Walls.hpp): the plain primitives' pipeline and draws (roof faces and n-gons'
// triangles, shaders/building/draw.slang's roofs_vs), and their thin edges' (roof_strokes_cs, roof_strokes_vs, through
// ObjectStrokes).
#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gpu/Descriptors.hpp"
#include "passes/Ground.hpp"
#include "passes/Walls.hpp"
#include "twin/building/BuildingShader.hpp"

#include <algorithm>
#include <cstring>
#include <ranges>

namespace atlas::passes {

static_assert(Buildings::strokes_order == ground_order::objects,
              "the roofs' thin edges after every element of the ground");

namespace {

/// A stroke job's words (draw.slang's STROKE_JOB_VECTORS uint4): its tile's push constants, then its run.
constexpr std::uint32_t job_words = 20;
/// The most primitives a job decides (roof_strokes_cs's workgroup), and the jobs a row of workgroups covers
/// (draw.slang's STROKE_JOB_ROW).
constexpr std::uint32_t job_size = 64;
constexpr std::uint32_t job_row = 65535;

/// `b`, or a buffer for `use` of at least `bytes` with room to grow by half again where `b` is smaller; whether it was
/// made anew.
[[nodiscard]] gpu::Result<bool> fit(const gpu::Device& device, gpu::Buffer& b, vk::DeviceSize bytes,
                                    vk::BufferUsageFlags usage, gpu::MemoryUse use) {
    if (b && b.size() >= bytes) return false;
    ATLAS_GPU_LET(made, device.memory().buffer(std::max<vk::DeviceSize>(bytes + (bytes / 2), 1024), usage, use));
    b = std::move(made);
    return true;
}

} // namespace

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
        .depth_op = vk::CompareOp::eGreater,
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
    // the thin edges, but in the owner mode; where the device cannot make them, the roofs are drawn without them
    if (!context.owners) {
        if (auto made = prepare_strokes(context); !made) {
            log::warn("vk buildings: the roofs' thin edges: {}; the roofs are drawn without them", made.error().text());
            strokes_.reset();
        }
    }
    return {};
}

gpu::Result<void> Buildings::prepare_strokes(const frame::PassContext& context) {
    auto s = std::make_unique<Strokes>();
    s->device = &context.device;
    const vk::raii::Device& d = context.device.handle();
    using Stage = vk::ShaderStageFlagBits;
    const std::array bindings{
        gpu::Binding{.slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 1, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 2, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    s->set_layout = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, static_cast<std::uint32_t>(s->sets.size())));
    s->pool = std::move(pool);
    for (vk::raii::DescriptorSet& set : s->sets) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, s->pool, s->set_layout));
        set = std::move(made);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*s->set_layout};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    s->layout = std::move(layout);
    const std::array<vk::DescriptorSetLayout, 3> sets{context.layout.frame_set_layout(),
                                                      context.layout.global_set_layout(), *s->set_layout};
    ATLAS_GPU_LET(compute_layout, gpu::make_layout(d, sets, frame::push_bytes, Stage::eCompute));
    s->compute_layout = std::move(compute_layout);
    ATLAS_GPU_LET(
        decide,
        frame::make_compute(context,
                            {.name = "roof strokes", .module = "building_draw", .configuration = context.configuration},
                            *draw_module_, "roof_strokes_cs", *s->compute_layout));
    s->decide = std::move(decide);
    ATLAS_GPU_CHECK(s->draw.prepare(context, *draw_module_, "roof_strokes_vs", *s->layout, "roof"));
    strokes_ = std::move(s);
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

void Buildings::write_jobs(std::uint32_t slot) {
    Strokes& s = *strokes_;
    s.jobs_now = 0;
    s.records_now = 0;
    std::vector<std::uint32_t>& w = s.words;
    w.clear();
    std::uint32_t records = 0;
    // the farthest tile first, as the renders paint; each run of its plain list in jobs of at most 64, a record each
    for (const TileDraw& d : std::ranges::reverse_view(draws_)) {
        if (d.plain_runs == 0) continue;
        std::array<std::byte, 64> push = d.push;
        const std::uint32_t mode = 0;
        std::memcpy(push.data() + 32, &d.plain_at, sizeof d.plain_at); // BuildingPush.at.x: the plain list
        std::memcpy(push.data() + 60, &mode, sizeof mode);             // BuildingPush.sun.w: primitives
        const std::uint32_t first_run = d.runs_first + d.edged_runs + d.cell_runs;
        for (std::uint32_t r = 0; r < d.plain_runs; ++r) {
            const Run& run = runs_[first_run + r];
            for (std::uint32_t first = run.first; first < run.end; first += job_size) {
                const std::uint32_t n = std::min(job_size, run.end - first);
                const std::size_t at = w.size();
                w.resize(at + job_words, 0U);
                std::memcpy(w.data() + at, push.data(), push.size());
                w[at + 16] = first;
                w[at + 17] = n;
                w[at + 18] = records;
                records += n;
            }
        }
    }
    const auto jobs = static_cast<std::uint32_t>(w.size() / job_words);
    if (jobs == 0) return;
    // into this frame slot's buffers (its previous frame has finished: its buffers and set are free)
    const gpu::Device& device = *s.device;
    using B = vk::BufferUsageFlagBits;
    auto jobs_made =
        fit(device, s.jobs[slot], w.size() * sizeof(std::uint32_t), B::eStorageBuffer, gpu::MemoryUse::upload);
    auto records_made =
        fit(device, s.records[slot],
            vk::DeviceSize{records} * shaders::BUILDING_STROKE_RECORD_VECTORS * 4 * sizeof(std::uint32_t),
            B::eStorageBuffer, gpu::MemoryUse::gpu);
    if (!jobs_made || !records_made) {
        log::error("vk buildings: the roofs' thin edges' {} jobs and {} records: {}", jobs, records,
                   (!jobs_made ? jobs_made.error() : records_made.error()).text());
        s.jobs[slot] = {};
        s.records[slot] = {};
        return;
    }
    if (*jobs_made || *records_made) {
        gpu::SetWriter(*s.sets[slot])
            .buffer(0, vk::DescriptorType::eStorageBuffer, s.jobs[slot].handle())
            .buffer(1, vk::DescriptorType::eStorageBuffer, s.records[slot].handle())
            .buffer(2, vk::DescriptorType::eStorageBuffer, s.records[slot].handle())
            .apply(device.handle());
    }
    std::memcpy(s.jobs[slot].bytes().data(), w.data(), w.size() * sizeof(std::uint32_t));
    s.jobs[slot].flush();
    s.slot = slot;
    s.jobs_now = jobs;
    s.records_now = records;
    stroke_counts_ = {.jobs = jobs, .records = records};
}

void Buildings::record_decide(const frame::Recording& recording) {
    if (strokes_ == nullptr || strokes_->jobs_now == 0) return;
    const Strokes& s = *strokes_;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *s.decide);
    const auto frame_sets = recording.layout.sets();
    const auto offsets = recording.layout.offsets(recording.frame.slot.slot);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 0, frame_sets, offsets);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 2, *s.sets[s.slot], {});
    shaders::BuildingPush push{};
    push.at = {s.jobs_now, 0U, 0U, 0U};
    cmd.pushConstants<shaders::BuildingPush>(*s.compute_layout, vk::ShaderStageFlagBits::eCompute, 0, push);
    cmd.dispatch(std::min(s.jobs_now, job_row), (s.jobs_now + job_row - 1) / job_row, 1);
    recording.counters.dispatch("roof strokes");
    // the records, for the strokes' vertex stage
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    recording.frame.sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                            .src_access = A::eShaderWrite,
                                                            .dst_stage = S::eVertexShader,
                                                            .dst_access = A::eShaderRead});
}

void Buildings::record_strokes(const frame::Recording& recording) {
    if (strokes_ == nullptr || strokes_->records_now == 0) return;
    const Strokes& s = *strokes_;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    // set 2 under the frame's sets, which the scene subpass keeps bound
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *s.layout, 2, *s.sets[s.slot], {});
    const std::uint32_t vertices = s.records_now * shaders::BUILDING_STROKE_VERTICES;
    s.draw.record(cmd, [&](vk::Pipeline) {
        cmd.draw(vertices, 1, 0, 0);
        recording.counters.draw("roof strokes", std::uint64_t{vertices} / 3);
    });
}

} // namespace atlas::passes
