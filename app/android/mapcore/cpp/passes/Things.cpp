#include "passes/Things.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "gen/spirv/tree/draw.hpp"
#include "gpu/Descriptors.hpp"
#include "scene/Cover.hpp"
#include "scene/Packs.hpp"
#include "scene/Residency.hpp"
#include "style/Light.hpp"
#include "twin/Twin.hpp"
#include "twin/tree/ThingTwin.hpp"
#include "twin/tree/TreeShader.hpp"

#include <algorithm>
#include <bit>
#include <cstring>

namespace atlas::passes {

namespace {

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }
[[nodiscard]] std::uint32_t f32_bits(double v) noexcept { return bits(static_cast<float>(v)); }

/// The jobs a row of workgroups covers (things_cs's TREE_JOB_ROW workgroups of 64).
constexpr std::uint32_t job_row = 65535;

/// The most records a frame decides: the jobs beyond are left out.
constexpr std::uint32_t max_records = 1U << 20U;

/// amsterdam.py's furniture_px: street furniture under this many pixels of focal length over its depth is left out.
constexpr double furniture_px = 1.6;

/// `b`, or a buffer for `use` of at least `bytes` with room to grow by half again where `b` is smaller; whether it was
/// made anew.
[[nodiscard]] gpu::Result<bool> fit(const gpu::Device& device, gpu::Buffer& b, vk::DeviceSize bytes,
                                    vk::BufferUsageFlags usage, gpu::MemoryUse use) {
    if (b && b.size() >= bytes) return false;
    ATLAS_GPU_LET(made, device.memory().buffer(std::max<vk::DeviceSize>(bytes + (bytes / 2), 1024), usage, use));
    b = std::move(made);
    return true;
}

/// A colour of the style (0xRRGGBB) as the shaders take it, 0xBBGGRR.
[[nodiscard]] std::uint32_t bgr(std::uint32_t rgb) noexcept {
    return ((rgb >> 16U) & 0xFFU) | (rgb & 0xFF00U) | ((rgb & 0xFFU) << 16U);
}

/// tohex of a colour's channels, packed 0xBBGGRR.
[[nodiscard]] std::uint32_t hex_bgr(shaders::double3 c) noexcept {
    const hlsl::uint3 h = shaders::tohex3(c);
    return h.x | (h.y << 8U) | (h.z << 16U);
}

} // namespace

std::vector<hlsl::uint4> thing_words(const geometry::TileThings& things, double origin_x, double origin_y,
                                     double origin_z) {
    std::vector<hlsl::uint4> out;
    out.reserve(things.things.size() * shaders::THING_VECTORS);
    for (const geometry::Thing& t : things.things) {
        out.push_back(hlsl::uint4{f32_bits(t.x - origin_x), f32_bits(t.gy - origin_y), f32_bits(t.z - origin_z),
                                  static_cast<std::uint32_t>(t.kind) | (t.posts << shaders::THING_JOB_POSTS_SHIFT)});
        out.push_back(hlsl::uint4{f32_bits(t.bx - origin_x), f32_bits(t.bz - origin_z), 0U, 0U});
    }
    return out;
}

ThingChoice thing_choice(geometry::ThingKind kind, std::uint32_t posts, double depth, double focal, bool night,
                         bool weather, bool wet) {
    ThingChoice c;
    const auto printed = [](double v) { return static_cast<float>(pymath::round(v, 2)); };
    if (kind == geometry::ThingKind::railing) {
        // railing (with weather): `if c[2] < 0.8 or c[2] > 220: continue` (its item from 0.8 m), its stroke-width
        // '%.2f' % max(0.4, 0.045 * s)
        if (!weather || depth > 220) return c;
        c.drawn = true;
        c.flags = static_cast<std::uint32_t>(kind) | (posts << shaders::THING_JOB_POSTS_SHIFT) |
                  (night ? shaders::THING_JOB_NIGHT : 0U);
        c.a = printed(pymath::max(0.4, 0.045 * focal / depth));
        return c;
    }
    // if k != 'tree' and cam.focal / c[2] < cfg.get('furniture_px', 1.6): continue
    if (focal / depth < furniture_px) return c;
    c.drawn = true;
    c.flags = static_cast<std::uint32_t>(kind) | (night ? shaders::THING_JOB_NIGHT : 0U);
    const double s = focal / depth;
    switch (kind) {
    case geometry::ThingKind::lamp:
        // draw_lamp: the mast '%.2f' % max(0.5, 0.13 * s); hw, hh = max(0.6, 0.2 * s), max(0.9, 0.5 * s); by day where
        // s > 1.2 the glint '%.2f' % max(0.3, 0.05 * s)
        c.a = printed(pymath::max(0.5, 0.13 * s));
        c.b = static_cast<float>(pymath::max(0.6, 0.2 * s));
        c.c = static_cast<float>(pymath::max(0.9, 0.5 * s));
        if (!night && s > 1.2) {
            c.flags |= shaders::THING_JOB_GLINT;
            c.d = printed(pymath::max(0.3, 0.05 * s));
        }
        // at night its halo, r '%.1f' % max(4, 2.6 * s)
        if (night) {
            c.flags |= shaders::THING_JOB_HALO;
            c.d = static_cast<float>(pymath::round(pymath::max(4.0, 2.6 * s), 1));
        }
        // on a wet night first its streak: cy p0 + 2.6 * s, rx '%.2f' % max(0.5, 0.5 * s), ry '%.1f' % max(2.5, 3.4 *
        // s)
        if (night && wet) {
            c.flags |= shaders::THING_JOB_STREAK;
            c.rx = printed(pymath::max(0.5, 0.5 * s));
            c.ry = static_cast<float>(pymath::round(pymath::max(2.5, 3.4 * s), 1));
            c.drop = static_cast<float>(2.6 * s);
        }
        break;
    case geometry::ThingKind::bollard:
        // draw_bollard: '%.2f' % max(0.7, 0.2 * s); by day where s > 1 its head at p1 - 0.03 * s, r '%.2f' % max(0.2,
        // 0.05 * s)
        c.a = printed(pymath::max(0.7, 0.2 * s));
        if (!night && s > 1) {
            c.flags |= shaders::THING_JOB_HEAD;
            c.b = static_cast<float>(0.03 * s);
            c.c = printed(pymath::max(0.2, 0.05 * s));
        }
        break;
    default: break;
    }
    return c;
}

std::array<std::uint32_t, 17> thing_tones(bool night, const tiles::ThingsStyle& style) {
    static_assert(shaders::THING_TONES == 17);
    std::array<std::uint32_t, 17> out{};
    const auto pick = [night](const tiles::DayNight& c) { return bgr(night ? c.night : c.day); };
    out[shaders::THING_TONE_MAST] = pick(style.mast);
    out[shaders::THING_TONE_LANTERN] = pick(style.lantern);
    out[shaders::THING_TONE_CAP] = pick(style.cap);
    out[shaders::THING_TONE_GLINT] = bgr(style.glint);
    // draw_box: c = hx(col) if not night else mulc(hx(col), 0.3), its top tohex(c), its leg tohex(mulc(c, 0.8))
    const auto box = [&](std::uint32_t at, tiles::StyleRgb col) {
        const double k = night ? 0.3 : 1.0;
        shaders::double3 c{.x = static_cast<double>((col >> 16U) & 0xFFU),
                           .y = static_cast<double>((col >> 8U) & 0xFFU),
                           .z = static_cast<double>(col & 0xFFU)};
        if (night) c = {.x = c.x * k, .y = c.y * k, .z = c.z * k};
        out[at] = hex_bgr(c);
        out[at + 1] = hex_bgr({.x = c.x * 0.8, .y = c.y * 0.8, .z = c.z * 0.8});
    };
    box(shaders::THING_TONE_BENCH, style.bench);
    box(shaders::THING_TONE_BACK, style.bench_back);
    box(shaders::THING_TONE_BASKET, style.basket);
    out[shaders::THING_TONE_BOLLARD] = pick(style.bollard);
    out[shaders::THING_TONE_HEAD] = bgr(style.bollard_head);
    out[shaders::THING_TONE_RACK] = pick(style.rack);
    out[shaders::THING_TONE_GLINT_OPACITY] = bits(style.glint_opacity);
    out[shaders::THING_TONE_HEAD_OPACITY] = bits(style.bollard_head_opacity);
    out[shaders::THING_TONE_RACK_WIDTH] = bits(style.rack_width);
    out[shaders::THING_TONE_RAILING] = pick(style.railing);
    return out;
}

std::uint32_t first_thing(const scene::ResidentTile& tile, std::uint16_t kind) noexcept {
    for (std::size_t i = 0; i < tile.items.size(); ++i) {
        if (tile.items[i].kind == kind) return static_cast<std::uint32_t>(i);
    }
    return static_cast<std::uint32_t>(tile.items.size());
}

gpu::Result<void> Things::prepare(const frame::PassContext& context) {
    release();
    // none in the owner mode: the likeness harness's owner maps draw no things yet
    if (context.owners) return {};
    device_ = &context.device;
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(module, frame::make_module(context, "tree_draw", spirv::tree_draw));
    module_ = std::move(module);
    using Stage = vk::ShaderStageFlagBits;
    const std::array bindings{
        gpu::Binding{.slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 1,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = Stage::eCompute | Stage::eVertex | Stage::eFragment}, // trees_vs reads a crown's reach
        gpu::Binding{.slot = 2, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 3, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 4, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 5, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        // the fills' list (ENGINE.md gain 18)
        gpu::Binding{.slot = 6, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, static_cast<std::uint32_t>(sets_.size())));
    pool_ = std::move(pool);
    for (vk::raii::DescriptorSet& set : sets_) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, pool_, set_layout_));
        set = std::move(made);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    const std::array<vk::DescriptorSetLayout, 3> sets{context.layout.frame_set_layout(),
                                                      context.layout.global_set_layout(), *set_layout_};
    ATLAS_GPU_LET(compute_layout, gpu::make_layout(d, sets, frame::push_bytes, Stage::eCompute));
    compute_layout_ = std::move(compute_layout);
    ATLAS_GPU_LET(decide,
                  frame::make_compute(context,
                                      {.name = "things", .module = "tree_draw", .configuration = context.configuration},
                                      *module_, "things_cs", *compute_layout_));
    decide_ = std::move(decide);
    // the parts: opaque, at their thing's plane's depth and leads, writing the depth and the objects bit (the trees'
    // records and stages)
    gpu::GraphicsDesc desc{.label = {.name = "things", .module = "tree_draw", .configuration = context.configuration},
                           .module = *module_,
                           .vertex = "trees_vs",
                           .fragment = "trees_fs",
                           .layout = *layout_,
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
    ATLAS_GPU_LET(draw, frame::make_pipeline(context, desc, frame::Overdraw::opaque));
    draw_ = std::move(draw);
    // the quads alone (ENGINE.md gain 18): the same tests and writes, a fragment stage that writes no mask and discards
    // nothing
    gpu::GraphicsDesc fill = desc;
    fill.label.name = "thing fills";
    fill.vertex = "trees_fills_vs";
    fill.fragment = "trees_fills_fs";
    ATLAS_GPU_LET(fill_pipeline, frame::make_pipeline(context, fill, frame::Overdraw::opaque));
    fill_ = std::move(fill_pipeline);
    // the glints and heads: translucent, in painter's order, the test on and writes off
    gpu::GraphicsDesc side{
        .label = {.name = "thing glints", .module = "tree_draw", .configuration = context.configuration},
        .module = *module_,
        .vertex = "tree_sides_vs",
        .fragment = "tree_sides_fs",
        .layout = *layout_,
        .target = context.pass.scene_target(),
        .depth_test = true,
        .depth_write = false,
        .depth_op = vk::CompareOp::eGreater};
    side.blends[0] = gpu::Blend::over();
    if (side.target.colour_count == 2) side.blends[1] = gpu::Blend::untouched();
    ATLAS_GPU_LET(side_pipeline, frame::make_pipeline(context, side, frame::Overdraw::translucent));
    side_ = std::move(side_pipeline);
    return {};
}

void Things::release() noexcept {
    side_.clear();
    fill_.clear();
    draw_.clear();
    decide_.clear();
    compute_layout_.clear();
    layout_.clear();
    for (vk::raii::DescriptorSet& s : sets_) s.clear();
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    jobs_ = {};
    tones_ = {};
    records_ = {};
    sides_ = {};
    fills_ = {};
    counts_ = {};
}

void Things::kinds(frame::Selectors& selectors) { kind_ = selectors.kind("thing"); }

void Things::select(const frame::FrameState& state, frame::Selectors& selectors) {
    // the map leaves its street furniture out: their items take no rank
    selectors.leave_out(kind_, state.camera.leaves_out(style::camera_part::furniture));
}

gpu::Result<void> Things::build(scene::TileBuild& b) const {
    const geometry::TileThings g = geometry::build_things({.tile = &b.tile(), .head = &b.head(), .kind = kind_});
    if (g.things.empty()) return {};
    const std::vector<hlsl::uint4> words = thing_words(g, b.origin().x, b.origin().y, b.origin().z);
    ATLAS_GPU_LET(
        range, b.allocate(scene::HeapKind::points, static_cast<std::uint32_t>(words.size() * sizeof(hlsl::uint4)), 16));
    b.heaps().write(range, std::as_bytes(std::span(words)));
    b.out().ranges[0] = range;
    auto layout = std::make_shared<ThingTile>();
    layout->things = static_cast<std::uint32_t>(g.things.size());
    layout->kinds.reserve(g.things.size());
    layout->ids.reserve(g.things.size());
    layout->posts.reserve(g.things.size());
    for (const geometry::Thing& t : g.things) {
        layout->kinds.push_back(t.kind);
        layout->ids.push_back(t.id);
        layout->posts.push_back(static_cast<std::uint8_t>(t.posts));
    }
    for (const scene::ItemSpec& item : g.items) b.add_item(item);
    const std::size_t bytes = sizeof(ThingTile) + (g.things.size() * (2 + sizeof(std::int64_t)));
    b.keep(std::shared_ptr<const ThingTile>(std::move(layout)), bytes);
    return {};
}

void Things::drawn(const frame::FrameState& state, std::vector<Drawn>& out) const {
    out.clear();
    if (state.cover == nullptr || state.ranks == nullptr) return;
    const std::uint32_t me = index();
    const std::span<const std::uint32_t> table = state.ranks->table();
    const std::span<const double> depth = state.ranks->depth();
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    for (std::size_t c = 0; c < tiles.size(); ++c) {
        const scene::CoverTile& t = tiles[c];
        if (t.drawn == nullptr || me >= t.drawn->passes.size()) continue;
        const scene::PassTile& mine = t.drawn->passes[me];
        const auto* things = mine.cpu_as<ThingTile>();
        if (things == nullptr || !mine.ranges[0]) continue;
        // a tile's things once, though it draws for several tiles (a stand-in ancestor, its cells over each)
        bool seen = false;
        for (std::size_t e = 0; e < c && !seen; ++e) seen = tiles[e].drawn == t.drawn;
        if (seen) continue;
        const std::uint32_t first = first_thing(*t.drawn, kind_);
        const std::span<const std::uint32_t> rows(t.drawn->rows);
        for (std::uint32_t i = 0; i < things->things && first + i < rows.size(); ++i) {
            const std::uint32_t row = rows[first + i];
            const std::uint32_t rank = row < table.size() ? table[row] : shaders::RANK_NONE;
            if (rank == shaders::RANK_NONE) continue;
            out.push_back({.cover = static_cast<std::uint32_t>(c), .thing = i, .rank = rank, .depth = depth[row]});
        }
    }
    // the nearest first (the latest slots), so early depth testing rejects what they hide
    std::ranges::stable_sort(out, std::ranges::greater{}, [](const Drawn& d) { return shaders::rank_slot(d.rank); });
}

void Things::update(const frame::FrameState& state) {
    counts_ = {};
    if (decide_ == nullptr || state.heaps == nullptr || !state.view ||
        state.camera.leaves_out(style::camera_part::furniture)) {
        return;
    }
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    drawn(state, drawn_);
    if (drawn_.empty()) return;
    const std::uint32_t me = index();
    const bool night = (state.block.theme.flags.x & shaders::THEME_NIGHT) != 0U;
    const bool weather = (state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U;
    const bool wet = (state.block.theme.flags.x & shaders::THEME_WET) != 0U;
    const bool halos = !state.camera.leaves_out(style::camera_part::lamp_halos);
    const bool streaks = !state.camera.leaves_out(style::camera_part::lamp_streaks);
    const double focal = state.view->camera().focal();
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    words_.clear();
    fill_words_.clear();
    std::uint32_t records = 0;
    std::uint32_t sides = 0;
    for (const Drawn& d : drawn_) {
        const scene::CoverTile& t = tiles[d.cover];
        const scene::PassTile& mine = t.drawn->passes[me];
        const ThingTile& things = *mine.cpu_as<ThingTile>();
        ThingChoice choice =
            thing_choice(things.kinds[d.thing], things.posts[d.thing], d.depth, focal, night, weather, wet);
        if (!choice.drawn) continue;
        // a lamp's halo and its wet streak, unless the map leaves them out (its records are counted by its flags)
        if (!halos) choice.flags &= ~shaders::THING_JOB_HALO;
        if (!streaks) choice.flags &= ~shaders::THING_JOB_STREAK;
        const std::uint32_t n = shaders::thing_record_count(choice.flags);
        if (records + n > max_records) continue;
        // its quads for the plain fills (ENGINE.md gain 18)
        for (std::uint32_t i = 0; i < shaders::thing_quad_count(choice.flags); ++i) {
            fill_words_.push_back(records + shaders::thing_quad_record(choice.flags, i));
        }
        const std::uint32_t side = shaders::thing_side_count(choice.flags);
        const std::uint32_t words[] = {bits(t.place.offset.x),
                                       bits(t.place.offset.y),
                                       bits(t.place.offset.z),
                                       bits(t.place.offset.w),
                                       bits(t.place.scale.x),
                                       bits(t.place.scale.y),
                                       bits(t.place.scale.z),
                                       bits(t.place.scale.w),
                                       (mine.ranges[0].offset / 16) + (d.thing * shaders::THING_VECTORS),
                                       choice.flags,
                                       shaders::rank_slot(d.rank),
                                       records,
                                       bits(choice.a),
                                       bits(choice.b),
                                       bits(choice.c),
                                       bits(choice.d),
                                       side > 0 ? sides : 0U,
                                       bits(choice.rx),
                                       bits(choice.ry),
                                       bits(choice.drop)};
        static_assert(std::size(words) == std::size_t{4} * shaders::THING_JOB_VECTORS);
        words_.insert(words_.end(), std::begin(words), std::end(words));
        records += n;
        sides += side;
    }
    // the translucent ones far to near (painter's order): the jobs went nearest first, and a job's own keep their order
    for (std::size_t at = 0; at < words_.size(); at += std::size_t{4} * shaders::THING_JOB_VECTORS) {
        const std::uint32_t side = shaders::thing_side_count(words_[at + 9]);
        if (side > 0) words_[at + 16] = sides - words_[at + 16] - side;
    }
    const auto jobs = static_cast<std::uint32_t>(words_.size() / (std::size_t{4} * shaders::THING_JOB_VECTORS));
    if (jobs == 0) return;
    const std::array<std::uint32_t, 17> tones = thing_tones(night, style->things);
    // into this frame slot's buffers (its previous frame has finished: its buffers and set are free)
    const std::uint32_t slot = state.slot % 2;
    using B = vk::BufferUsageFlagBits;
    const auto vectors = [](std::uint32_t n) {
        return vk::DeviceSize{std::max(n, 1U)} * shaders::TREE_RECORD_VECTORS * 16;
    };
    auto jobs_made =
        fit(*device_, jobs_[slot], words_.size() * sizeof(std::uint32_t), B::eStorageBuffer, gpu::MemoryUse::upload);
    auto tones_made = fit(*device_, tones_[slot], sizeof tones, B::eStorageBuffer, gpu::MemoryUse::upload);
    auto records_made = fit(*device_, records_[slot], vectors(records), B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto sides_made = fit(*device_, sides_[slot], vectors(sides), B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto fills_made = fit(*device_, fills_[slot], std::max<std::size_t>(fill_words_.size(), 1) * sizeof(std::uint32_t),
                          B::eStorageBuffer, gpu::MemoryUse::upload);
    if (!jobs_made || !tones_made || !records_made || !sides_made || !fills_made) {
        const gpu::Error& e = !jobs_made      ? jobs_made.error()
                              : !tones_made   ? tones_made.error()
                              : !records_made ? records_made.error()
                              : !sides_made   ? sides_made.error()
                                              : fills_made.error();
        log::error("vk things: {} jobs, {} records, {} translucent ones and {} fills: {}", jobs, records, sides,
                   fill_words_.size(), e.text());
        jobs_[slot] = {};
        tones_[slot] = {};
        records_[slot] = {};
        sides_[slot] = {};
        fills_[slot] = {};
        return;
    }
    if (*jobs_made || *tones_made || *records_made || *sides_made || *fills_made) {
        gpu::SetWriter(*sets_[slot])
            .buffer(0, vk::DescriptorType::eStorageBuffer, jobs_[slot].handle())
            .buffer(1, vk::DescriptorType::eStorageBuffer, tones_[slot].handle())
            .buffer(2, vk::DescriptorType::eStorageBuffer, records_[slot].handle())
            .buffer(3, vk::DescriptorType::eStorageBuffer, records_[slot].handle())
            .buffer(4, vk::DescriptorType::eStorageBuffer, sides_[slot].handle())
            .buffer(5, vk::DescriptorType::eStorageBuffer, sides_[slot].handle())
            .buffer(6, vk::DescriptorType::eStorageBuffer, fills_[slot].handle())
            .apply(device_->handle());
    }
    std::memcpy(jobs_[slot].bytes().data(), words_.data(), words_.size() * sizeof(std::uint32_t));
    jobs_[slot].flush();
    std::memcpy(tones_[slot].bytes().data(), tones.data(), sizeof tones);
    tones_[slot].flush();
    if (!fill_words_.empty()) {
        std::memcpy(fills_[slot].bytes().data(), fill_words_.data(), fill_words_.size() * sizeof(std::uint32_t));
        fills_[slot].flush();
    }
    slot_ = slot;
    counts_ = {
        .jobs = jobs, .records = records, .sides = sides, .fills = static_cast<std::uint32_t>(fill_words_.size())};
}

void Things::record(const frame::Recording& recording, std::uint32_t phase) {
    switch (phase) {
    case draw_phase: record_draw(recording); break;
    case decide_phase: record_decide(recording); break;
    case sides_phase: record_sides(recording); break;
    default: break;
    }
}

void Things::record_decide(const frame::Recording& recording) {
    if (decide_ == nullptr || counts_.jobs == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *decide_);
    const auto frame_sets = recording.layout.sets();
    const auto offsets = recording.layout.offsets(recording.frame.slot.slot);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *compute_layout_, 0, frame_sets, offsets);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *compute_layout_, 2, *sets_[slot_], {});
    const hlsl::uint4 push{counts_.jobs, 0U, 0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*compute_layout_, vk::ShaderStageFlagBits::eCompute, 0, push);
    const std::uint32_t groups = (counts_.jobs + 63) / 64;
    cmd.dispatch(std::min(groups, job_row), (groups + job_row - 1) / job_row, 1);
    recording.counters.dispatch("things");
    // the records, for the vertex stages
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    recording.frame.sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                            .src_access = A::eShaderWrite,
                                                            .dst_stage = S::eVertexShader,
                                                            .dst_access = A::eShaderRead});
}

void Things::record_draw(const frame::Recording& recording) {
    if (draw_ == nullptr || counts_.records == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_[slot_], {});
    // At true depth with float depth the quads below depth 1 (tree_fill: the lamps' caps and lanterns, the boxes' tops)
    // draw first, alone, through a pipeline that writes no mask and discards nothing, so a tiler keeps its early depth
    // test and hidden-surface removal for them, and the masts and legs behind them are rejected early (ENGINE.md gain
    // 18); then the rest. Of its own thing's records a quad passes only the legs drawn before it (a box's top its leg,
    // a bench's back's top the seat's leg too), each in a lower slot, whose lead keeps their depths apart; another
    // thing's record would tie it only with their planes' depths equal to the float's last bit (in a ratio of two
    // leads). On 24-bit depth, whose step drops the leads a few dozen metres out, and under the painter switch, one
    // draw as before.
    const bool fills = counts_.fills > 0 && (recording.state.block.camera.flags.x &
                                             (shaders::CAMERA_PAINTER | shaders::CAMERA_DEPTH24)) == 0U;
    const hlsl::uint4 push{static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples()), fills ? 1U : 0U,
                           0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, push);
    if (fills) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *fill_);
        cmd.draw(counts_.fills * shaders::TREE_RECORD_VERTICES, 1, 0, 0);
        recording.counters.draw("thing fills", std::uint64_t{counts_.fills} * 2);
    }
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *draw_);
    cmd.draw(counts_.records * shaders::TREE_RECORD_VERTICES, 1, 0, 0);
    recording.counters.draw("things", std::uint64_t{counts_.records} * 2);
}

void Things::record_sides(const frame::Recording& recording) {
    if (side_ == nullptr || counts_.sides == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_[slot_], {});
    const hlsl::uint4 push{static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples()), 0U, 0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, push);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *side_);
    cmd.draw(counts_.sides * shaders::TREE_RECORD_VERTICES, 1, 0, 0);
    recording.counters.draw("thing glints", std::uint64_t{counts_.sides} * 2);
}

void Things::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    if (phase != draw_phase) return;
    emit_records(state, out, nullptr, nullptr);
}

void Things::emit_records(const frame::FrameState& state, twin::Stream& out, std::vector<shaders::TreeRecord>* records,
                          std::vector<shaders::TreeRecord>* sides) const {
    if (state.cover == nullptr || state.ranks == nullptr || state.heaps == nullptr || !state.view) return;
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    std::vector<Drawn> things;
    drawn(state, things);
    if (things.empty()) return;
    out.layer(twin::Layer::objects); // the port's objects image (`o`)
    twin::ThingTwin twin(state, *style, out);
    twin.keep_records(records, sides);
    const std::uint32_t me = index();
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    for (const Drawn& d : things) {
        const scene::CoverTile& t = tiles[d.cover];
        const scene::PassTile& mine = t.drawn->passes[me];
        twin.thing(t, *mine.cpu_as<ThingTile>(), state.heaps->writer(mine.ranges[0]).bytes(), d.thing, d.rank, d.depth);
    }
}

} // namespace atlas::passes
