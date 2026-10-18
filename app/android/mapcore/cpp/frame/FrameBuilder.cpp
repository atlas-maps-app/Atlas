// The frame builder (frame/FrameBuilder.hpp), and the passes' shared helpers (frame/Pass.hpp).
#include "frame/FrameBuilder.hpp"

#include "base/assert.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/spirv/frame/overdraw.hpp"
#include "scene/Heaps.hpp"
#include "scene/ItemTable.hpp"
#include "view/Snap.hpp"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <format>

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
    if (context.overdraw && overdraw != Overdraw::none && context.overdraw_module) {
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
        return gpu::make_graphics(context.device.handle(), context.cache.handle(), desc);
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
    // The item kinds, before the world can build a tile: their rows are fixed from here on.
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
    return self;
}

gpu::Result<void> FrameBuilder::prepare(const gpu::MainPass& pass, const gpu::Output& output) {
    release();
    if (!pass.composite()) {
        return gpu::fail("the map's frame is the composite form; the one-subpass form (ONEPASS) is not built (A0)");
    }
    quarter_turns_ = quarter_turns_of(output.transform());
    levels_ = levels_of(pass.spec().colour_format);
    configuration_ =
        std::format("{} {}", gpu::name_of(pass.spec().config), gpu::colour_name(pass.spec().colour_format));
    PipelineStats stats;
    const PassContext context{.device = device_,
                              .cache = cache_,
                              .watch = watch_,
                              .pass = pass,
                              .output = output,
                              .layout = *layout_,
                              .configuration = configuration_,
                              .overdraw = options_.overdraw,
                              .stats = &stats,
                              .overdraw_module = *overdraw_module_};
    for (const std::unique_ptr<Pass>& p : passes_) {
        if (auto made = p->prepare(context); !made) {
            const gpu::Error error{.code = made.error().code,
                                   .what = std::format("the {} pass: {}", p->name(), made.error().what)};
            release();
            return std::unexpected(error);
        }
    }
    pipelines_ms_ = stats.ms;
    pipeline_count_ = stats.count;
    prepared_ = true;
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

bool FrameBuilder::derive(std::uint64_t number, std::uint32_t slot, const gpu::Output* output) {
    const style::CameraState& cam = inputs_.camera;
    if (cam.width_px == 0 || cam.height_px == 0 || cam.width_dp == 0 || cam.height_dp == 0) return false;
    state_.number = number;
    state_.seconds = inputs_.seconds;
    state_.camera = cam;
    state_.theme = inputs_.theme;
    state_.mode = inputs_.mode;
    state_.rest = cam.has(style::camera_flag::rest);
    state_.slot = slot;
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
    b.theme = style::shader_theme(state_.theme);
    const std::uint32_t steps = options_.overdraw ? 0U : steps_of(state_.mode) & options_.steps;
    b.layers = view::block_of(*state_.layers, state_.theme, steps, levels_);
    std::uint32_t mode = 0;
    if (state_.mode == Mode::full) mode |= shaders::FRAME_FULL;
    if (engine) mode |= shaders::FRAME_ENGINE;
    if (state_.theme.has(style::theme_flag::night)) mode |= shaders::FRAME_NIGHT;
    b.info = {mode, 0U, 0U, 0U};
    return true;
}

void FrameBuilder::write_ring(std::uint32_t slot) {
    std::span<std::byte> bytes = layout_->ring(slot);
    const RingSlice& s = layout_->slice();
    std::memcpy(bytes.data() + s.block, &state_.block, sizeof(state_.block));
    if (state_.items != nullptr) {
        const auto entries = ranks_.table();
        std::memcpy(bytes.data() + s.ranks, entries.data(), entries.size_bytes());
    }
    const auto table = selectors_.table();
    std::memcpy(bytes.data() + s.selectors, table.data(), table.size_bytes());
    layout_->flush(slot);
}

void FrameBuilder::record_slot(const gpu::FrameContext& frame, Slot slot) {
    const auto [first, last] = slot_steps_[static_cast<std::size_t>(slot)];
    for (std::uint32_t i = first; i < last; ++i) {
        const Step& step = steps_[i];
        const Recording r{.frame = frame, .state = state_, .layout = *layout_, .counters = counters_[step.pass]};
        passes_[step.pass]->record(r, step.phase);
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
    // The world for this view, the detail bands, the ranks, then every pass's own stage F.
    ATLAS_ASSERT(state_.view.has_value(), "derive made the frame's view");
    const view::View& view = state_.view.value();
    FrameScene* scene = inputs_.scene;
    if (scene != nullptr) scene->update(state_);
    state_.cover = scene != nullptr ? scene->cover() : nullptr;
    state_.items = scene != nullptr ? scene->items() : nullptr;
    for (const std::unique_ptr<Pass>& p : passes_) p->select(state_, selectors_);
    state_.selectors = &selectors_;
    // The ground's painter slots, every pass's in the registry's order from slot 0; the items' come after them.
    std::uint32_t ground = 0;
    for (const std::unique_ptr<Pass>& p : passes_) {
        p->first_ground_slot_ = ground;
        ground += p->ground_slots(state_);
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
        ranks_.update(*state_.items, view.camera(), selectors_, options);
        state_.ranks = &ranks_;
        state_.block.info.y = ranks_.slots();
        state_.block.info.z = static_cast<std::uint32_t>(state_.items->rows());
    } else {
        state_.ranks = nullptr;
        state_.block.info.y = ground;
        state_.block.info.z = 0;
    }
    for (const std::unique_ptr<Pass>& p : passes_) p->update(state_);

    // Set 1's heaps where they grew; the frame's slice of set 0's ring.
    if (scene != nullptr && scene->heaps() != nullptr) {
        scene::Heaps& heaps = *scene->heaps();
        if (heaps.version() != heaps_version_) {
            for (std::size_t k = 0; k < scene::heap_kinds; ++k) {
                layout_->set_buffer(static_cast<Global>(k), heaps.buffer(static_cast<scene::HeapKind>(k)));
            }
            heaps_version_ = heaps.version();
        }
        heaps.bound(frame.number);
        heaps.collect(frame.number >= gpu::Frames::in_flight ? frame.number - gpu::Frames::in_flight : 0);
    }
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
    if (!options_.overdraw) record_slot(frame, Slot::labels);
    frame.pass.end(cmd, frame.sync, frame.image);
    frame.frames.mark(frame.slot, gpu::Mark::main);
    if (!options_.overdraw) record_slot(frame, Slot::after);
    frame.frames.mark(frame.slot, gpu::Mark::after);
    record_ms_ = ms_since(recording);
}

} // namespace atlas::frame
