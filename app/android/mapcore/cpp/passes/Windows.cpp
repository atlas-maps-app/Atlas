// The buildings pass's windows (passes/Walls.hpp; shaders/building/windows.slangh): this frame slot's jobs, the
// compute stage that decides every window of them (draw.slang's windows_cs), and the draws of the near windows' parts,
// their glass and their frames (window_near_vs with building_fs, window_glass_vs with window_glass_fs,
// window_frames_vs with window_frames_fs), of the churches' own windows (church_window_vs with window_glass_fs), of
// the walls' material faces (material_fill_vs and material_line_vs with material_fs, translucent), and the records the
// CPU lays each frame: the hoist beams, a kit's walls' boards' lines and eaves' shadows (roof_line_vs with
// roof_line_fs, kit_shadow_vs with material_fs) and its roofs' lines.
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
/// walls' first window record, its first near record (no_near: none), its windows' widths (where it draws church
/// windows, their leading's), its facade record (no_near: none), its church windows (no_near: none drawn; its first
/// church record, how many, each one's transoms), its material face's first fill record and first line record
/// (no_near: none), its count word (MaterialChoice::counts) and its ribs' colour, from these words.
constexpr std::uint32_t job_words = 596;
constexpr std::uint32_t job_records = 20;
constexpr std::uint32_t job_nears = 84;
constexpr std::uint32_t job_widths = 148;
constexpr std::uint32_t job_facades = 212;
constexpr std::uint32_t job_churches = 276;
constexpr std::uint32_t job_material_fills = 340;
constexpr std::uint32_t job_material_lines = 404;
constexpr std::uint32_t job_materials = 468;
constexpr std::uint32_t job_ribs = 532;
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
        gpu::Binding{.slot = 11, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 17, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 18, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 19, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 20, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 21, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 22, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 23, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 24, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex}};
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
    // the near windows' parts, the glass, the frames and the church windows: opaque, each on its wall's plane a lead of
    // its own over its cells (the frames a lead over the glass), drawn nearest first (record_windows), the frames' test
    // GREATER_OR_EQUAL (one window's frame over another's of the same path)
    enum class Part : std::uint8_t { near, facades, glass, frames, church, beams };
    for (const Part part : {Part::near, Part::facades, Part::glass, Part::frames, Part::church, Part::beams}) {
        const bool frames = part == Part::frames;
        const bool beams = part == Part::beams;
        const bool filled = part == Part::near || part == Part::facades;
        const char* const name = part == Part::near      ? "window parts"
                                 : part == Part::facades ? "facades"
                                 : part == Part::church  ? "church windows"
                                 : beams                 ? "hoist beams"
                                 : frames                ? "window frames"
                                                         : "window glass";
        const char* const vertex = part == Part::near      ? "window_near_vs"
                                   : part == Part::facades ? "window_facade_vs"
                                   : part == Part::church  ? "church_window_vs"
                                   : beams                 ? "beam_vs"
                                   : frames                ? "window_frames_vs"
                                                           : "window_glass_vs";
        gpu::GraphicsDesc desc{
            .label = {.name = name, .module = "building_draw", .configuration = context.configuration},
            .module = *draw_module_,
            .vertex = vertex,
            .fragment =
                filled ? "building_fs" : (beams ? "beam_fs" : (frames ? "window_frames_fs" : "window_glass_fs")),
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
        vk::raii::Pipeline& made = part == Part::near      ? w->near
                                   : part == Part::facades ? w->facades_pipeline
                                   : part == Part::church  ? w->church
                                   : beams                 ? w->beams_pipeline
                                   : frames                ? w->frames
                                                           : w->glass;
        made = std::move(pipeline);
    }
    // the material faces: translucent, each on its wall's plane a lead past its shop glazing's and short of its
    // windows', the test on and writes off, over what lies under them (premultiplied source over, as SVG's opacity);
    // the lines each sample once a path (they test and set BUILDING_MATERIAL_STENCIL: a sample takes only the lines of
    // the wall nearest it, so the first to reach it marks it), as SVG strokes a path
    for (const bool lines : {false, true}) {
        gpu::GraphicsDesc desc{.label = {.name = lines ? "material lines" : "material fills",
                                         .module = "building_draw",
                                         .configuration = context.configuration},
                               .module = *draw_module_,
                               .vertex = lines ? "material_line_vs" : "material_fill_vs",
                               .fragment = "material_fs",
                               .layout = *w->layout,
                               .target = context.pass.scene_target(),
                               .depth_test = true,
                               .depth_write = false,
                               .depth_op = vk::CompareOp::eGreater};
        if (lines) {
            desc.stencil_test = true;
            desc.stencil = {.failOp = vk::StencilOp::eKeep,
                            .passOp = vk::StencilOp::eReplace,
                            .depthFailOp = vk::StencilOp::eKeep,
                            .compareOp = vk::CompareOp::eNotEqual,
                            .compareMask = shaders::BUILDING_MATERIAL_STENCIL,
                            .writeMask = shaders::BUILDING_MATERIAL_STENCIL,
                            .reference = shaders::BUILDING_MATERIAL_STENCIL};
        }
        desc.blends[0] = gpu::Blend::over();
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        ATLAS_GPU_LET(pipeline, frame::make_pipeline(context, desc, frame::Overdraw::translucent));
        (lines ? w->material_lines_pipeline : w->material_fills_pipeline) = std::move(pipeline);
    }
    // a kit roof's tile courses and lit ridges: translucent, a lead over its slopes along each, the test on and writes
    // off, over what lies under them; each line its own (a slope's courses never meet)
    {
        gpu::GraphicsDesc desc{
            .label = {.name = "roof lines", .module = "building_draw", .configuration = context.configuration},
            .module = *draw_module_,
            .vertex = "roof_line_vs",
            .fragment = "roof_line_fs",
            .layout = *w->layout,
            .target = context.pass.scene_target(),
            .depth_test = true,
            .depth_write = false,
            .depth_op = vk::CompareOp::eGreater};
        desc.blends[0] = gpu::Blend::over();
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        ATLAS_GPU_LET(pipeline, frame::make_pipeline(context, desc, frame::Overdraw::translucent));
        w->roof_lines_pipeline = std::move(pipeline);
    }
    // a kit wall's eaves' shadow: translucent on its wall's plane a lead over its corner boards, the test on and writes
    // off, over what lies under it (its boards' lines, drawn just before it with the roof lines' pipeline, among them)
    {
        gpu::GraphicsDesc desc{
            .label = {.name = "kit shadows", .module = "building_draw", .configuration = context.configuration},
            .module = *draw_module_,
            .vertex = "kit_shadow_vs",
            .fragment = "material_fs",
            .layout = *w->layout,
            .target = context.pass.scene_target(),
            .depth_test = true,
            .depth_write = false,
            .depth_op = vk::CompareOp::eGreater};
        desc.blends[0] = gpu::Blend::over();
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        ATLAS_GPU_LET(pipeline, frame::make_pipeline(context, desc, frame::Overdraw::translucent));
        w->kit_shadows_pipeline = std::move(pipeline);
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
    s.churches_now = 0;
    s.segments_now = 0;
    s.material_fills_now = 0;
    s.material_lines_now = 0;
    s.beams_now = 0;
    s.roof_lines_now = 0;
    s.kit_lines_now = 0;
    s.kit_shadows_now = 0;
    std::uint32_t kit_lines = 0;
    std::uint32_t kit_shadows = 0;
    std::vector<std::uint32_t>& w = s.words;
    w.clear();
    const std::span<const std::uint32_t> ranks = state.ranks->table();
    const std::span<const double> depths = state.ranks->depth();
    const bool weather = (state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U;
    const bool night = (state.block.theme.flags.x & shaders::THEME_NIGHT) != 0U;
    const double focal = state.view ? state.view->camera().focal() : 0.0;
    const auto frame_width = static_cast<double>(shaders::building_window_frame_width(state.block.windows));
    constexpr std::uint8_t detailed =
        geometry::window_flag::plinth | geometry::window_flag::cornice | geometry::window_flag::glazing;
    std::uint32_t walls = 0;
    std::uint32_t records = 0;
    std::uint32_t nears = 0;
    std::uint32_t facades = 0;
    std::uint32_t churches = 0;
    std::uint32_t segments = 0;
    std::uint32_t material_fills = 0;
    std::uint32_t material_lines = 0;
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
        // a church's own windows, where it has any (building_church_drawn but its wall's turn): with weather, its item
        // nearer than 520 m
        const auto church_of = [&](std::uint32_t wall, std::uint32_t detail) {
            return weather && (b.window_flags[wall] & geometry::window_flag::church) != 0U &&
                   (detail & shaders::BUILDING_DETAIL_CHURCH) != 0U && wall < b.window_churches.size() &&
                   b.window_churches[wall] != 0U;
        };
        // its material face's records (passes::material_choice): with weather, its item nearer than 1.35 times win_d
        const auto material_of = [&](std::uint32_t wall, std::uint32_t detail) {
            if (!weather || (detail & shaders::BUILDING_DETAIL_MATERIALS) == 0U || wall >= b.window_materials.size()) {
                return MaterialChoice{};
            }
            const BuildingTile::MaterialWall& m = b.window_materials[wall];
            if (m.kind == geometry::Material::none) return MaterialChoice{};
            const std::uint32_t row = row_of(wall);
            return material_choice(m, night, row < depths.size() ? depths[row] : 0.0, focal);
        };
        const auto decided = [&](std::uint32_t wall) {
            const std::uint32_t detail = detail_of(wall);
            if (count_of(wall, detail) > 0 || church_of(wall, detail) ||
                (weather && (detail & shaders::BUILDING_DETAIL_FACADES) != 0U &&
                 (b.window_flags[wall] & detailed) != 0U)) {
                return true;
            }
            const MaterialChoice m = material_of(wall, detail);
            return m.fills + m.lines > 0;
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
                    // no material face, unless one is reserved below (a church's cap passes over the rest)
                    w[at + job_material_fills + k] = no_near;
                    w[at + job_material_lines + k] = no_near;
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
                    // its church windows (none of its plain ones then): their records, their leading's width
                    w[at + job_churches + k] = no_near;
                    if (church_of(wall, detail)) {
                        const std::uint32_t bays = b.window_churches[wall] & 0xFFFFU;
                        const std::uint32_t transoms = b.window_churches[wall] >> 16U;
                        if (churches + bays > max_churches) {
                            if (!churches_capped_) {
                                log::warn("vk buildings: a frame's church windows pass {}: the farther ones are left "
                                          "out",
                                          max_churches);
                                churches_capped_ = true;
                            }
                            continue;
                        }
                        w[at + job_churches + k] = churches | (bays << 16U) | (transoms << 24U);
                        w[at + job_widths + k] = church_leading(row < depths.size() ? depths[row] : 0.0, focal);
                        churches += bays;
                        segments = std::max(segments, transoms + 1);
                    }
                    // its material face's fills and lines, one record each
                    const MaterialChoice m = material_of(wall, detail);
                    if (m.fills + m.lines > 0) {
                        if (material_fills + m.fills > max_materials || material_lines + m.lines > max_materials) {
                            if (!materials_capped_) {
                                log::warn("vk buildings: a frame's material faces pass {} fills or lines: the farther "
                                          "ones are left out",
                                          max_materials);
                                materials_capped_ = true;
                            }
                            continue;
                        }
                        if (m.fills > 0) w[at + job_material_fills + k] = material_fills;
                        if (m.lines > 0) w[at + job_material_lines + k] = material_lines;
                        w[at + job_materials + k] = m.counts;
                        w[at + job_ribs + k] = m.ribs;
                        material_fills += m.fills;
                        material_lines += m.lines;
                    }
                }
                walls += n;
            }
        }
    }
    // the hoist beams under old gables (each tile's, with weather, their item nearer than 260 m), a record each with
    // its tile's place, its painter slot and its width (windows.slangh's BuildingBeam)
    std::vector<hlsl::uint4>& beams = s.beam_words;
    beams.clear();
    for (const TileDraw& d : draws_) {
        if (!weather || d.tile == nullptr || d.tile->beams.empty()) continue;
        shaders::BuildingPush push{};
        std::memcpy(&push, d.push.data(), sizeof push);
        const auto offset = std::bit_cast<hlsl::uint4>(push.place.offset);
        const auto scale = std::bit_cast<hlsl::uint4>(push.place.scale);
        for (const BuildingTile::Beam& beam : d.tile->beams) {
            const std::uint32_t row = beam.item < d.rows.size() ? d.rows[beam.item] : shaders::RANK_NONE;
            const std::uint32_t rank = row < ranks.size() ? ranks[row] : shaders::RANK_NONE;
            if (rank == shaders::RANK_NONE) continue;
            const std::uint32_t detail = shaders::rank_detail(rank);
            if ((detail & shaders::BUILDING_DETAIL_BEAMS) == 0U) continue;
            const bool near = (detail & shaders::BUILDING_DETAIL_NEAR) != 0U;
            const std::uint32_t painter = shaders::rank_slot(rank) + (near ? beam.near_slot : beam.far_slot);
            const std::uint32_t width = beam_width(row < depths.size() ? depths[row] : 0.0, focal);
            const auto bits = [](float v) { return std::bit_cast<std::uint32_t>(v); };
            const hlsl::uint4 from = {bits(beam.from[0]), bits(beam.from[1]), bits(beam.from[2]), painter};
            const hlsl::uint4 to = {bits(beam.to[0]), bits(beam.to[1]), bits(beam.to[2]), width};
            const hlsl::uint4 normal = {bits(beam.normal[0]), bits(beam.normal[1]), bits(beam.normal[2]), 0U};
            const hlsl::uint4 test = {bits(beam.test[0]), bits(beam.test[1]), bits(beam.test[2]), 0U};
            for (const hlsl::uint4& v : {from, to, normal, test, offset, scale}) beams.push_back(v);
        }
    }
    const auto beam_count = static_cast<std::uint32_t>(beams.size() / shaders::BUILDING_BEAM_VECTORS);
    // the lines' records, with weather by day: a kit's walls' boards' lines (where the view resolves them: a record a
    // line, its wall's turn and plane), then their eaves' shadows (a record a wall), then its roofs' lines
    std::vector<hlsl::uint4>& lines = s.roof_line_words;
    lines.clear();
    const auto bits = [](float v) { return std::bit_cast<std::uint32_t>(v); };
    // a tile's item's painter slot and its depth, or none where the frame does not draw it
    const auto placed_in = [&](const TileDraw& d, std::uint32_t item, std::uint16_t near_slot,
                               std::uint16_t far_slot) -> std::optional<std::pair<std::uint32_t, double>> {
        const std::uint32_t row = item < d.rows.size() ? d.rows[item] : shaders::RANK_NONE;
        const std::uint32_t rank = row < ranks.size() ? ranks[row] : shaders::RANK_NONE;
        if (rank == shaders::RANK_NONE) return std::nullopt;
        const bool near = (shaders::rank_detail(rank) & shaders::BUILDING_DETAIL_NEAR) != 0U;
        return std::pair{shaders::rank_slot(rank) + (near ? near_slot : far_slot),
                         row < depths.size() ? depths[row] : 0.0};
    };
    for (const bool shadows : {false, true}) {
        for (const TileDraw& d : draws_) {
            if (!weather || night || d.tile == nullptr || d.tile->kit_walls.empty()) continue;
            shaders::BuildingPush push{};
            std::memcpy(&push, d.push.data(), sizeof push);
            const auto offset = std::bit_cast<hlsl::uint4>(push.place.offset);
            const auto scale = std::bit_cast<hlsl::uint4>(push.place.scale);
            for (const BuildingTile::KitWall& k : d.tile->kit_walls) {
                const hlsl::uint4 test = {bits(k.test[0]), bits(k.test[1]), bits(k.test[2]), k.rgb};
                if (shadows) {
                    if (!k.shadow) continue;
                    const auto at = placed_in(d, k.item, k.shadow_near, k.shadow_far);
                    if (!at) continue;
                    const std::array<std::array<float, 3>, 4>& q = k.shade;
                    const hlsl::uint4 foot = {bits(q[0][0]), bits(q[0][2]), bits(q[1][0]), bits(q[1][2])};
                    const hlsl::uint4 heights = {bits(q[0][1]), bits(q[2][1]), at->first,
                                                 shaders::BUILDING_KIT_SHADOW_RGB};
                    const hlsl::uint4 normal = {bits(k.normal[0]), bits(k.normal[1]), bits(k.normal[2]),
                                                shaders::BUILDING_KIT_SHADOW_OPACITY};
                    const hlsl::uint4 on = {test.x, test.y, test.z, 0U};
                    for (const hlsl::uint4& v : {foot, heights, normal, on, offset, scale}) lines.push_back(v);
                    ++kit_shadows;
                    continue;
                }
                if (k.boards < 2) continue;
                const auto at = placed_in(d, k.item, k.lines_near, k.lines_far);
                if (!at || !kit_lines_shown(at->second, focal, k.spacing)) continue;
                const std::uint32_t flags =
                    shaders::BUILDING_ROOF_LINE_ON_WALL | (32U << shaders::BUILDING_ROOF_LINE_OPACITY_SHIFT);
                const hlsl::uint4 normal = {bits(k.normal[0]), bits(k.normal[1]), bits(k.normal[2]), flags};
                for (std::uint32_t j = 1; j < k.boards; ++j) {
                    const auto [a, b] = kit_line_ends(k, j);
                    const hlsl::uint4 from = {bits(a[0]), bits(a[1]), bits(a[2]), at->first};
                    const hlsl::uint4 to = {bits(b[0]), bits(b[1]), bits(b[2]), kit_lines_width};
                    for (const hlsl::uint4& v : {from, to, normal, test, offset, scale}) lines.push_back(v);
                    ++kit_lines;
                }
            }
        }
    }
    // a kit roof's lines (each tile's, with weather by day): each slope's tile courses where the view resolves them, a
    // record a course with its slope's turn, and the lit ridges whichever way they turn (windows.slangh's
    // BuildingRoofLine)
    for (const TileDraw& d : draws_) {
        if (!weather || night || d.tile == nullptr || (d.tile->courses.empty() && d.tile->ridges.empty())) continue;
        shaders::BuildingPush push{};
        std::memcpy(&push, d.push.data(), sizeof push);
        const auto offset = std::bit_cast<hlsl::uint4>(push.place.offset);
        const auto scale = std::bit_cast<hlsl::uint4>(push.place.scale);
        const auto placed = [&](std::uint32_t item, std::uint16_t near_slot, std::uint16_t far_slot) {
            return placed_in(d, item, near_slot, far_slot);
        };
        for (const BuildingTile::Courses& c : d.tile->courses) {
            const auto at = placed(c.item, c.near_slot, c.far_slot);
            if (!at || !courses_shown(at->second, focal, c.rstep)) continue;
            const std::uint32_t width = courses_width(at->second, focal);
            const std::uint32_t flags = 30U << shaders::BUILDING_ROOF_LINE_OPACITY_SHIFT;
            const hlsl::uint4 normal = {bits(c.normal[0]), bits(c.normal[1]), bits(c.normal[2]), flags};
            const hlsl::uint4 test = {bits(c.test[0]), bits(c.test[1]), bits(c.test[2]), c.rgb};
            for (std::uint32_t j = 1; j < c.k; ++j) {
                const auto [a, b] = course_ends(c, j);
                const hlsl::uint4 from = {bits(a[0]), bits(a[1]), bits(a[2]), at->first};
                const hlsl::uint4 to = {bits(b[0]), bits(b[1]), bits(b[2]), width};
                for (const hlsl::uint4& v : {from, to, normal, test, offset, scale}) lines.push_back(v);
            }
        }
        for (const BuildingTile::Ridge& r : d.tile->ridges) {
            const auto at = placed(r.item, r.near_slot, r.far_slot);
            if (!at) continue;
            const std::uint32_t flags = shaders::BUILDING_ROOF_LINE_UNTURNED | shaders::BUILDING_ROOF_LINE_ROUND |
                                        (55U << shaders::BUILDING_ROOF_LINE_OPACITY_SHIFT);
            const hlsl::uint4 from = {bits(r.from[0]), bits(r.from[1]), bits(r.from[2]), at->first};
            const hlsl::uint4 to = {bits(r.to[0]), bits(r.to[1]), bits(r.to[2]), ridge_width(at->second, focal)};
            const hlsl::uint4 normal = {0U, 0U, 0U, flags};
            const hlsl::uint4 test = {0U, 0U, 0U, shaders::BUILDING_ROOF_RIDGE_RGB};
            for (const hlsl::uint4& v : {from, to, normal, test, offset, scale}) lines.push_back(v);
        }
    }
    static_assert(shaders::BUILDING_KIT_SHADOW_VECTORS == shaders::BUILDING_ROOF_LINE_VECTORS);
    const auto line_count = static_cast<std::uint32_t>(lines.size() / shaders::BUILDING_ROOF_LINE_VECTORS);
    const auto jobs = static_cast<std::uint32_t>(w.size() / job_words);
    if (jobs == 0 && beam_count == 0 && line_count == 0) return;
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
    auto churches_made = fit(device, s.churches[slot], vectors(churches, shaders::BUILDING_CHURCH_RECORD_VECTORS),
                             B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto fills_made =
        fit(device, s.material_fills[slot], vectors(material_fills, shaders::BUILDING_MATERIAL_FILL_VECTORS),
            B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto lines_made =
        fit(device, s.material_lines[slot], vectors(material_lines, shaders::BUILDING_MATERIAL_LINE_VECTORS),
            B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto beams_made = fit(device, s.beams[slot], vectors(beam_count, shaders::BUILDING_BEAM_VECTORS), B::eStorageBuffer,
                          gpu::MemoryUse::upload);
    auto roof_made = fit(device, s.roof_lines[slot], vectors(line_count, shaders::BUILDING_ROOF_LINE_VECTORS),
                         B::eStorageBuffer, gpu::MemoryUse::upload);
    if (!jobs_made || !records_made || !walls_made || !nears_made || !facades_made || !churches_made || !fills_made ||
        !lines_made || !beams_made || !roof_made) {
        const gpu::Error& e = !jobs_made       ? jobs_made.error()
                              : !records_made  ? records_made.error()
                              : !walls_made    ? walls_made.error()
                              : !nears_made    ? nears_made.error()
                              : !facades_made  ? facades_made.error()
                              : !churches_made ? churches_made.error()
                              : !fills_made    ? fills_made.error()
                              : !lines_made    ? lines_made.error()
                              : !beams_made    ? beams_made.error()
                                               : roof_made.error();
        log::error("vk buildings: the windows' {} jobs, {} walls, {} records, {} near ones, {} facades, {} church "
                   "windows, {} material fills and {} material lines: {}",
                   jobs, walls, records, nears, facades, churches, material_fills, material_lines, e.text());
        s.jobs[slot] = {};
        s.records[slot] = {};
        s.walls[slot] = {};
        s.nears[slot] = {};
        s.facades[slot] = {};
        s.churches[slot] = {};
        s.material_fills[slot] = {};
        s.material_lines[slot] = {};
        s.beams[slot] = {};
        s.roof_lines[slot] = {};
        return;
    }
    if (*jobs_made || *records_made || *walls_made || *nears_made || *facades_made || *churches_made || *fills_made ||
        *lines_made || *beams_made || *roof_made) {
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
            .buffer(17, vk::DescriptorType::eStorageBuffer, s.churches[slot].handle())
            .buffer(18, vk::DescriptorType::eStorageBuffer, s.churches[slot].handle())
            .buffer(19, vk::DescriptorType::eStorageBuffer, s.material_fills[slot].handle())
            .buffer(20, vk::DescriptorType::eStorageBuffer, s.material_fills[slot].handle())
            .buffer(21, vk::DescriptorType::eStorageBuffer, s.material_lines[slot].handle())
            .buffer(22, vk::DescriptorType::eStorageBuffer, s.material_lines[slot].handle())
            .buffer(23, vk::DescriptorType::eStorageBuffer, s.beams[slot].handle())
            .buffer(24, vk::DescriptorType::eStorageBuffer, s.roof_lines[slot].handle())
            .apply(device.handle());
    }
    // never from an empty vector's data(), not even no bytes (UBSan's nonnull check on Linux)
    if (!w.empty()) {
        std::memcpy(s.jobs[slot].bytes().data(), w.data(), w.size() * sizeof(std::uint32_t));
        s.jobs[slot].flush();
    }
    if (beam_count > 0) {
        std::memcpy(s.beams[slot].bytes().data(), beams.data(), beams.size() * sizeof(hlsl::uint4));
        s.beams[slot].flush();
    }
    if (line_count > 0) {
        std::memcpy(s.roof_lines[slot].bytes().data(), lines.data(), lines.size() * sizeof(hlsl::uint4));
        s.roof_lines[slot].flush();
    }
    s.slot = slot;
    s.jobs_now = jobs;
    s.walls_now = walls;
    s.records_now = records;
    s.nears_now = nears;
    s.facades_now = facades;
    s.churches_now = churches;
    s.segments_now = segments;
    s.material_fills_now = material_fills;
    s.material_lines_now = material_lines;
    s.beams_now = beam_count;
    s.roof_lines_now = line_count - kit_lines - kit_shadows;
    s.kit_lines_now = kit_lines;
    s.kit_shadows_now = kit_shadows;
    window_counts_ = {.jobs = jobs,
                      .walls = walls,
                      .windows = records,
                      .nears = nears,
                      .facades = facades,
                      .churches = churches,
                      .material_fills = material_fills,
                      .material_lines = material_lines,
                      .beams = beam_count,
                      .roof_lines = s.roof_lines_now,
                      .kit_lines = s.kit_lines_now,
                      .kit_shadows = s.kit_shadows_now};
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
    if (windows_ == nullptr || (windows_->records_now == 0 && windows_->facades_now == 0 &&
                                windows_->churches_now == 0 && windows_->beams_now == 0)) {
        return;
    }
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
    // the hoist beams under old gables: an instance a record, its quad
    if (s.beams_now > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.beams_pipeline);
        cmd.draw(shaders::BUILDING_BEAM_VERTICES, s.beams_now, 0, 0);
        recording.counters.draw("hoist beams", std::uint64_t{s.beams_now} * 2, s.beams_now);
    }
    // the church windows: an instance a record, its arches' fans and its leading's bars (as many as the frame's most)
    if (s.churches_now > 0) {
        const std::uint32_t vertices =
            shaders::BUILDING_CHURCH_ARCH_VERTICES + (s.segments_now * shaders::BUILDING_CHURCH_SEGMENT_VERTICES);
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.church);
        cmd.draw(vertices, s.churches_now, 0, 0);
        recording.counters.draw("church windows", std::uint64_t{s.churches_now} * (vertices / 3), s.churches_now);
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

void Buildings::record_materials(const frame::Recording& recording) {
    if (windows_ == nullptr || (windows_->material_fills_now == 0 && windows_->material_lines_now == 0)) return;
    const Windows& s = *windows_;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *s.layout, 2, *s.sets[s.slot], {});
    shaders::BuildingPush push{};
    push.at = {static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples()), 0U, 0U, 0U};
    cmd.pushConstants<shaders::BuildingPush>(*s.layout, frame::push_stages, 0, push);
    // the fills (a glass wall's glass, a parking garage's decks), then the lines over them: an instance a record
    if (s.material_fills_now > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.material_fills_pipeline);
        cmd.draw(shaders::BUILDING_MATERIAL_FILL_VERTICES, s.material_fills_now, 0, 0);
        recording.counters.draw("material fills",
                                std::uint64_t{s.material_fills_now} * (shaders::BUILDING_MATERIAL_FILL_VERTICES / 3),
                                s.material_fills_now);
    }
    if (s.material_lines_now > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.material_lines_pipeline);
        cmd.draw(shaders::BUILDING_MATERIAL_LINE_VERTICES, s.material_lines_now, 0, 0);
        recording.counters.draw("material lines",
                                std::uint64_t{s.material_lines_now} * (shaders::BUILDING_MATERIAL_LINE_VERTICES / 3),
                                s.material_lines_now);
    }
}

void Buildings::record_roof_lines(const frame::Recording& recording) {
    if (windows_ == nullptr || windows_->roof_lines_now == 0) return;
    const Windows& s = *windows_;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *s.layout, 2, *s.sets[s.slot], {});
    shaders::BuildingPush push{};
    push.at = {static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples()), 0U, 0U, 0U};
    cmd.pushConstants<shaders::BuildingPush>(*s.layout, frame::push_stages, 0, push);
    // a kit roof's tile courses and lit ridges: an instance a record, its quad (after the kit walls' records)
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.roof_lines_pipeline);
    cmd.draw(shaders::BUILDING_ROOF_LINE_VERTICES, s.roof_lines_now, 0, s.kit_lines_now + s.kit_shadows_now);
    recording.counters.draw("roof lines", std::uint64_t{s.roof_lines_now} * 2, s.roof_lines_now);
}

void Buildings::record_kit_walls(const frame::Recording& recording) {
    if (windows_ == nullptr || (windows_->kit_lines_now == 0 && windows_->kit_shadows_now == 0)) return;
    const Windows& s = *windows_;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *s.layout, 2, *s.sets[s.slot], {});
    shaders::BuildingPush push{};
    push.at = {static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples()), 0U, 0U, 0U};
    cmd.pushConstants<shaders::BuildingPush>(*s.layout, frame::push_stages, 0, push);
    // a kit wall's boards' lines (an instance a record, its quad), then its eaves' shadows over them (a fan each)
    if (s.kit_lines_now > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.roof_lines_pipeline);
        cmd.draw(shaders::BUILDING_ROOF_LINE_VERTICES, s.kit_lines_now, 0, 0);
        recording.counters.draw("kit lines", std::uint64_t{s.kit_lines_now} * 2, s.kit_lines_now);
    }
    if (s.kit_shadows_now > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.kit_shadows_pipeline);
        cmd.draw(shaders::BUILDING_KIT_SHADOW_VERTICES, s.kit_shadows_now, 0, s.kit_lines_now);
        recording.counters.draw("kit shadows", std::uint64_t{s.kit_shadows_now} * 3, s.kit_shadows_now);
    }
}

} // namespace atlas::passes
