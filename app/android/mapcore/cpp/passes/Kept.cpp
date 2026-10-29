// The buildings pass's kept primitives (passes/Walls.hpp; ENGINE.md's gains, 1b). Before the main pass, in the decide
// phase, three compute stages over this frame slot's jobs (every run of the walls', the near cells' and the roofs'
// lists, in the order their draws go, at most 64 primitives a job):
//   1. the cull (kept_cull_cs) tests each primitive as building_shape first tests it (its rank, its detail variant, the
//      moment's weather, its facing with a margin: building.slangh's building_may_draw), each job's candidates into
//      its count and mask;
//   2. the scan (kept_scan_cs, a workgroup a list) gives each job its first place among its list's kept primitives,
//      in the jobs' order, and each of the list's chunks (the static index buffers' primitives) its indirect draw;
//   3. the scatter (kept_scatter_cs) writes each candidate at its place, as its job and lane.
// The walls, the near cells and the roofs are then drawn a chunk at a time through those draws, and the vertex stage
// decides each kept primitive as walls_vs and roofs_vs decide one (walls_kept_vs, roofs_kept_vs, from its job's push
// constants). So the frame draws the same primitives in the same order as the runs draw them, less those building_shape
// would draw nothing of: the same pixels, without the vertex work of the rest. Where the device cannot make the kept
// pipelines, or a frame's buffers cannot be had, the lists are drawn as their runs, every primitive (passes/Walls.cpp,
// passes/Roofs.cpp).
#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gpu/Descriptors.hpp"
#include "passes/Walls.hpp"
#include "twin/building/BuildingShader.hpp"

#include <algorithm>
#include <array>
#include <cstring>
#include <string_view>

namespace atlas::passes {

namespace {

/// A job's words (BUILDING_KEPT_JOB_VECTORS uint4: its tile's push constants, then its run) and the header's.
constexpr std::uint32_t job_words = shaders::BUILDING_KEPT_JOB_VECTORS * 4;
constexpr std::uint32_t header_words = shaders::BUILDING_KEPT_HEADER * 4;
/// The most primitives a job takes (the compute stages' workgroups), and the jobs a row of workgroups covers.
constexpr std::uint32_t job_size = 64;
constexpr std::uint32_t job_row = 65535;
constexpr std::uint32_t lists = shaders::BUILDING_KEPT_LISTS;
constexpr std::uint32_t draw_words = shaders::BUILDING_KEPT_DRAW_WORDS;

static_assert(Buildings::edged_chunk == shaders::BUILDING_KEPT_EDGED_CHUNK &&
                  Buildings::plain_chunk == shaders::BUILDING_KEPT_PLAIN_CHUNK && lists == 3,
              "the kept lists' chunks are the static index buffers'");

/// Each list's chunk (its index buffer's primitives), vertices and indexes a primitive, and its counters' kind.
constexpr std::array<std::uint32_t, lists> chunk_of{Buildings::edged_chunk, Buildings::edged_chunk,
                                                    Buildings::plain_chunk};
constexpr std::array<std::uint32_t, lists> vertices_of{
    shaders::BUILDING_EDGED_VERTICES, shaders::BUILDING_EDGED_VERTICES, shaders::BUILDING_PLAIN_VERTICES};
constexpr std::array<std::uint32_t, lists> indices_of{Buildings::edged_indices, Buildings::edged_indices,
                                                      Buildings::plain_indices};
constexpr std::array<std::string_view, lists> kind_of{"walls", "near cells", "roofs"};

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

gpu::Result<void> Buildings::prepare_kept(const frame::PassContext& context) {
    auto k = std::make_unique<Kept>();
    k->device = &context.device;
    const vk::raii::Device& d = context.device.handle();
    using Stage = vk::ShaderStageFlagBits;
    const std::array bindings{
        gpu::Binding{
            .slot = 12, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute | Stage::eVertex},
        gpu::Binding{.slot = 13, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 14, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 15, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 16, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    k->set_layout = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, static_cast<std::uint32_t>(k->sets.size())));
    k->pool = std::move(pool);
    for (vk::raii::DescriptorSet& set : k->sets) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, k->pool, k->set_layout));
        set = std::move(made);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*k->set_layout};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    k->layout = std::move(layout);
    const std::array<vk::DescriptorSetLayout, 3> sets{context.layout.frame_set_layout(),
                                                      context.layout.global_set_layout(), *k->set_layout};
    ATLAS_GPU_LET(compute_layout, gpu::make_layout(d, sets, frame::push_bytes, Stage::eCompute));
    k->compute_layout = std::move(compute_layout);
    const auto compute = [&](const char* name, const char* entry) {
        return frame::make_compute(context,
                                   {.name = name, .module = "building_draw", .configuration = context.configuration},
                                   *draw_module_, entry, *k->compute_layout);
    };
    ATLAS_GPU_LET(cull, compute("kept cull", "kept_cull_cs"));
    k->cull = std::move(cull);
    ATLAS_GPU_LET(scan, compute("kept scan", "kept_scan_cs"));
    k->scan = std::move(scan);
    ATLAS_GPU_LET(scatter, compute("kept scatter", "kept_scatter_cs"));
    k->scatter = std::move(scatter);
    ATLAS_GPU_LET(walls, frame::make_pipeline(
                             context, building_desc(context, *draw_module_, "kept walls", "walls_kept_vs", *k->layout),
                             frame::Overdraw::opaque));
    k->walls = std::move(walls);
    ATLAS_GPU_LET(roofs, frame::make_pipeline(
                             context, building_desc(context, *draw_module_, "kept roofs", "roofs_kept_vs", *k->layout),
                             frame::Overdraw::opaque));
    k->roofs = std::move(roofs);
    kept_ = std::move(k);
    return {};
}

bool Buildings::drawn_kept() const noexcept { return kept_ != nullptr && kept_->jobs_now > 0; }

void Buildings::write_kept_jobs(std::uint32_t slot) {
    Kept& k = *kept_;
    k.jobs_now = 0;
    k.first_draw = {};
    k.chunks = {};
    k.submitted = {};
    std::vector<std::uint32_t>& w = k.words;
    w.assign(header_words, 0U);
    // the map leaves the cull out: the walls and roofs are drawn as their runs (drawn_kept: no jobs)
    if (out(style::camera_part::kept_cull)) return;
    std::array<std::uint32_t, lists> first_job{};
    std::array<std::uint32_t, lists> jobs{};
    std::array<std::uint32_t, lists> base{};
    std::uint32_t places = 0;
    std::uint32_t draws = 0;
    for (std::uint32_t list = 0; list < lists; ++list) {
        first_job[list] = static_cast<std::uint32_t>((w.size() - header_words) / job_words);
        base[list] = places;
        // record_walls' and record_roofs' order: the walls of every tile, the nearest first, then the near cells, then
        // the roofs; each run of a tile's list in jobs of at most 64
        for (const TileDraw& d : draws_) {
            // (the plain list's runs are the roofs' thin edges' too: no kept roofs where the map leaves them out)
            const std::array<std::uint32_t, lists> runs{d.edged_runs, d.cell_runs,
                                                        out(style::camera_part::roofs) ? 0U : d.plain_runs};
            const std::array<std::uint32_t, lists> firsts{d.runs_first, d.runs_first + d.edged_runs,
                                                          d.runs_first + d.edged_runs + d.cell_runs};
            const std::array<std::uint32_t, lists> ats{d.edged_at, d.cells_at, d.plain_at};
            if (runs[list] == 0) continue;
            std::array<std::byte, 64> push = d.push;
            const std::uint32_t mode = list == 1 ? 1U : 0U;
            std::memcpy(push.data() + 32, &ats[list], sizeof(std::uint32_t)); // BuildingPush.at.x: the list
            std::memcpy(push.data() + 60, &mode, sizeof mode);                // BuildingPush.sun.w: near cells or not
            for (std::uint32_t r = 0; r < runs[list]; ++r) {
                const Run& run = runs_[firsts[list] + r];
                for (std::uint32_t first = run.first; first < run.end; first += job_size) {
                    const std::uint32_t n = std::min(job_size, run.end - first);
                    const std::size_t at = w.size();
                    w.resize(at + job_words, 0U);
                    std::memcpy(w.data() + at, push.data(), push.size());
                    w[at + 16] = first;
                    w[at + 17] = n;
                    w[at + 18] = list;
                    places += n;
                }
            }
        }
        jobs[list] = static_cast<std::uint32_t>((w.size() - header_words) / job_words) - first_job[list];
        k.submitted[list] = places - base[list];
        k.first_draw[list] = draws;
        k.chunks[list] = (k.submitted[list] + chunk_of[list] - 1) / chunk_of[list];
        draws += k.chunks[list];
    }
    const auto total = static_cast<std::uint32_t>((w.size() - header_words) / job_words);
    if (total == 0) return;
    // the header: each list's first job, jobs, first place and first chunk draw; the totals
    for (std::uint32_t list = 0; list < lists; ++list) {
        w[list] = first_job[list];
        w[4 + list] = jobs[list];
        w[8 + list] = base[list];
        w[12 + list] = k.first_draw[list];
    }
    w[3] = total;
    w[11] = places;
    w[15] = draws;
    // into this frame slot's buffers (its previous frame has finished: its buffers and set are free)
    const gpu::Device& device = *k.device;
    using B = vk::BufferUsageFlagBits;
    const auto words = [](std::uint64_t n) { return vk::DeviceSize{n} * sizeof(std::uint32_t); };
    auto jobs_made = fit(device, k.jobs[slot], words(w.size()), B::eStorageBuffer, gpu::MemoryUse::upload);
    auto counts_made =
        fit(device, k.counts[slot], words(std::uint64_t{total} * 4), B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto kept_made = fit(device, k.kept[slot], words(places), B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto draws_made = fit(device, k.draws[slot], words(std::uint64_t{draws} * draw_words),
                          B::eStorageBuffer | B::eIndirectBuffer, gpu::MemoryUse::readback);
    if (!jobs_made || !counts_made || !kept_made || !draws_made) {
        const gpu::Error& e = !jobs_made     ? jobs_made.error()
                              : !counts_made ? counts_made.error()
                              : !kept_made   ? kept_made.error()
                                             : draws_made.error();
        log::error("vk buildings: the kept primitives' {} jobs: {}; drawn as their runs", total, e.text());
        k.jobs[slot] = {};
        k.counts[slot] = {};
        k.kept[slot] = {};
        k.draws[slot] = {};
        return;
    }
    if (*jobs_made || *counts_made || *kept_made || *draws_made) {
        gpu::SetWriter(*k.sets[slot])
            .buffer(12, vk::DescriptorType::eStorageBuffer, k.jobs[slot].handle())
            .buffer(13, vk::DescriptorType::eStorageBuffer, k.counts[slot].handle())
            .buffer(14, vk::DescriptorType::eStorageBuffer, k.kept[slot].handle())
            .buffer(15, vk::DescriptorType::eStorageBuffer, k.kept[slot].handle())
            .buffer(16, vk::DescriptorType::eStorageBuffer, k.draws[slot].handle())
            .apply(device.handle());
    }
    std::memcpy(k.jobs[slot].bytes().data(), w.data(), w.size() * sizeof(std::uint32_t));
    k.jobs[slot].flush();
    k.slot = slot;
    k.jobs_now = total;
}

void Buildings::record_kept_cull(const frame::Recording& recording) {
    if (!drawn_kept()) return;
    const Kept& k = *kept_;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    const auto frame_sets = recording.layout.sets();
    const auto offsets = recording.layout.offsets(recording.frame.slot.slot);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *k.compute_layout, 0, frame_sets, offsets);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *k.compute_layout, 2, *k.sets[k.slot], {});
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    const gpu::MemoryDependency compute_then_compute{.src_stage = S::eComputeShader,
                                                     .src_access = A::eShaderWrite,
                                                     .dst_stage = S::eComputeShader,
                                                     .dst_access = A::eShaderRead | A::eShaderWrite};
    const std::uint32_t rows = (k.jobs_now + job_row - 1) / job_row;
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *k.cull);
    cmd.dispatch(std::min(k.jobs_now, job_row), rows, 1);
    recording.counters.dispatch("kept cull");
    recording.frame.sync.barrier(cmd, compute_then_compute);
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *k.scan);
    cmd.dispatch(lists, 1, 1);
    recording.counters.dispatch("kept scan");
    recording.frame.sync.barrier(cmd, compute_then_compute);
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *k.scatter);
    cmd.dispatch(std::min(k.jobs_now, job_row), rows, 1);
    recording.counters.dispatch("kept scatter");
    // the kept primitives for the vertex stage, the chunks' draws for the indirect draws
    recording.frame.sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                            .src_access = A::eShaderWrite,
                                                            .dst_stage = S::eVertexShader | S::eDrawIndirect,
                                                            .dst_access = A::eShaderRead | A::eIndirectCommandRead});
}

void Buildings::record_kept(const frame::Recording& recording, std::uint32_t list) {
    const Kept& k = *kept_;
    if (k.chunks[list] == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, list + 1 == lists ? *k.roofs : *k.walls);
    cmd.bindIndexBuffer(list + 1 == lists ? plain_index_.handle() : edged_index_.handle(), 0, vk::IndexType::eUint16);
    // set 2 under the frame's sets, which the scene subpass keeps bound
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *k.layout, 2, *k.sets[k.slot], {});
    for (std::uint32_t c = 0; c < k.chunks[list]; ++c) {
        cmd.drawIndexedIndirect(k.draws[k.slot].handle(), vk::DeviceSize{k.first_draw[list] + c} * draw_words * 4, 1,
                                draw_words * 4);
        // what the chunk may draw: the CPU's count (kept_counts reads what the GPU kept, once the frame is done)
        const std::uint32_t n = std::min(chunk_of[list], k.submitted[list] - (c * chunk_of[list]));
        recording.counters.draw(kind_of[list], std::uint64_t{n} * (vertices_of[list] - 2));
    }
}

Buildings::KeptCounts Buildings::kept_counts() const {
    KeptCounts out;
    if (kept_ == nullptr) return out;
    const Kept& k = *kept_;
    for (std::uint32_t list = 0; list < lists; ++list) {
        out.submitted[list] = std::uint64_t{k.submitted[list]} * (vertices_of[list] - 2);
    }
    if (k.jobs_now == 0 || !k.draws[k.slot]) return out;
    const gpu::Buffer& draws = k.draws[k.slot];
    draws.invalidate();
    const std::span<const std::byte> bytes = draws.bytes();
    for (std::uint32_t list = 0; list < lists; ++list) {
        for (std::uint32_t c = 0; c < k.chunks[list]; ++c) {
            std::uint32_t indices = 0;
            std::memcpy(&indices, bytes.data() + (std::size_t{k.first_draw[list] + c} * draw_words * 4),
                        sizeof indices);
            out.kept[list] += std::uint64_t{indices / indices_of[list]} * (vertices_of[list] - 2);
        }
    }
    return out;
}

} // namespace atlas::passes
