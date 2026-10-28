// The frame builder (frame/FrameBuilder.hpp), and the passes' shared helpers (frame/Pass.hpp).
#include "frame/FrameBuilder.hpp"

#include "base/assert.hpp"
#include "base/log.hpp"
#include "base/trace.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/GroundRuns.hpp"
#include "frame/MarkerBoxes.hpp"
#include "gen/spirv/frame/overdraw.hpp"
#include "gen/spirv/frame/owner.hpp"
#include "gpu/Instance.hpp"
#include "scene/Heaps.hpp"
#include "scene/ItemTable.hpp"
#include "scene/Packs.hpp"
#include "tiles/Style.hpp"
#include "twin/Twin.hpp"
#include "view/Snap.hpp"

#include <algorithm>
#include <atomic>
#include <bit>
#include <chrono>
#include <cstring>
#include <format>
#include <span>
#include <string_view>
#include <thread>

namespace atlas::frame {

namespace {

using clock = std::chrono::steady_clock;

[[nodiscard]] double ms_since(clock::time_point t) {
    return std::chrono::duration<double, std::milli>(clock::now() - t).count();
}

/// How many quarter turns a surface transform is (the pre-rotation the frame applies).
[[nodiscard]] std::uint32_t quarter_turns_of(vk::SurfaceTransformFlagBitsKHR transform) noexcept {
    using T = vk::SurfaceTransformFlagBitsKHR;
    switch (transform) {
    case T::eRotate90:
    case T::eHorizontalMirrorRotate90: return 1;
    case T::eRotate180:
    case T::eHorizontalMirrorRotate180: return 2;
    case T::eRotate270:
    case T::eHorizontalMirrorRotate270: return 3;
    default: return 0;
    }
}

/// The output's levels a channel: what the composite rounds its steps to.
[[nodiscard]] std::uint32_t levels_of(vk::Format format) noexcept {
    switch (format) {
    case vk::Format::eA2B10G10R10UnormPack32:
    case vk::Format::eA2R10G10B10UnormPack32: return 1023;
    case vk::Format::eR16G16B16A16Unorm: return 65535;
    default: return 255;
    }
}

/// The composite's steps in a mode: structural keeps the renders' gradients and patterns (the haze, the wash, the
/// rain, the vignette) and drops their filters (mat, the grain, the cloud shadows), as D16's structural mode does.
[[nodiscard]] std::uint32_t steps_of(Mode mode) noexcept {
    if (mode == Mode::full) {
        return shaders::COMPOSITE_MAT | shaders::COMPOSITE_CLOUD_SHADOWS | shaders::COMPOSITE_RAIN |
               shaders::COMPOSITE_HAZE | shaders::COMPOSITE_WASH | shaders::COMPOSITE_GRAIN |
               shaders::COMPOSITE_VIGNETTE;
    }
    return shaders::COMPOSITE_RAIN | shaders::COMPOSITE_HAZE | shaders::COMPOSITE_WASH | shaders::COMPOSITE_VIGNETTE;
}

/// A colour of the style as the shaders take it: sRGB 0 to 1 at 8-bit levels, opaque.
[[nodiscard]] hlsl::float4 unit(tiles::StyleRgb c) noexcept {
    const auto level = [c](unsigned shift) { return static_cast<float>((c >> shift) & 0xFFU) / 255.0F; };
    return {level(16U), level(8U), level(0U), 1.0F};
}

/// The buildings' thin strokes in `pack`'s style (none without a pack: nothing draws them).
[[nodiscard]] shaders::FrameStrokes strokes_of(const scene::ResidentPack* pack) noexcept {
    const tiles::Style* style = pack != nullptr && pack->source ? pack->source->pack.style() : nullptr;
    if (style == nullptr) return {};
    const tiles::EdgesStyle& e = style->edges;
    return {.roof_day = unit(e.roof.day),
            .roof_night = unit(e.roof.night),
            .cabin = unit(e.cabin),
            .widths = {e.roof_slope, e.roof_top, e.cabin_width, e.wall}};
}

/// The windows' colours and their frames' width in `pack`'s style (none without a pack: nothing draws them).
[[nodiscard]] shaders::FrameWindows windows_of(const scene::ResidentPack* pack) noexcept {
    const tiles::Style* style = pack != nullptr && pack->source ? pack->source->pack.style() : nullptr;
    if (style == nullptr) return {};
    const tiles::WindowsStyle& w = style->windows;
    return {.glass = {w.glass[0], w.glass[1], w.glass_overcast[0], w.glass_overcast[1]},
            .lit = {w.lit_glass[0], w.lit_glass[1], w.lit, std::bit_cast<std::uint32_t>(w.frame_width)},
            .dark = {w.dark.day, w.dark.night, w.warehouse.day, w.warehouse.night},
            .frame = {w.frame.day, w.frame.night, w.sill.day, w.sill.night},
            .shop = {w.shop_frame.day, w.shop_frame.night, std::bit_cast<std::uint32_t>(w.shop_frame_width), 0U},
            .facades = {w.plinth.day, w.plinth.night, w.cornice.day, w.cornice.night},
            .cabin = {w.cabin_frame.day, w.cabin_frame.night, w.cabin_glass_night,
                      std::bit_cast<std::uint32_t>(w.cabin_frame_width)}};
}

} // namespace

std::string_view name_of(Slot slot) noexcept {
    switch (slot) {
    case Slot::offscreen: return "offscreen";
    case Slot::objects: return "objects";
    case Slot::ground: return "ground";
    case Slot::translucent: return "translucent";
    case Slot::composite: return "composite";
    case Slot::labels: return "labels";
    case Slot::after: return "after";
    }
    return "?";
}

gpu::Result<vk::raii::ShaderModule> make_module(const PassContext& context, std::string_view name,
                                                std::span<const std::uint32_t> words) {
    const gpu::Announced named = gpu::announce_module(name, context.watch);
    return gpu::make_module(context.device.handle(), words);
}

gpu::Result<vk::raii::Pipeline> make_pipeline(const PassContext& context, gpu::GraphicsDesc desc, Overdraw overdraw) {
    bool twin = false;
    if (context.owners && overdraw != Overdraw::none && context.owner_module) {
        twin = true;
        // The owner twin: the same vertex stage, layout, target and tests; a fragment stage writing its painter slot,
        // blending off, so the latest primitive in painter's order covering a pixel leaves its slot there.
        desc.fragment_module = context.owner_module;
        desc.fragment = "owner_fs";
        desc.alpha_to_coverage = false;
        desc.blends[0] = {.enable = false,
                          .src_colour = vk::BlendFactor::eOne,
                          .dst_colour = vk::BlendFactor::eZero,
                          .src_alpha = vk::BlendFactor::eOne,
                          .dst_alpha = vk::BlendFactor::eZero,
                          .mask = vk::ColorComponentFlagBits::eR | vk::ColorComponentFlagBits::eG |
                                  vk::ColorComponentFlagBits::eB | vk::ColorComponentFlagBits::eA};
    } else if (context.overdraw && overdraw != Overdraw::none && context.overdraw_module) {
        twin = true;
        // The overdraw twin: the same vertex stage, layout, target and tests; a fragment stage adding 1/255 of red (an
        // opaque fragment) or green (a translucent one), onto red, green and blue only.
        desc.fragment_module = context.overdraw_module;
        desc.fragment = overdraw == Overdraw::opaque ? "overdraw_opaque_fs" : "overdraw_translucent_fs";
        desc.alpha_to_coverage = false;
        desc.blends[0] = {.enable = true,
                          .src_colour = vk::BlendFactor::eOne,
                          .dst_colour = vk::BlendFactor::eOne,
                          .src_alpha = vk::BlendFactor::eZero,
                          .dst_alpha = vk::BlendFactor::eOne,
                          .mask = vk::ColorComponentFlagBits::eR | vk::ColorComponentFlagBits::eG |
                                  vk::ColorComponentFlagBits::eB};
    }
    context.cache.note_made();
    const auto started = clock::now();
    auto made = [&] {
        const gpu::Announced named = gpu::announce(desc.label, context.watch);
        // A twin's fragment stage reads none of the vertex stage's outputs, by design: the validation layer's warning
        // about them is dropped while a twin is made, and then only (a pass's own pipeline leaving an output unread is
        // reported as ever).
        const gpu::QuietMessages quiet(twin ? std::span<const std::string_view>(gpu::unconsumed_outputs)
                                            : std::span<const std::string_view>());
        return gpu::make_graphics(context.device.handle(), context.cache.handle(), desc);
    }();
    if (context.stats != nullptr) {
        context.stats->ms += ms_since(started);
        ++context.stats->count;
    }
    return made;
}

gpu::Result<vk::raii::Pipeline> make_compute(const PassContext& context, const gpu::PipelineLabel& label,
                                             vk::ShaderModule code, const char* entry, vk::PipelineLayout layout) {
    context.cache.note_made();
    const auto started = clock::now();
    auto made = [&] {
        const gpu::Announced named = gpu::announce(label, context.watch);
        return gpu::make_compute(context.device.handle(), context.cache.handle(), label, code, entry, layout);
    }();
    if (context.stats != nullptr) {
        context.stats->ms += ms_since(started);
        ++context.stats->count;
    }
    return made;
}

FrameBuilder::FrameBuilder(const gpu::Device& device, const gpu::PipelineCache& cache, FrameOptions options,
                           gpu::PipelineWatch* watch) noexcept
    : device_(device), cache_(cache), options_(options), watch_(watch) {}

FrameBuilder::~FrameBuilder() { release(); }

gpu::Result<std::unique_ptr<FrameBuilder>> FrameBuilder::create(const gpu::Device& device,
                                                                const gpu::PipelineCache& cache,
                                                                std::vector<std::unique_ptr<Pass>> passes,
                                                                FrameOptions options, gpu::PipelineWatch* watch) {
    std::unique_ptr<FrameBuilder> self(
        new FrameBuilder(device, cache, options, watch)); // NOLINT(cppcoreguidelines-owning-memory)
    ATLAS_GPU_LET(layout, FrameLayout::create(device));
    self->layout_ = std::move(layout);
    self->passes_ = std::move(passes);
    // The passes' numbers (their PassTile and PassPack), and the item kinds, before the world can build a tile: their
    // rows are fixed from here on.
    for (std::uint32_t i = 0; i < self->passes_.size(); ++i) self->passes_[i]->index_ = i;
    // The trace sections' names (gain 21): each pass's prepare, select and update, each slot's recording.
    for (const std::unique_ptr<Pass>& p : self->passes_) {
        self->trace_names_.push_back({.prepare = std::format("atlas prepare {}", p->name()),
                                      .select = std::format("atlas select {}", p->name()),
                                      .update = std::format("atlas update {}", p->name())});
    }
    for (std::size_t s = 0; s < slot_count; ++s) {
        self->slot_trace_names_[s] = std::format("atlas record {}", name_of(static_cast<Slot>(s)));
    }
    for (const std::unique_ptr<Pass>& p : self->passes_) p->kinds(self->selectors_);
    // Every phase in the frame's order: by slot, then order, then the registry's (a stable sort).
    for (std::uint32_t p = 0; p < self->passes_.size(); ++p) {
        self->counters_.emplace_back(self->passes_[p]->name());
        for (const Phase& phase : self->passes_[p]->phases()) {
            self->steps_.push_back({.slot = phase.slot, .order = phase.order, .pass = p, .phase = phase.id});
        }
    }
    std::ranges::stable_sort(self->steps_, [](const Step& a, const Step& b) {
        return a.slot != b.slot ? a.slot < b.slot : a.order < b.order;
    });
    for (std::size_t s = 0; s < slot_count; ++s) {
        const auto first =
            std::ranges::find_if(self->steps_, [s](const Step& x) { return static_cast<std::size_t>(x.slot) >= s; });
        const auto last =
            std::ranges::find_if(self->steps_, [s](const Step& x) { return static_cast<std::size_t>(x.slot) > s; });
        self->slot_steps_[s] = {static_cast<std::uint32_t>(first - self->steps_.begin()),
                                static_cast<std::uint32_t>(last - self->steps_.begin())};
    }
    if (options.overdraw) {
        auto made = [&] {
            const gpu::Announced named = gpu::announce_module("frame_overdraw", watch);
            return gpu::make_module(device.handle(), spirv::frame_overdraw);
        }();
        ATLAS_GPU_LET(module, std::move(made));
        self->overdraw_module_ = std::move(module);
    }
    if (options.owners) {
        auto made = [&] {
            const gpu::Announced named = gpu::announce_module("frame_owner", watch);
            return gpu::make_module(device.handle(), spirv::frame_owner);
        }();
        ATLAS_GPU_LET(module, std::move(made));
        self->owner_module_ = std::move(module);
    }
    return self;
}

gpu::Result<void> FrameBuilder::prepare(const gpu::MainPass& pass, const gpu::Output& output) {
    const auto started = clock::now();
    release();
    if (!pass.composite()) {
        return gpu::fail("the map's frame is the composite form; the one-subpass form (ONEPASS) is not built (A0)");
    }
    quarter_turns_ = quarter_turns_of(output.transform());
    levels_ = levels_of(pass.spec().colour_format);
    depth24_ = pass.spec().depth_stencil == vk::Format::eD24UnormS8Uint;
    configuration_ =
        std::format("{} {}", gpu::name_of(pass.spec().config), gpu::colour_name(pass.spec().colour_format));
    // Every pass prepares on a thread of its own, the calling one among them, as many at once as there are threads
    // (ENGINE.md gain 20): a pass's prepare touches only its own state and what the context gives it (frame/Pass.hpp),
    // so the first picture waits for the longest pass instead of every pass's pipelines one after another. Each pass
    // counts its pipelines apart; the passes start in the registry's order.
    const std::size_t count = passes_.size();
    std::vector<PipelineStats> stats(count);
    std::vector<gpu::Result<void>> made(count);
    std::atomic<std::size_t> next{0};
    const auto work = [&] {
        for (;;) {
            const std::size_t p = next.fetch_add(1, std::memory_order_relaxed);
            if (p >= count) return;
            const trace::Section traced(trace_names_[p].prepare.c_str());
            const PassContext context{.device = device_,
                                      .cache = cache_,
                                      .watch = watch_,
                                      .pass = pass,
                                      .output = output,
                                      .layout = *layout_,
                                      .configuration = configuration_,
                                      .overdraw = options_.overdraw,
                                      .stats = &stats[p],
                                      .overdraw_module = *overdraw_module_,
                                      .owners = options_.owners,
                                      .owner_module = *owner_module_};
            made[p] = passes_[p]->prepare(context);
        }
    };
    const std::uint32_t cores = options_.threads != 0 ? options_.threads : std::thread::hardware_concurrency();
    const auto threads = static_cast<std::uint32_t>(std::clamp<std::size_t>(cores, 1, std::max<std::size_t>(count, 1)));
    {
        std::vector<std::jthread> helpers;
        helpers.reserve(threads - 1);
        for (std::uint32_t k = 1; k < threads; ++k) helpers.emplace_back(work);
        work();
    } // the helpers join here
    // The first pass that failed, in the registry's order, says why, whichever thread failed first.
    PipelineStats total;
    for (std::size_t p = 0; p < count; ++p) {
        if (!made[p]) {
            const gpu::Error error{.code = made[p].error().code,
                                   .what = std::format("the {} pass: {}", passes_[p]->name(), made[p].error().what)};
            release();
            return std::unexpected(error);
        }
        total.ms += stats[p].ms;
        total.count += stats[p].count;
    }
    pipelines_ms_ = total.ms;
    pipeline_count_ = total.count;
    prepare_ms_ = ms_since(started);
    prepare_threads_ = threads;
    prepared_ = true;
    // Not a `vk frame` line: smoke.sh counts those as frames drawn.
    log::info("vk passes prepared {}: {} pipelines in {:.1f} ms on {} threads ({:.1f} ms in the driver)",
              configuration_, pipeline_count_, prepare_ms_, threads, pipelines_ms_);
    return {};
}

void FrameBuilder::release() {
    for (const std::unique_ptr<Pass>& p : passes_) p->release();
    prepared_ = false;
}

Count FrameBuilder::total() const noexcept {
    Count c;
    for (const PassCounters& p : counters_) c += p.total();
    return c;
}

const style::ThemeBlock& FrameBuilder::take_moment(bool at_once) {
    const auto same = [](const style::ThemeBlock& a, const style::ThemeBlock& b) {
        return std::ranges::equal(std::as_bytes(std::span(&a, 1)), std::as_bytes(std::span(&b, 1)));
    };
    if (at_once || !shown_) {
        asked_.reset();
        return shown_.emplace(inputs_.theme);
    }
    if (same(*shown_, inputs_.theme)) {
        asked_.reset(); // back to the moment shown: one asked about meanwhile is not shown
        return *shown_;
    }
    // A new moment: every pass asked (each starts its work ahead for it), the frame showing the one before until every
    // pass is ready. The passes see the frame before (state_, not derived yet).
    const style::ThemeBlock& asked = asked_ ? *asked_ : asked_.emplace(inputs_.theme);
    const shaders::Theme next = style::shader_theme(asked);
    bool ready = true;
    for (const std::unique_ptr<Pass>& p : passes_) ready = p->ready_for(state_, next, wake_) && ready;
    if (!ready) return *shown_;
    shown_ = asked;
    asked_.reset();
    // the inputs moved on while it was lit: the next frame asks about theirs, at rest too
    if (!same(*shown_, inputs_.theme) && wake_) wake_();
    return *shown_;
}

bool FrameBuilder::derive(std::uint64_t number, std::uint32_t slot, const gpu::Output* output) {
    const style::CameraState& cam = inputs_.camera;
    if (cam.width_px == 0 || cam.height_px == 0 || cam.width_dp == 0 || cam.height_dp == 0) return false;
    // the moment: a frame drawn into an output waits for every pass's work ahead for a new one (Pass::ready_for); the
    // twin's, which draws nothing, takes the inputs' at once
    state_.theme = take_moment(output == nullptr);
    state_.number = number;
    state_.seconds = inputs_.seconds;
    state_.camera = cam;
    state_.mode = inputs_.mode;
    state_.rest = cam.has(style::camera_flag::rest);
    state_.slot = slot;
    state_.labels = inputs_.labels;
    state_.markers = inputs_.markers;
    state_.routes = inputs_.routes;
    state_.asked = inputs_.asked;
    const view::Device device{.density = cam.density,
                              .width_px = cam.width_px,
                              .height_px = cam.height_px,
                              .quarter_turns = output != nullptr ? quarter_turns_of(output->transform()) : 0};
    // The engine camera: through the active pack's ladder (ViewLadder.place's cfg), else the camera state alone.
    view::LayerSettings settings;
    view::CameraSpec spec;
    const view::Ladder* ladder = inputs_.scene != nullptr ? inputs_.scene->ladder(cam) : nullptr;
    if (ladder != nullptr) {
        state_.placed = ladder->place(view::live_view(cam));
        spec = state_.placed->camera();
        settings = view::settings_of(*state_.placed);
    } else {
        state_.placed.reset();
        spec = {.target = {},
                .yaw = view::bearing_of(cam.bearing),
                .pitch = view::engine_pitch(cam.pitch),
                .dist = cam.distance,
                .fov = view::field_of_view,
                .width = static_cast<double>(cam.width_dp),
                .height = static_cast<double>(cam.height_dp),
                .cx = std::nullopt,
                .cy = cam.principal_y,
                .near = view::near_plane};
        settings = {.width = spec.width,
                    .height = spec.height,
                    .pitch = spec.pitch,
                    .haze0 = view::haze0_default,
                    .haze1 = view::haze1_default,
                    .ground_y = 0,
                    .no_fin = false};
    }
    state_.view.emplace(spec, device);
    state_.layers = view::layers_of(state_.view->camera(), state_.theme, settings);
    const bool engine = cam.has(style::camera_flag::engine_mode);
    shaders::FrameBlock& b = state_.block;
    b.camera = state_.view->block(state_.rest, engine, number);
    // the depth attachment's scale, and the painter switch (the owner mode reads painter slots back from the depth)
    if (depth24_) b.camera.flags.x |= shaders::CAMERA_DEPTH24;
    if (options_.painter || options_.owners) b.camera.flags.x |= shaders::CAMERA_PAINTER;
    b.theme = style::shader_theme(state_.theme);
    std::uint32_t steps = options_.overdraw || options_.owners ? 0U : steps_of(state_.mode) & options_.steps;
    if (cam.has(style::camera_flag::no_cloud_shadows)) steps &= ~shaders::COMPOSITE_CLOUD_SHADOWS;
    if (options_.ground_clouds && !engine && (steps & shaders::COMPOSITE_CLOUD_SHADOWS) != 0U) {
        steps |= shaders::COMPOSITE_CLOUD_GROUND;
    }
    b.layers = view::block_of(*state_.layers, state_.theme, steps, levels_);
    std::uint32_t mode = 0;
    if (state_.mode == Mode::full) mode |= shaders::FRAME_FULL;
    if (engine) mode |= shaders::FRAME_ENGINE;
    if (state_.theme.has(style::theme_flag::night)) mode |= shaders::FRAME_NIGHT;
    if (!options_.grain) mode |= shaders::FRAME_NO_GRAIN;
    b.info = {mode, 0U, 0U, 0U};
    return true;
}

void FrameBuilder::write_ring(std::uint32_t slot) {
    std::span<std::byte> bytes = layout_->ring(slot);
    const RingSlice& s = layout_->slice();
    std::memcpy(bytes.data() + s.block, &state_.block, sizeof(state_.block));
    // An empty table's data() may be null, and memcpy from null is undefined even for zero bytes (UBSan, Core run
    // 39359743198): copy only what there is.
    if (state_.items != nullptr) {
        const auto entries = ranks_.table();
        if (!entries.empty()) std::memcpy(bytes.data() + s.ranks, entries.data(), entries.size_bytes());
    }
    const auto table = selectors_.table();
    if (!table.empty()) std::memcpy(bytes.data() + s.selectors, table.data(), table.size_bytes());
    layout_->flush(slot);
}

void FrameBuilder::record_slot(const gpu::FrameContext& frame, Slot slot) {
    const trace::Section traced(slot_trace_names_[static_cast<std::size_t>(slot)].c_str());
    const auto [first, last] = slot_steps_[static_cast<std::size_t>(slot)];
    for (std::uint32_t i = first; i < last; ++i) {
        const Step& step = steps_[i];
        const Recording r{.frame = frame, .state = state_, .layout = *layout_, .counters = counters_[step.pass]};
        passes_[step.pass]->record(r, step.phase);
    }
}

bool FrameBuilder::built_by(std::span<const std::string_view> names) {
    if (names.empty()) return true; // the scene does not say
    const bool same =
        std::ranges::equal(names, passes_, {}, {}, [](const std::unique_ptr<Pass>& p) { return p->name(); });
    if (!same && !other_passes_said_) {
        log::warn("vk frame: the scene's tiles were built by other passes than the frame's; it draws none of them");
        other_passes_said_ = true;
    }
    return same;
}

void FrameBuilder::stage_f() {
    // The world for this view, set 1's globals, the detail bands, the ranks, then every pass's own stage F.
    ATLAS_ASSERT(state_.view.has_value(), "derive made the frame's view");
    const view::View& view = state_.view.value();
    FrameScene* scene = inputs_.scene;
    if (scene != nullptr) {
        const trace::Section traced("atlas scene update");
        scene->update(state_);
    }
    // The scene's tiles and packs hold each pass's stage T at the place of the pass that built it: a builder of other
    // passes draws none of them.
    const bool ours = scene != nullptr && built_by(scene->pass_names());
    state_.cover = ours ? scene->cover() : nullptr;
    state_.items = ours ? scene->items() : nullptr;
    state_.heaps = ours ? scene->heaps() : nullptr;
    state_.ground_frame = scene != nullptr ? scene->ground_frame() : std::nullopt;
    state_.active_pack = ours ? scene->active_pack() : nullptr;
    state_.coarser_pack = ours ? scene->coarser_pack() : nullptr;
    // the strokes of the active pack's style (a coarser pack around it is made with the same style file)
    state_.block.strokes = strokes_of(state_.active_pack);
    state_.block.windows = windows_of(state_.active_pack);
    // Set 1's globals that no pass owns: each provider brings its bindings up to date (frame/Globals.hpp), and says
    // which effects its resources allow (FRAME_FIELDS: in full mode alone).
    if (scene != nullptr) {
        const trace::Section traced("atlas globals");
        std::uint32_t allowed = 0;
        for (GlobalProvider* g : scene->globals()) {
            Globals globals(*layout_, *g);
            g->update(state_, globals);
            allowed |= g->frame_flags(state_);
        }
        allowed &= shaders::FRAME_FIELDS;
        if ((state_.block.info.x & shaders::FRAME_FULL) == 0U) allowed &= ~shaders::FRAME_FIELDS;
        state_.block.info.x |= allowed;
    }
    for (std::size_t p = 0; p < passes_.size(); ++p) {
        const trace::Section traced(trace_names_[p].select.c_str());
        passes_[p]->select(state_, selectors_);
    }
    state_.selectors = &selectors_;
    // The ground's painter slots, every pass's in the registry's order from slot 0, then their tails over them (the
    // ground's shadows over the canal names); the items' come after them.
    std::uint32_t ground = 0;
    for (const std::unique_ptr<Pass>& p : passes_) {
        p->first_ground_slot_ = ground;
        ground += p->ground_slots(state_);
    }
    for (const std::unique_ptr<Pass>& p : passes_) {
        p->first_tail_slot_ = ground;
        ground += p->ground_tail_slots(state_);
    }
    state_.ground_slots = ground;
    if (state_.items != nullptr) {
        RankOptions options{.ground_slots = ground};
        if (state_.camera.has(style::camera_flag::engine_mode) && state_.placed) {
            if (const auto radius = state_.placed->number("radius")) {
                const view::Vec3 target = state_.placed->camera().target;
                options.radius_cut = true;
                options.radius = *radius;
                options.cut_x = target.x;
                options.cut_z = target.z;
            }
        }
        // the building asked about: its items, keyed as the buildings' (an area's owner)
        if (state_.asked) {
            options.asked = scene::owner_key(scene::owner_kind_area, static_cast<std::uint64_t>(state_.asked->id));
            options.hatched = state_.asked->hatched;
        }
        const trace::Section traced("atlas ranks");
        ranks_.update(*state_.items, view.camera(), selectors_, options);
        state_.ranks = &ranks_;
        state_.block.info.y = ranks_.slots();
        state_.block.info.z = static_cast<std::uint32_t>(state_.items->rows());
    } else {
        state_.ranks = nullptr;
        state_.block.info.y = ground;
        state_.block.info.z = 0;
    }
    if (!ground_runs_) ground_runs_ = std::make_unique<GroundRuns>();
    ground_runs_->runs.clear();
    state_.ground_runs = ground_runs_.get();
    if (!marker_boxes_) marker_boxes_ = std::make_unique<MarkerBoxes>();
    marker_boxes_->boxes.clear();
    state_.marker_boxes = marker_boxes_.get();
    for (std::size_t p = 0; p < passes_.size(); ++p) {
        const trace::Section traced(trace_names_[p].update.c_str());
        passes_[p]->update(state_);
    }
}

void FrameBuilder::record(const gpu::FrameContext& frame) {
    const auto started = clock::now();
    for (PassCounters& c : counters_) c.clear();
    const std::uint32_t slot = frame.slot.slot;
    if (!derive(frame.number, slot, &frame.output)) {
        // No frame size yet: the clear alone, so the frame still goes out (the marks in their order).
        frame.frames.mark(frame.slot, gpu::Mark::offscreen);
        frame.pass.begin(frame.cmd, frame.sync, frame.image, options_.clear);
        if (frame.pass.composite()) frame.pass.next(frame.cmd, frame.sync);
        frame.pass.end(frame.cmd, frame.sync, frame.image);
        frame.frames.mark(frame.slot, gpu::Mark::main);
        frame.frames.mark(frame.slot, gpu::Mark::after);
        return;
    }
    {
        const trace::Section traced("atlas stage F");
        stage_f();
    }
    FrameScene* scene = inputs_.scene;

    // The frame's slice of set 0's ring.
    const std::uint64_t finished = frame.number >= gpu::Frames::in_flight ? frame.number - gpu::Frames::in_flight : 0;
    const auto rows = state_.items != nullptr ? static_cast<std::uint32_t>(state_.items->rows()) : 0U;
    if (auto begun = layout_->begin_frame(frame.number, finished, rows, Selectors::max_kinds); !begun) {
        log::error("vk frame {}: the frame's descriptors: {}", frame.number, begun.error().text());
    }
    write_ring(slot);
    stage_f_ms_ = ms_since(started);

    const auto recording = clock::now();
    const vk::raii::CommandBuffer& cmd = frame.cmd;
    layout_->record_setup(cmd, frame.sync);
    if (scene != nullptr) {
        for (GlobalProvider* g : scene->globals()) g->record(frame);
    }
    record_slot(frame, Slot::offscreen);
    frame.frames.mark(frame.slot, gpu::Mark::offscreen);

    frame.pass.begin(cmd, frame.sync, frame.image, options_.clear);
    const vk::Extent2D e = frame.output.extent();
    cmd.setViewport(0, vk::Viewport{.x = 0,
                                    .y = 0,
                                    .width = static_cast<float>(e.width),
                                    .height = static_cast<float>(e.height),
                                    .minDepth = 0,
                                    .maxDepth = 1});
    cmd.setScissor(0, vk::Rect2D{.offset = {.x = 0, .y = 0}, .extent = e});
    const auto sets = layout_->sets();
    const auto offsets = layout_->offsets(slot);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, layout_->pipeline_layout(), 0, sets, offsets);
    record_slot(frame, Slot::objects);
    record_slot(frame, Slot::ground);
    record_slot(frame, Slot::translucent);
    frame.pass.next(cmd, frame.sync);
    record_slot(frame, Slot::composite);
    if (!options_.overdraw && !options_.owners) record_slot(frame, Slot::labels);
    frame.pass.end(cmd, frame.sync, frame.image);
    frame.frames.mark(frame.slot, gpu::Mark::main);
    if (!options_.overdraw && !options_.owners) record_slot(frame, Slot::after);
    frame.frames.mark(frame.slot, gpu::Mark::after);
    record_ms_ = ms_since(recording);
}

std::uint64_t FrameBuilder::owner_of(std::uint32_t slot) const noexcept {
    if (state_.ranks == nullptr || state_.items == nullptr || slot < state_.ground_slots) return 0;
    const std::span<const std::uint32_t> order = ranks_.order();
    const std::span<const std::uint32_t> first = ranks_.first_slot();
    // The last item in painter's order whose first slot is at or before `slot` (first slots ascend along the order).
    const auto it = std::ranges::upper_bound(order, slot, {}, [&](std::uint32_t row) { return first[row]; });
    if (it == order.begin()) return 0;
    const std::uint32_t row = *std::prev(it);
    const std::uint32_t count = state_.items->slots(row, ranks_.band()[row]);
    if (slot >= first[row] + count) return 0;
    return state_.items->owner()[row];
}

void FrameBuilder::owner_map(std::span<const std::uint8_t> rgba, std::span<std::uint64_t> out) const noexcept {
    const std::size_t n = std::min(rgba.size() / 4, out.size());
    for (std::size_t i = 0; i < n; ++i) {
        const std::uint32_t id = std::uint32_t{rgba[4 * i]} | (std::uint32_t{rgba[(4 * i) + 1]} << 8U) |
                                 (std::uint32_t{rgba[(4 * i) + 2]} << 16U);
        out[i] = id == 0 ? 0 : owner_of(id - 1);
    }
}

void FrameBuilder::emit(twin::Stream& out, std::uint64_t number) {
    out.clear();
    for (PassCounters& c : counters_) c.clear();
    if (!derive(number, 0, nullptr)) return;
    stage_f();
    // The frame's root group: the SVG's clip to the frame (`<g clip-path="url(#vb)">`), which every layer's elements
    // hang from; then every pass's phases in the frame's order.
    const float width = state_.block.camera.frame.x;
    const float height = state_.block.camera.frame.y;
    const std::int32_t vb = out.begin_clip("vb");
    out.rect({}, 0.0F, 0.0F, width, height);
    out.end_clip();
    const std::int32_t root = out.group({.node = {.clip = vb}, .parent = twin::none});
    out.set_root(root);
    // The groups the passes share, as the port's render writes them: the objects' `<g stroke-linejoin="round">` (the
    // linejoin is its children's style), filtered by `mat` when the moment has weather, and the ground's `mat` group
    // around the areas, the paving and the roads, then only.
    const bool weather = state_.theme.has(style::theme_flag::weather);
    const std::int32_t mat = weather ? out.filter("mat") : twin::none;
    out.set_objects(out.group({.node = {.filter = mat}, .parent = root}));
    out.set_ground(weather ? out.group({.node = {.filter = mat}, .parent = root}) : root);
    for (const Step& step : steps_) passes_[step.pass]->emit(state_, out, step.phase);
}

} // namespace atlas::frame
