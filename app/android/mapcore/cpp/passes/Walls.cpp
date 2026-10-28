// The buildings pass (passes/Walls.hpp): stage T into the walls heap, stage F's rows and draw lists, the walls'
// pipeline and draws, and the twin. The roofs' pipeline, draws and thin edges are passes/Roofs.cpp; the faces' light on
// the CPU, each tile's areas of colours and the light ahead, passes/Light.cpp.
#include "passes/Walls.hpp"

#include "base/assert.hpp"
#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "gen/spirv/building/draw.hpp"
#include "scene/Cover.hpp"
#include "scene/Residency.hpp"
#include "tiles/Style.hpp"
#include "twin/Twin.hpp"
#include "twin/building/BuildingShader.hpp"
#include "twin/building/BuildingTwin.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <span>

namespace atlas::passes {

namespace {

using Words = std::array<std::uint32_t, 4>;

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

/// Whether rank table entry `rank` draws its building's near variant (the cells' reach: building.slangh's
/// BUILDING_DETAIL_NEAR, which building_shape tests).
[[nodiscard]] bool near_rank(std::uint32_t rank) noexcept {
    return rank != shaders::RANK_NONE && (shaders::rank_detail(rank) & shaders::BUILDING_DETAIL_NEAR) != 0U;
}

/// The cells of `cells` whose near cells can draw this frame: those with an item (BuildingTile::near_items) whose rank
/// entry, through the tile's item rows `rows` and the frame's rank table `ranks`, draws the near variant. An item the
/// frame cannot look up counts as near, so its cell's near cells are drawn as before.
[[nodiscard]] std::uint64_t near_cells(const BuildingTile& b, std::uint64_t cells, std::span<const std::uint32_t> rows,
                                       std::span<const std::uint32_t> ranks) noexcept {
    std::uint64_t out = 0;
    const std::size_t n = !b.near_items_first.empty() ? b.near_items_first.size() - 1 : 0;
    for (std::size_t c = 0; c < n && c < 64; ++c) {
        if (((cells >> c) & 1U) == 0) continue;
        for (std::uint32_t i = b.near_items_first[c]; i < b.near_items_first[c + 1]; ++i) {
            const std::uint16_t item = b.near_items[i];
            if (item >= rows.size() || rows[item] >= ranks.size() || near_rank(ranks[rows[item]])) {
                out |= std::uint64_t{1} << c;
                break;
            }
        }
    }
    return out;
}

} // namespace

void Buildings::kinds(frame::Selectors& selectors) { kind_ = selectors.kind("building"); }

void Buildings::select(const frame::FrameState& state, frame::Selectors& selectors) {
    // _draw_building: a wall's ncol × nrow cells where d < 460 (band 0, its slots), one cell beyond
    selectors.set(kind_, geometry::cells_reach);
    // the detail, in building.slangh's BUILDING_DETAIL_ order: the cells' near variant; the windows where d < the
    // view's win_d (cfg's, 390 m where it names none); a church's own windows where d < 520 (with weather: no others
    // then); deep windows where d < 160, sash bars where d < 140; the facades' flat detail where d < 760, the low
    // plinth where d < 300, cornices where d < 520; the material faces where d < win_d * 1.35 (in double, as the port
    // multiplies); an old gable's hoist beam where d < 260
    const double win_d =
        state.placed ? state.placed->number("win_d").value_or(geometry::windows_reach) : geometry::windows_reach;
    const std::array<double, 10> details{geometry::cells_reach,
                                         win_d,
                                         geometry::church_windows_reach,
                                         geometry::deep_windows_reach,
                                         geometry::sash_reach,
                                         geometry::facades_reach,
                                         geometry::low_plinth_reach,
                                         geometry::cornices_reach,
                                         win_d * 1.35,
                                         geometry::beams_reach};
    selectors.set_details(kind_, details);
}

gpu::Result<void> Buildings::build(scene::TileBuild& b) const {
    const tiles::Style& style = b.style();
    const geometry::TileBuildings g = geometry::build_buildings(
        {.tile = &b.tile(),
         .head = &b.head(),
         .level = b.level(),
         .region = {.water_y = style.water_y,
                    .hull = style.hull,
                    .deck = style.deck,
                    .paris = style.paris,
                    .kit_eaves = style.kit ? std::optional<double>(style.kit->eaves) : std::nullopt},
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
        layout->ids.push_back(item.outline);
        layout->owners.push_back(static_cast<std::uint8_t>((item.outline_relation ? 2U : 0U) |
                                                           (item.source == geometry::Source::part ? 1U : 0U)));
    }
    const std::size_t bytes =
        sizeof(BuildingTile) +
        ((layout->edged_first.size() + layout->cells_first.size() + layout->plain_first.size() +
          layout->windows_first.size() + layout->window_at.size() + layout->near_items_first.size()) *
         4) +
        (layout->near_items.size() * 2) + (layout->ids.size() * 9) + (layout->lights.size() * sizeof(BuildingLight)) +
        (layout->face_items.size() * 2) + layout->face_walls.size() + (layout->rings.size() * sizeof(BuildingRing)) +
        (layout->ring_points.size() * sizeof(float));
    b.keep(std::shared_ptr<const BuildingTile>(std::move(layout)), bytes);
    return {};
}

std::uint32_t first_item(const scene::ResidentTile& tile, std::uint16_t kind) noexcept {
    for (std::size_t i = 0; i < tile.items.size(); ++i) {
        if (tile.items[i].kind == kind) return static_cast<std::uint32_t>(i);
    }
    return static_cast<std::uint32_t>(tile.items.size());
}

void Buildings::write_rows(scene::Heaps& heaps, const scene::ResidentTile& tile, const scene::PassTile& mine,
                           const BuildingTile& b) {
    if (rows_written_.contains(tile.handle)) return;
    const std::uint32_t first = first_item(tile, kind_);
    std::vector<std::uint32_t> rows(std::size_t{(b.items + 3) / 4} * 4, 0);
    for (std::uint32_t k = 0; k < b.items && first + k < tile.rows.size(); ++k) rows[k] = tile.rows[first + k];
    heaps.write(mine.ranges[0], std::as_bytes(std::span(rows)), b.rows_at * 16);
    heaps.flush();
    rows_written_.insert(tile.handle);
}

void Buildings::update(const frame::FrameState& state) {
    draws_.clear();
    runs_.clear();
    drawn_handles_.clear();
    stroke_counts_ = {};
    window_counts_ = {};
    if (strokes_ != nullptr) {
        strokes_->jobs_now = 0;
        strokes_->records_now = 0;
    }
    if (windows_ != nullptr) {
        windows_->jobs_now = 0;
        windows_->walls_now = 0;
        windows_->records_now = 0;
        windows_->nears_now = 0;
        windows_->facades_now = 0;
    }
    if (kept_ != nullptr) kept_->jobs_now = 0;
    // the light ahead of a moment no longer asked about let go of
    forget_ahead();
    if (state.cover == nullptr || state.ranks == nullptr || state.heaps == nullptr) return;
    // the tiles' PassTile of this pass: the one at its place among the passes that built them (FrameState::cover)
    const std::uint32_t me = index();
    // a new moment (the frame block's theme): every tile's colours again as it is drawn, once, into the area of them no
    // frame in flight reads; those the light ahead lit for it (ready_for) copied in
    const std::span<const std::byte> theme = std::as_bytes(std::span(&state.block.theme, 1));
    if (!std::ranges::equal(theme, theme_) || moment_ == 0) {
        std::ranges::copy(theme, theme_.begin());
        ++moment_;
        take_ahead(state.block.theme);
    }
    // the building asked about changed: the tiles that hold it (now, or before) light again
    if (state.asked != asked_) {
        asked_ = state.asked;
        ++asked_revision_;
    }
    const std::uint32_t slot = state.slot % 2;
    // The tiles with a building nearer than the cells' reach (its rank draws the near variant): only theirs can draw
    // their near cells. The ranks' order is far to near, so those buildings are among its last rows.
    near_tiles_.clear();
    if (state.items != nullptr) {
        const std::span<const std::uint32_t> order = state.ranks->order();
        const std::span<const double> depth = state.ranks->depth();
        const std::span<const std::uint32_t> table = state.ranks->table();
        const std::span<const std::uint16_t> kinds = state.items->kind();
        const std::span<const std::uint32_t> tiles = state.items->tile();
        for (std::size_t k = order.size(); k > 0; --k) {
            const std::uint32_t row = order[k - 1];
            if (depth[row] >= geometry::cells_reach) break; // so is every row before it: no near variant there
            if (kinds[row] != kind_ || !near_rank(table[row])) continue;
            if (std::ranges::find(near_tiles_, tiles[row]) == near_tiles_.end()) near_tiles_.push_back(tiles[row]);
        }
    }
    bool lit = false;
    for (const scene::CoverTile& t : state.cover->tiles()) {
        if (t.drawn == nullptr || me >= t.drawn->passes.size()) continue;
        const scene::ResidentTile& tile = *t.drawn;
        const scene::PassTile& mine = tile.passes[me];
        const auto* b = mine.cpu_as<BuildingTile>();
        if (b == nullptr || !mine.ranges[0]) continue;
        const std::uint32_t base = mine.ranges[0].offset / 16;
        // a tile's rows and colours once, though it draws for several tiles (a stand-in ancestor, its cells over each)
        std::uint32_t area = 0;
        if (std::ranges::find(drawn_handles_, tile.handle) == drawn_handles_.end()) {
            drawn_handles_.push_back(tile.handle);
            write_rows(*state.heaps, tile, mine, *b);
            area = light(*state.heaps, state.block.theme, slot, tile, state.asked, mine, *b, lit);
        } else {
            area = lit_[tile.handle].read[slot];
        }
        const std::uint32_t colours = ((base + b->colours_at) * 4) + (area * 2 * b->faces);
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
        d.windows_at = base + b->windows_at;
        d.windows_from = t.sun && t.sun->now ? t.sun->now->windows_from : 0U;
        d.tile = b;
        if (const std::uint32_t first = first_item(tile, kind_); first < tile.rows.size()) {
            d.rows = std::span(tile.rows).subspan(first, std::min<std::size_t>(b->items, tile.rows.size() - first));
        }
        d.reveals = ((base + b->reveals_at) * 4) + (area * b->window_walls);
        d.facades = base + b->facades_at;
        d.runs_first = static_cast<std::uint32_t>(runs_.size());
        building_runs(t.cells, b->edged_first, runs_);
        d.edged_runs = static_cast<std::uint32_t>(runs_.size()) - d.runs_first;
        // its near cells only in the cells where one of their buildings is near: elsewhere they draw nothing
        const bool near_tile = std::ranges::find(near_tiles_, tile.handle) != near_tiles_.end();
        building_runs(near_tile ? near_cells(*b, t.cells, d.rows, state.ranks->table()) : 0U, b->cells_first, runs_);
        d.cell_runs = static_cast<std::uint32_t>(runs_.size()) - d.runs_first - d.edged_runs;
        building_runs(t.cells, b->plain_first, runs_);
        d.plain_runs = static_cast<std::uint32_t>(runs_.size()) - d.runs_first - d.edged_runs - d.cell_runs;
        building_runs(t.cells, b->windows_first, runs_);
        d.window_runs =
            static_cast<std::uint32_t>(runs_.size()) - d.runs_first - d.edged_runs - d.cell_runs - d.plain_runs;
        d.distance = t.distance;
        if (d.edged_runs + d.cell_runs + d.plain_runs + d.window_runs > 0) draws_.push_back(d);
    }
    // the colours written this frame reach the GPU, once for every tile; the tiles lit ahead, copied in, are let go of
    if (lit) state.heaps->flush();
    drop_ahead();
    // the nearest tiles first, so early depth testing rejects what they hide in the tiles behind
    std::ranges::stable_sort(draws_, {}, &TileDraw::distance);
    if (strokes_ != nullptr) write_jobs(slot);
    if (windows_ != nullptr) write_window_jobs(slot, state);
    if (kept_ != nullptr) write_kept_jobs(slot);
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

gpu::GraphicsDesc building_desc(const frame::PassContext& context, vk::ShaderModule module, std::string_view name,
                                const char* vertex, vk::PipelineLayout layout) {
    gpu::GraphicsDesc desc{.label = {.name = name, .module = "building_draw", .configuration = context.configuration},
                           .module = module,
                           .vertex = vertex,
                           .fragment = "building_fs",
                           .layout = layout,
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
    return desc;
}

gpu::Result<void> Buildings::prepare(const frame::PassContext& context) {
    release();
    frame_layout_ = context.layout.pipeline_layout();
    ATLAS_GPU_LET(module, frame::make_module(context, "building_draw", spirv::building_draw));
    draw_module_ = std::move(module);
    ATLAS_GPU_LET(walls, frame::make_pipeline(context,
                                              building_desc(context, *draw_module_, "walls", "walls_vs", frame_layout_),
                                              frame::Overdraw::opaque));
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
    ATLAS_GPU_CHECK(prepare_roofs(context));
    // the windows, but in the owner mode; where the device cannot make them, the walls are drawn without them
    if (!context.owners) {
        if (auto made = prepare_windows(context); !made) {
            log::warn("vk buildings: the windows: {}; the walls are drawn without them", made.error().text());
            windows_.reset();
        }
    }
    // the kept walls, near cells and roofs; where the device cannot make them, they are drawn as their runs
    if (auto made = prepare_kept(context); !made) {
        log::warn("vk buildings: the kept primitives: {}; the walls and roofs are drawn as their runs",
                  made.error().text());
        kept_.reset();
    }
    return {};
}

void Buildings::release() noexcept {
    strokes_.reset();
    windows_.reset();
    kept_.reset();
    walls_.clear();
    roofs_.clear();
    draw_module_.clear();
    edged_index_ = {};
    plain_index_ = {};
    // the heaps' colours are the frames' to light again
    lit_.clear();
}

void Buildings::record(const frame::Recording& recording, std::uint32_t phase) {
    switch (phase) {
    case draw_phase:
        // the windows before their walls: nearer on the wall's plane, they hide what early depth testing then rejects
        // of the walls (under the painter switch their slots' depths lie past the walls' alike)
        record_windows(recording);
        record_walls(recording);
        record_roofs(recording);
        break;
    case decide_phase:
        record_kept_cull(recording);
        record_decide(recording);
        record_window_decide(recording);
        break;
    case strokes_phase: record_strokes(recording); break;
    case materials_phase: record_materials(recording); break;
    case roof_lines_phase: record_roof_lines(recording); break;
    default: break;
    }
}

void Buildings::record_walls(const frame::Recording& recording) {
    if (draws_.empty()) return;
    if (drawn_kept()) {
        // the walls, then the near cells, those the cull kept (passes/Kept.cpp)
        record_kept(recording, 0);
        record_kept(recording, 1);
        return;
    }
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
    if (phase != draw_phase) return;
    emitted_ = {};
    depths_.clear();
    if (state.cover == nullptr || state.ranks == nullptr || state.heaps == nullptr) return;
    const std::uint32_t me = index();
    scene::Heaps& heaps = *state.heaps;
    // the objects group: the port's `<g stroke-linejoin="round">`, with its `mat` filter where the moment has weather
    // (the renders' wx), under the frame's root (its clip to the frame). The frame will emit it for every objects pass
    // (K7); until then the pass's own, shaped the same.
    const bool wx = (state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U;
    const std::int32_t objects =
        out.group({.node = {.filter = wx ? out.filter("mat") : twin::none}, .parent = out.root()});
    // the glass's gradients, by day with weather (amsterdam.py's defs: `glass` by the sky's cover, then `glassLit`)
    twin::WindowGradients gradients;
    if (wx && (state.block.theme.flags.x & shaders::THEME_NIGHT) == 0U) {
        const shaders::FrameWindows& w = state.block.windows;
        const bool overcast = (state.block.theme.flags.x & shaders::THEME_OVERCAST) != 0U;
        const std::array<float, 2> offsets{0.0F, 1.0F};
        const std::array<std::uint32_t, 2> glass{0xFF00'0000U | (overcast ? w.glass.z : w.glass.x),
                                                 0xFF00'0000U | (overcast ? w.glass.w : w.glass.y)};
        const std::array<std::uint32_t, 2> lit{0xFF00'0000U | w.lit.x, 0xFF00'0000U | w.lit.y};
        gradients.glass = out.gradient(
            {.id = "glass", .coords = {0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, .offsets = offsets, .colours = glass});
        gradients.lit = out.gradient(
            {.id = "glassLit", .coords = {0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, .offsets = offsets, .colours = lit});
    }
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    for (std::size_t i = 0; i < tiles.size(); ++i) {
        const scene::CoverTile& t = tiles[i];
        if (t.drawn == nullptr || me >= t.drawn->passes.size()) continue;
        const scene::PassTile& mine = t.drawn->passes[me];
        const auto* b = mine.cpu_as<BuildingTile>();
        if (b == nullptr || !mine.ranges[0]) continue;
        const scene::Heaps::Writer w = heaps.writer(mine.ranges[0]);
        const std::uint32_t first = first_item(*t.drawn, kind_);
        const std::span<const std::uint32_t> rows(t.drawn->rows);
        twin::SunTexels sun;
        if (t.sun_base != scene::no_sun && t.sun && t.sun->now && t.sun->before) {
            sun = {.now = t.sun->now->words,
                   .before = t.sun->before->words,
                   .weight = t.sun_weight,
                   .has = true,
                   .windows_from = t.sun->now->windows_from};
        }
        twin::BuildingTwin twin(
            state.block, state.ranks->table(),
            {.depth = state.ranks->depth(), .focal = state.view ? state.view->camera().focal() : 0.0}, gradients, out,
            objects);
        twin.record_depths(&depths_, static_cast<std::uint32_t>(i));
        twin.tile(*b, rows.subspan(std::min<std::size_t>(first, rows.size())), w.bytes(), t.place, t.cells, sun);
        emitted_.elements += twin.elements();
        emitted_.sunlit += twin.sunlit();
    }
}

} // namespace atlas::passes
