// The buildings pass's windows (passes/Walls.hpp; shaders/building/windows.slangh): this frame slot's jobs, the
// compute stage that decides every window of them (draw.slang's windows_cs), and the draws of the near windows' parts,
// their glass and their frames (window_near_vs with building_fs, window_glass_vs with window_glass_fs,
// window_frames_vs with window_frames_fs).
#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gpu/Descriptors.hpp"
#include "passes/Walls.hpp"
#include "twin/building/BuildingShader.hpp"

#include <algorithm>
#include <bit>
#include <cstring>

namespace atlas::passes {

namespace {

/// A window job's words (draw.slang's WINDOW_JOB_VECTORS uint4): its tile's push constants, its run, then each of its
/// walls' first window record, its first near record (no_near: none), its windows' widths and its facade record
/// (no_near: none), from these words.
constexpr std::uint32_t job_words = 276;
constexpr std::uint32_t job_records = 20;
constexpr std::uint32_t job_nears = 84;
constexpr std::uint32_t job_widths = 148;
constexpr std::uint32_t job_facades = 212;
constexpr std::uint32_t no_near = 0xFFFF'FFFFU;
/// The most windowed walls a job decides (windows_cs's workgroup), and the jobs a row of workgroups covers.
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

gpu::Result<void> Buildings::prepare_windows(const frame::PassContext& context) {
    auto w = std::make_unique<Windows>();
    w->device = &context.device;
    const vk::raii::Device& d = context.device.handle();
    using Stage = vk::ShaderStageFlagBits;
    const std::array bindings{
        gpu::Binding{.slot = 3, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 4, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 5, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 6, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 7, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 8, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 9, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 10, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 11, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    w->set_layout = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, static_cast<std::uint32_t>(w->sets.size())));
    w->pool = std::move(pool);
    for (vk::raii::DescriptorSet& set : w->sets) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, w->pool, w->set_layout));
        set = std::move(made);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*w->set_layout};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    w->layout = std::move(layout);
    const std::array<vk::DescriptorSetLayout, 3> sets{context.layout.frame_set_layout(),
                                                      context.layout.global_set_layout(), *w->set_layout};
    ATLAS_GPU_LET(compute_layout, gpu::make_layout(d, sets, frame::push_bytes, Stage::eCompute));
    w->compute_layout = std::move(compute_layout);
    ATLAS_GPU_LET(decide,
                  frame::make_compute(
                      context, {.name = "windows", .module = "building_draw", .configuration = context.configuration},
                      *draw_module_, "windows_cs", *w->compute_layout));
    w->decide = std::move(decide);
    // the near windows' parts, the glass and the frames: opaque, each on its wall's plane a lead of its own over its
    // cells (the frames a lead over the glass), drawn nearest first (record_windows), the frames' test GREATER_OR_EQUAL
    // (one window's frame over another's of the same path)
    enum class Part : std::uint8_t { near, facades, glass, frames };
    for (const Part part : {Part::near, Part::facades, Part::glass, Part::frames}) {
        const bool frames = part == Part::frames;
        const bool filled = part == Part::near || part == Part::facades;
        const char* const name = part == Part::near      ? "window parts"
                                 : part == Part::facades ? "facades"
                                 : frames                ? "window frames"
                                                         : "window glass";
        const char* const vertex = part == Part::near      ? "window_near_vs"
                                   : part == Part::facades ? "window_facade_vs"
                                   : frames                ? "window_frames_vs"
                                                           : "window_glass_vs";
        gpu::GraphicsDesc desc{
            .label = {.name = name, .module = "building_draw", .configuration = context.configuration},
            .module = *draw_module_,
            .vertex = vertex,
            .fragment = filled ? "building_fs" : (frames ? "window_frames_fs" : "window_glass_fs"),
            .layout = *w->layout,
            .target = context.pass.scene_target(),
            .depth_test = true,
            .depth_write = true,
            .depth_op = frames ? vk::CompareOp::eGreaterOrEqual : vk::CompareOp::eGreater,
            .stencil_test = true,
            .stencil = {.failOp = vk::StencilOp::eKeep,
                        .passOp = vk::StencilOp::eReplace,
                        .depthFailOp = vk::StencilOp::eKeep,
                        .compareOp = vk::CompareOp::eAlways,
                        .compareMask = 0,
                        .writeMask = shaders::OBJECTS_BIT,
                        .reference = shaders::OBJECTS_BIT}};
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        ATLAS_GPU_LET(pipeline, frame::make_pipeline(context, desc, frame::Overdraw::opaque));
        (part == Part::near ? w->near
                            : (part == Part::facades ? w->facades_pipeline : (frames ? w->frames : w->glass))) =
            std::move(pipeline);
    }
    windows_ = std::move(w);
    return {};
}

void Buildings::write_window_jobs(std::uint32_t slot, const frame::FrameState& state) {
    Windows& s = *windows_;
    s.jobs_now = 0;
    s.walls_now = 0;
    s.records_now = 0;
    s.nears_now = 0;
    s.facades_now = 0;
    std::vector<std::uint32_t>& w = s.words;
    w.clear();
    const std::span<const std::uint32_t> ranks = state.ranks->table();
    const std::span<const double> depths = state.ranks->depth();
    const bool weather = (state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U;
    const double focal = state.view ? state.view->camera().focal() : 0.0;
    const auto frame_width = static_cast<double>(shaders::building_window_frame_width(state.block.windows));
    constexpr std::uint8_t detailed =
        geometry::window_flag::plinth | geometry::window_flag::cornice | geometry::window_flag::glazing;
    std::uint32_t walls = 0;
    std::uint32_t records = 0;
    std::uint32_t nears = 0;
    std::uint32_t facades = 0;
    // the nearest tile first (the draws' order, so early depth testing rejects what the near windows hide); each run of
    // its facade walls whose items the frame draws with their windows (the rank's detail, building_windows_drawn's
    // first test: the other walls' windows would come out empty, though the drawn tiles' walls reach kilometres past
    // the windows' few hundred metres) or, with weather, with their flat detail (760 m), in jobs of at most 64 walls:
    // each wall's records one after another as building_window_records counts them, nearer than 160 m its windows'
    // near records, with a plinth or a cornice its facade record
    for (const TileDraw& d : draws_) {
        if (d.window_runs == 0 || d.tile == nullptr) continue;
        const BuildingTile& b = *d.tile;
        const auto row_of = [&](std::uint32_t wall) {
            const std::uint16_t item = b.window_items[wall];
            return item < d.rows.size() ? d.rows[item] : shaders::RANK_NONE;
        };
        const auto detail_of = [&](std::uint32_t wall) {
            const std::uint32_t row = row_of(wall);
            const std::uint32_t rank = row < ranks.size() ? ranks[row] : shaders::RANK_NONE;
            return rank != shaders::RANK_NONE ? shaders::rank_detail(rank) : 0U;
        };
        // building_window_count: a wall's windows where its item is nearer than win_d, a houseboat cabin's (drawn with
        // weather, as the boat) nearer than 520 m
        const auto count_of = [&](std::uint32_t wall, std::uint32_t detail) {
            const bool cabin = b.window_styles[wall] == geometry::WindowStyle::cabin;
            if (cabin && !weather) return 0U;
            const std::uint32_t reach = cabin ? shaders::BUILDING_DETAIL_CHURCH : shaders::BUILDING_DETAIL_WINDOWS;
            return (detail & reach) != 0U ? b.window_at[wall + 1] - b.window_at[wall] : 0U;
        };
        const auto decided = [&](std::uint32_t wall) {
            const std::uint32_t detail = detail_of(wall);
            return count_of(wall, detail) > 0 || (weather && (detail & shaders::BUILDING_DETAIL_FACADES) != 0U &&
                                                  (b.window_flags[wall] & detailed) != 0U);
        };
        shaders::BuildingPush push{};
        std::memcpy(&push, d.push.data(), sizeof push);
        const std::uint32_t base = d.windows_at - b.windows_at;
        push.at = {d.windows_at, push.at.y, base + b.prims_at, push.at.w};
        push.sun = {push.sun.x, push.sun.y, d.windows_from, d.reveals};
        const std::uint32_t first_run = d.runs_first + d.edged_runs + d.cell_runs + d.plain_runs;
        for (std::uint32_t r = 0; r < d.window_runs; ++r) {
            const Run& run = runs_[first_run + r];
            for (std::uint32_t i = run.first; i < run.end;) {
                if (!decided(i)) {
                    ++i;
                    continue;
                }
                const std::uint32_t first = i;
                while (i < run.end && i - first < job_size && decided(i)) ++i;
                const std::uint32_t n = i - first;
                if (records + (b.window_at[first + n] - b.window_at[first]) + n > max_windows) {
                    if (!windows_capped_) {
                        log::warn("vk buildings: a frame's windows pass {}: the farther ones are left out",
                                  max_windows);
                        windows_capped_ = true;
                    }
                    continue;
                }
                const std::size_t at = w.size();
                w.resize(at + job_words, 0U);
                std::memcpy(w.data() + at, &push, sizeof push);
                w[at + 16] = first;
                w[at + 17] = n;
                w[at + 18] = walls;
                w[at + 19] = d.facades;
                for (std::uint32_t k = 0; k < n; ++k) {
                    const std::uint32_t wall = first + k;
                    const std::uint32_t row = row_of(wall);
                    const std::uint32_t detail = detail_of(wall);
                    const std::uint8_t flags = b.window_flags[wall];
                    // building_window_records: its windows within their reach, its shop glazing within 760 m
                    const std::uint32_t windows = count_of(wall, detail);
                    const bool glazing = (flags & geometry::window_flag::glazing) != 0U &&
                                         (detail & shaders::BUILDING_DETAIL_FACADES) != 0U;
                    w[at + job_records + k] = records;
                    records += windows + (glazing ? 1U : 0U);
                    w[at + job_nears + k] = no_near;
                    if ((detail & shaders::BUILDING_DETAIL_DEEP) != 0U && windows > 0 &&
                        b.window_styles[wall] != geometry::WindowStyle::cabin) {
                        w[at + job_nears + k] = nears;
                        nears += windows;
                    }
                    const WindowWidths widths =
                        window_widths(detail, weather, row < depths.size() ? depths[row] : 0.0, focal, frame_width);
                    w[at + job_widths + k] = std::uint32_t{widths.frame} | (std::uint32_t{widths.sash} << 16U);
                    w[at + job_facades + k] = no_near;
                    if ((flags & (geometry::window_flag::plinth | geometry::window_flag::cornice)) != 0U &&
                        (detail & shaders::BUILDING_DETAIL_FACADES) != 0U) {
                        w[at + job_facades + k] = facades++;
                    }
                }
                walls += n;
            }
        }
    }
    const auto jobs = static_cast<std::uint32_t>(w.size() / job_words);
    if (jobs == 0) return;
    // into this frame slot's buffers (its previous frame has finished: its buffers and set are free)
    const gpu::Device& device = *s.device;
    using B = vk::BufferUsageFlagBits;
    const auto vectors = [](std::uint32_t n, std::uint32_t each) {
        return vk::DeviceSize{std::max(n, 1U)} * each * 16;
    };
    auto jobs_made =
        fit(device, s.jobs[slot], w.size() * sizeof(std::uint32_t), B::eStorageBuffer, gpu::MemoryUse::upload);
    auto records_made = fit(device, s.records[slot], vectors(records, shaders::BUILDING_WINDOW_RECORD_VECTORS),
                            B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto walls_made = fit(device, s.walls[slot], vectors(walls, shaders::BUILDING_WALL_RECORD_VECTORS),
                          B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto nears_made = fit(device, s.nears[slot], vectors(nears, shaders::BUILDING_NEAR_RECORD_VECTORS),
                          B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto facades_made = fit(device, s.facades[slot], vectors(facades, shaders::BUILDING_FACADE_RECORD_VECTORS),
                            B::eStorageBuffer, gpu::MemoryUse::gpu);
    if (!jobs_made || !records_made || !walls_made || !nears_made || !facades_made) {
        const gpu::Error& e = !jobs_made      ? jobs_made.error()
                              : !records_made ? records_made.error()
                              : !walls_made   ? walls_made.error()
                              : !nears_made   ? nears_made.error()
                                              : facades_made.error();
        log::error("vk buildings: the windows' {} jobs, {} walls, {} records, {} near ones and {} facades: {}", jobs,
                   walls, records, nears, facades, e.text());
        s.jobs[slot] = {};
        s.records[slot] = {};
        s.walls[slot] = {};
        s.nears[slot] = {};
        s.facades[slot] = {};
        return;
    }
    if (*jobs_made || *records_made || *walls_made || *nears_made || *facades_made) {
        gpu::SetWriter(*s.sets[slot])
            .buffer(3, vk::DescriptorType::eStorageBuffer, s.jobs[slot].handle())
            .buffer(4, vk::DescriptorType::eStorageBuffer, s.records[slot].handle())
            .buffer(5, vk::DescriptorType::eStorageBuffer, s.records[slot].handle())
            .buffer(6, vk::DescriptorType::eStorageBuffer, s.walls[slot].handle())
            .buffer(7, vk::DescriptorType::eStorageBuffer, s.walls[slot].handle())
            .buffer(8, vk::DescriptorType::eStorageBuffer, s.nears[slot].handle())
            .buffer(9, vk::DescriptorType::eStorageBuffer, s.nears[slot].handle())
            .buffer(10, vk::DescriptorType::eStorageBuffer, s.facades[slot].handle())
            .buffer(11, vk::DescriptorType::eStorageBuffer, s.facades[slot].handle())
            .apply(device.handle());
    }
    std::memcpy(s.jobs[slot].bytes().data(), w.data(), w.size() * sizeof(std::uint32_t));
    s.jobs[slot].flush();
    s.slot = slot;
    s.jobs_now = jobs;
    s.walls_now = walls;
    s.records_now = records;
    s.nears_now = nears;
    s.facades_now = facades;
    window_counts_ = {.jobs = jobs, .walls = walls, .windows = records, .nears = nears, .facades = facades};
}

void Buildings::record_window_decide(const frame::Recording& recording) {
    if (windows_ == nullptr || windows_->jobs_now == 0) return;
    const Windows& s = *windows_;
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
    recording.counters.dispatch("windows");
    // the records, for the windows' vertex stages
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    recording.frame.sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                            .src_access = A::eShaderWrite,
                                                            .dst_stage = S::eVertexShader,
                                                            .dst_access = A::eShaderRead});
}

void Buildings::record_windows(const frame::Recording& recording) {
    if (windows_ == nullptr || (windows_->records_now == 0 && windows_->facades_now == 0)) return;
    const Windows& s = *windows_;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    // set 2 under the frame's sets, which the scene subpass keeps bound; the frames' fragments' samples a pixel
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *s.layout, 2, *s.sets[s.slot], {});
    shaders::BuildingPush push{};
    push.at = {static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples()), 0U, 0U, 0U};
    cmd.pushConstants<shaders::BuildingPush>(*s.layout, frame::push_stages, 0, push);
    // the parts by their leads, nearest first, so early depth testing rejects what each hides of the next: the
    // facades' plinths and cornices and the near windows' sills and sash bars (an instance a record, from its vertex
    // 9), the frames (10 triangles each, unindexed), the glass (5 points each, the roofs' fans), the near windows'
    // reveals (their first 9 vertices). Under
    // the painter switch a frame and the glass it overlaps share their path's slot, and the glass's test (GREATER)
    // leaves the frame drawn first, as the path's strokes cover its fills.
    constexpr std::uint32_t reveal_vertices = 9;
    if (s.facades_now > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.facades_pipeline);
        cmd.draw(shaders::BUILDING_FACADE_VERTICES, s.facades_now, 0, 0);
        recording.counters.draw("facades", std::uint64_t{s.facades_now} * (shaders::BUILDING_FACADE_VERTICES / 3),
                                s.facades_now);
    }
    if (s.nears_now > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.near);
        cmd.draw(shaders::BUILDING_WINDOW_NEAR_VERTICES - reveal_vertices, s.nears_now, reveal_vertices, 0);
        recording.counters.draw(
            "window parts",
            std::uint64_t{s.nears_now} * ((shaders::BUILDING_WINDOW_NEAR_VERTICES - reveal_vertices) / 3), s.nears_now);
    }
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.frames);
    cmd.draw(s.records_now * shaders::BUILDING_WINDOW_FRAME_VERTICES, 1, 0, 0);
    recording.counters.draw("window frames",
                            std::uint64_t{s.records_now} * (shaders::BUILDING_WINDOW_FRAME_VERTICES / 3));
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.glass);
    cmd.bindIndexBuffer(plain_index_.handle(), 0, vk::IndexType::eUint16);
    for (std::uint32_t first = 0; first < s.records_now; first += plain_chunk) {
        const std::uint32_t n = std::min(plain_chunk, s.records_now - first);
        cmd.drawIndexed(n * plain_indices, 1, 0,
                        static_cast<std::int32_t>(first * shaders::BUILDING_WINDOW_GLASS_VERTICES), 0);
        recording.counters.draw("window glass", std::uint64_t{n} * (shaders::BUILDING_WINDOW_GLASS_VERTICES - 2));
    }
    if (s.nears_now > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.near);
        cmd.draw(reveal_vertices, s.nears_now, 0, 0);
        recording.counters.draw("window parts", std::uint64_t{s.nears_now} * (reveal_vertices / 3), s.nears_now);
    }
}

} // namespace atlas::passes
