// The buildings pass (passes/Walls.hpp): stage T into the walls heap, stage F's rows, light and draw lists, the walls'
// pipeline and draws, and the twin. The roofs' pipeline, draws and the light's are passes/Roofs.cpp.
#include "passes/Walls.hpp"

#include "base/assert.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "gen/spirv/building/draw.hpp"
#include "scene/Cover.hpp"
#include "scene/Residency.hpp"
#include "sun/Samples.hpp"
#include "twin/Twin.hpp"
#include "twin/building/BuildingShader.hpp"
#include "twin/building/BuildingTwin.hpp"

#include <algorithm>
#include <bit>
#include <chrono>
#include <cstring>
#include <span>

namespace atlas::passes {

namespace {

using Words = std::array<std::uint32_t, 4>;

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

/// The pass's place in the registry, unknown before the first tile builds.
constexpr std::uint32_t no_index = 0xFFFF'FFFFU;

} // namespace

void Buildings::kinds(frame::Selectors& selectors) { kind_ = selectors.kind("building"); }

void Buildings::select(const frame::FrameState& state, frame::Selectors& selectors) {
    (void)state;
    // _draw_building: a wall's ncol × nrow cells where d < 460 (band 0), one cell beyond
    selectors.set(kind_, geometry::cells_reach);
}

gpu::Result<void> Buildings::build(scene::TileBuild& b) const {
    pass_index_.store(b.pass(), std::memory_order_relaxed);
    heaps_.store(&b.heaps(), std::memory_order_relaxed);
    const tiles::StyleTables* style = b.style();
    const sun::Region region = style != nullptr ? sun::region_of(*style) : sun::Region{};
    const geometry::TileBuildings g = geometry::build_buildings({.tile = &b.tile(),
                                                                 .head = &b.head(),
                                                                 .level = b.level(),
                                                                 .region = {.water_y = region.water_y},
                                                                 .kind = kind_});
    if (g.items.empty()) return {};
    auto layout = std::make_shared<BuildingTile>();
    const std::vector<Words> words = heap_words(g, b.origin().x, b.origin().z, *layout);
    ATLAS_GPU_LET(range,
                  b.allocate(scene::HeapKind::walls, static_cast<std::uint32_t>(words.size() * sizeof(Words)), 16));
    b.heaps().write(range, std::as_bytes(std::span(words)));
    b.out().ranges[0] = range;
    layout->cells_per_side = g.cells_per_side;
    layout->ids.reserve(g.items.size());
    layout->owners.reserve(g.items.size());
    for (const geometry::BuildingItem& item : g.items) {
        b.add_item(item.spec);
        layout->ids.push_back(item.id);
        layout->owners.push_back(
            static_cast<std::uint8_t>((item.relation ? 2U : 0U) | (item.source == geometry::Source::part ? 1U : 0U)));
    }
    const std::size_t bytes =
        sizeof(BuildingTile) +
        ((layout->edged_first.size() + layout->cells_first.size() + layout->plain_first.size()) * 4) +
        (layout->ids.size() * 9) + (layout->lights.size() * sizeof(BuildingLight)) +
        (layout->rings.size() * sizeof(BuildingRing)) + (layout->ring_points.size() * sizeof(float));
    b.keep(std::shared_ptr<const BuildingTile>(std::move(layout)), bytes);
    return {};
}

std::uint32_t first_item(const scene::ResidentTile& tile, std::uint16_t kind) noexcept {
    for (std::size_t i = 0; i < tile.items.size(); ++i) {
        if (tile.items[i].kind == kind) return static_cast<std::uint32_t>(i);
    }
    return static_cast<std::uint32_t>(tile.items.size());
}

void Buildings::write_rows(const scene::ResidentTile& tile, const scene::PassTile& mine, const BuildingTile& b) {
    if (rows_written_.contains(tile.handle)) return;
    scene::Heaps* heaps = heaps_.load(std::memory_order_relaxed);
    if (heaps == nullptr) return;
    const std::uint32_t first = first_item(tile, kind_);
    std::vector<std::uint32_t> rows(std::size_t{(b.items + 3) / 4} * 4, 0);
    for (std::uint32_t k = 0; k < b.items && first + k < tile.rows.size(); ++k) rows[k] = tile.rows[first + k];
    heaps->write(mine.ranges[0], std::as_bytes(std::span(rows)), b.rows_at * 16);
    heaps->flush();
    rows_written_.insert(tile.handle);
}

void Buildings::light(const shaders::Theme& theme, std::uint32_t slot, const scene::ResidentTile& tile,
                      const scene::PassTile& mine, const BuildingTile& b) {
    std::array<std::uint64_t, 2>& lit = lit_[tile.handle];
    if (lit[slot] == moment_) return;
    scene::Heaps* heaps = heaps_.load(std::memory_order_relaxed);
    if (heaps == nullptr) return;
    const auto started = std::chrono::steady_clock::now();
    colours_.resize(std::size_t{b.faces} * 2);
    building_colours(theme, b.lights, colours_);
    heaps->write(mine.ranges[0], std::as_bytes(std::span(colours_)),
                 (b.colours_at * 16) + (slot * b.faces * 2 * static_cast<std::uint32_t>(sizeof(std::uint32_t))));
    heaps->flush();
    lit[slot] = moment_;
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
    ++lighting_.tiles;
    lighting_.faces += b.faces;
    lighting_.ms += ms;
    lighting_.longest_ms = std::max(lighting_.longest_ms, ms);
}

void Buildings::update(const frame::FrameState& state) {
    draws_.clear();
    runs_.clear();
    drawn_handles_.clear();
    if (state.cover == nullptr || state.ranks == nullptr) return;
    const std::uint32_t index = pass_index_.load(std::memory_order_relaxed);
    if (index == no_index) return;
    // a new moment (the frame block's theme): every tile's colours again, slot by slot, as each is drawn
    const std::span<const std::byte> theme = std::as_bytes(std::span(&state.block.theme, 1));
    if (!std::ranges::equal(theme, theme_) || moment_ == 0) {
        std::ranges::copy(theme, theme_.begin());
        ++moment_;
    }
    const std::uint32_t slot = state.slot % 2;
    for (const scene::CoverTile& t : state.cover->tiles()) {
        if (t.drawn == nullptr || index >= t.drawn->passes.size()) continue;
        const scene::ResidentTile& tile = *t.drawn;
        const scene::PassTile& mine = tile.passes[index];
        const auto* b = mine.cpu_as<BuildingTile>();
        if (b == nullptr || !mine.ranges[0]) continue;
        const std::uint32_t base = mine.ranges[0].offset / 16;
        const std::uint32_t colours = ((base + b->colours_at) * 4) + (slot * 2 * b->faces);
        // a tile's rows and colours once, though it draws for several tiles (a stand-in ancestor, its cells over each)
        if (std::ranges::find(drawn_handles_, tile.handle) == drawn_handles_.end()) {
            drawn_handles_.push_back(tile.handle);
            write_rows(tile, mine, *b);
            light(state.block.theme, slot, tile, mine, *b);
        }
        TileDraw d;
        shaders::BuildingPush push{};
        push.place = t.place;
        push.at = {0U, base + b->faces_at, colours, base + b->rows_at};
        // its sun bits' first texel in set 1's sun_bits and their fade (none yet: every face unlit); the edged list's
        // first primitive (a near cell's wall)
        static_assert(scene::no_sun == shaders::BUILDING_NO_BITS);
        push.sun = {t.sun_base, bits(t.sun_weight), base + b->prims_at, 0U};
        static_assert(sizeof push == sizeof d.push);
        std::memcpy(d.push.data(), &push, sizeof push);
        d.edged_at = base + b->prims_at;
        d.cells_at = base + b->cells_at;
        d.plain_at = base + b->prims_at + (b->edged * shaders::BUILDING_PRIM_VECTORS);
        d.runs_first = static_cast<std::uint32_t>(runs_.size());
        building_runs(t.cells, b->edged_first, runs_);
        d.edged_runs = static_cast<std::uint32_t>(runs_.size()) - d.runs_first;
        building_runs(t.cells, b->cells_first, runs_);
        d.cell_runs = static_cast<std::uint32_t>(runs_.size()) - d.runs_first - d.edged_runs;
        building_runs(t.cells, b->plain_first, runs_);
        d.plain_runs = static_cast<std::uint32_t>(runs_.size()) - d.runs_first - d.edged_runs - d.cell_runs;
        d.distance = t.distance;
        if (d.edged_runs + d.cell_runs + d.plain_runs > 0) draws_.push_back(d);
    }
    // the nearest tiles first, so early depth testing rejects what they hide in the tiles behind
    std::ranges::stable_sort(draws_, {}, &TileDraw::distance);
    // the tiles no longer drawn are forgotten once many are kept (their rows written again if they come back: the
    // same; their colours lit again)
    if (rows_written_.size() > 4096) {
        std::erase_if(rows_written_,
                      [&](std::uint32_t h) { return std::ranges::find(drawn_handles_, h) == drawn_handles_.end(); });
    }
    if (lit_.size() > 4096) {
        std::erase_if(
            lit_, [&](const auto& e) { return std::ranges::find(drawn_handles_, e.first) == drawn_handles_.end(); });
    }
}

gpu::Result<void> Buildings::prepare(const frame::PassContext& context) {
    release();
    frame_layout_ = context.layout.pipeline_layout();
    ATLAS_GPU_LET(module, frame::make_module(context, "building_draw", spirv::building_draw));
    draw_module_ = std::move(module);
    gpu::GraphicsDesc desc{
        .label = {.name = "walls", .module = "building_draw", .configuration = context.configuration},
        .module = *draw_module_,
        .vertex = "walls_vs",
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
    ATLAS_GPU_LET(walls, frame::make_pipeline(context, desc, frame::Overdraw::opaque));
    walls_ = std::move(walls);
    // the outlines' fans: 14 triangles over each primitive's 16 points
    if (!edged_index_) {
        ATLAS_GPU_LET(index,
                      context.device.memory().buffer(std::uint64_t{edged_chunk} * edged_indices * 2,
                                                     vk::BufferUsageFlagBits::eIndexBuffer, gpu::MemoryUse::upload));
        auto* out = reinterpret_cast<std::uint16_t*>(index.bytes().data()); // NOLINT: the mapped indexes
        std::size_t k = 0;
        for (std::uint32_t p = 0; p < edged_chunk; ++p) {
            const std::uint32_t v = p * shaders::BUILDING_EDGED_VERTICES;
            for (std::uint32_t t = 0; t + 2 < shaders::BUILDING_EDGED_VERTICES; ++t) {
                out[k++] = static_cast<std::uint16_t>(v);
                out[k++] = static_cast<std::uint16_t>(v + t + 1);
                out[k++] = static_cast<std::uint16_t>(v + t + 2);
            }
        }
        index.flush();
        edged_index_ = std::move(index);
    }
    return prepare_roofs(context);
}

void Buildings::release() noexcept {
    walls_.clear();
    roofs_.clear();
    draw_module_.clear();
    edged_index_ = {};
    plain_index_ = {};
    // the heaps' colours are the frames' to light again
    lit_.clear();
}

void Buildings::record(const frame::Recording& recording, std::uint32_t phase) {
    if (phase != draw_phase) return;
    record_walls(recording);
    record_roofs(recording);
}

void Buildings::record_walls(const frame::Recording& recording) {
    if (draws_.empty()) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *walls_);
    cmd.bindIndexBuffer(edged_index_.handle(), 0, vk::IndexType::eUint16);
    // the walls (and the rest of the edged list), then the near cells cut from their walls
    for (const bool cells : {false, true}) {
        for (TileDraw& d : draws_) {
            const std::uint32_t runs = cells ? d.cell_runs : d.edged_runs;
            if (runs == 0) continue;
            const std::uint32_t at = cells ? d.cells_at : d.edged_at;
            const std::uint32_t mode = cells ? 1U : 0U;
            std::memcpy(d.push.data() + 32, &at, sizeof at);     // BuildingPush.at.x
            std::memcpy(d.push.data() + 60, &mode, sizeof mode); // BuildingPush.sun.w
            cmd.pushConstants(
                frame_layout_, frame::push_stages, 0,
                vk::ArrayProxy<const std::byte>(static_cast<std::uint32_t>(d.push.size()), d.push.data()));
            const std::uint32_t first_run = d.runs_first + (cells ? d.edged_runs : 0);
            for (std::uint32_t r = 0; r < runs; ++r) {
                const Run& run = runs_[first_run + r];
                for (std::uint32_t first = run.first; first < run.end; first += edged_chunk) {
                    const std::uint32_t n = std::min(edged_chunk, run.end - first);
                    cmd.drawIndexed(n * edged_indices, 1, 0,
                                    static_cast<std::int32_t>(first * shaders::BUILDING_EDGED_VERTICES), 0);
                    recording.counters.draw(cells ? "near cells" : "walls",
                                            std::uint64_t{n} * (shaders::BUILDING_EDGED_VERTICES - 2));
                }
            }
        }
    }
}

void Buildings::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    if (phase != draw_phase || state.cover == nullptr || state.ranks == nullptr) return;
    const std::uint32_t index = pass_index_.load(std::memory_order_relaxed);
    scene::Heaps* heaps = heaps_.load(std::memory_order_relaxed);
    emitted_ = {};
    depths_.clear();
    if (index == no_index || heaps == nullptr) return;
    // the objects group: the port's `<g stroke-linejoin="round">`, with its `mat` filter where the moment has weather
    // (the renders' wx), under the frame's root (its clip to the frame). The frame will emit it for every objects pass
    // (K7); until then the pass's own, shaped the same.
    const bool wx = (state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U;
    const std::int32_t objects =
        out.group({.node = {.filter = wx ? out.filter("mat") : twin::none}, .parent = out.root()});
    for (const scene::CoverTile& t : state.cover->tiles()) {
        if (t.drawn == nullptr || index >= t.drawn->passes.size()) continue;
        const scene::PassTile& mine = t.drawn->passes[index];
        const auto* b = mine.cpu_as<BuildingTile>();
        if (b == nullptr || !mine.ranges[0]) continue;
        const scene::Heaps::Writer w = heaps->writer(mine.ranges[0]);
        const std::uint32_t first = first_item(*t.drawn, kind_);
        const std::span<const std::uint32_t> rows(t.drawn->rows);
        twin::SunTexels sun;
        if (t.sun_base != scene::no_sun && t.sun && t.sun->now && t.sun->before) {
            sun = {.now = t.sun->now->words, .before = t.sun->before->words, .weight = t.sun_weight, .has = true};
        }
        twin::BuildingTwin twin(state.block, state.ranks->table(), out, objects);
        twin.record_depths(&depths_);
        twin.tile(*b, rows.subspan(std::min<std::size_t>(first, rows.size())), w.bytes(), t.place, t.cells, sun);
        emitted_.elements += twin.elements();
        emitted_.sunlit += twin.sunlit();
    }
}

} // namespace atlas::passes
