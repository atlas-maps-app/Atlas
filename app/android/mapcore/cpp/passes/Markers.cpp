// The app's markers drawn by the core (passes/Markers.hpp).
#include "passes/Markers.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/MarkerBoxes.hpp"
#include "gen/spirv/overlay/marker.hpp"
#include "gen/spirv/overlay/marker_loc1.hpp"
#include "gpu/Descriptors.hpp"
#include "markers/Markers.hpp"

#include <algorithm>
#include <cstring>
#include <limits>

namespace atlas::passes {

namespace {

namespace mk = layouts::overlay_marker;

constexpr std::size_t draw_bytes = sizeof(mk::MarkerDraw);
constexpr std::size_t slot_bytes = Markers::max_draws * draw_bytes;
/// The draws' kinds (marker.slang's MARKER_SPRITE and MARKER_BUBBLE).
constexpr std::uint32_t sprite_kind = 0;
constexpr std::uint32_t bubble_kind = 1;
constexpr float no_point = std::numeric_limits<float>::quiet_NaN();

/// Kotlin's Float.roundToInt: Math.round of the value (halves up), Int's limits beyond them.
[[nodiscard]] std::int32_t round_px(float v) noexcept {
    const std::int64_t r = pymath::java::round(static_cast<double>(v));
    constexpr std::int64_t lo = std::numeric_limits<std::int32_t>::min();
    constexpr std::int64_t hi = std::numeric_limits<std::int32_t>::max();
    return static_cast<std::int32_t>(std::clamp(r, lo, hi));
}

/// The draw of `image` (its texels from `offset`) with its anchor on the view's pixel (x, y).
[[nodiscard]] mk::MarkerDraw sprite_draw(const markers::Image& image, std::uint32_t offset, std::int32_t x,
                                         std::int32_t y) noexcept {
    return {.target = {static_cast<float>(x - image.anchor_x), static_cast<float>(y - image.anchor_y),
                       static_cast<float>(image.width), static_cast<float>(image.height)},
            .source = {offset, image.width, image.height, sprite_kind}};
}

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

Markers::~Markers() { Markers::release(); }

void Markers::update(const frame::FrameState& state) {
    const std::uint32_t slot = state.slot & 1U;
    Slot& s = slots_.at(slot);
    s.count = 0;
    draws_.clear();
    const markers::MarkerSet* set = state.markers;
    if (set == nullptr || set->markers().empty() || !state.view.has_value() || !state.ground_frame.has_value()) {
        xs_.clear();
        ys_.clear();
        visible_.clear();
        layout_.place({}, xs_, ys_, visible_, {}, 0.0F, 0.0F);
        return;
    }
    // Each marker's place on the view's device pixels (OverlayPainter.layoutMarkers' GroundView: the street), placed
    // where it stands within 60 dp of the view.
    const view::View& view = *state.view;
    const view::GroundFrame& ground = *state.ground_frame;
    const markers::Metrics metrics{.density = set->density()};
    const auto width = static_cast<float>(state.camera.width_px);
    const auto height = static_cast<float>(state.camera.height_px);
    const float margin = markers::Layout::margin_dp * metrics.density;
    const auto all = set->markers();
    xs_.resize(all.size());
    ys_.resize(all.size());
    visible_.resize(all.size());
    for (std::size_t i = 0; i < all.size(); ++i) {
        const markers::Marker& m = all[i];
        const auto p = view.device_proj({ground.x(m.latitude, m.longitude), 0.0, ground.z(m.latitude, m.longitude)});
        if (!p) {
            xs_[i] = no_point;
            ys_[i] = no_point;
            visible_[i] = 0;
            continue;
        }
        const auto x = static_cast<float>(p->x);
        const auto y = static_cast<float>(p->y);
        xs_[i] = x;
        ys_[i] = y;
        visible_[i] = x >= -margin && x <= width + margin && y >= -margin && y <= height + margin ? 1 : 0;
    }
    layout_.place(all, xs_, ys_, visible_, metrics, width, height);
    const auto placed = layout_.placed();
    const auto clusters = layout_.clusters();
    // What the labels keep clear of: each placed marker's box, and a named pin's name beside it.
    if (state.marker_boxes != nullptr) {
        std::vector<labels::Box>& boxes = state.marker_boxes->boxes;
        for (std::size_t j = 0; j < placed.size(); ++j) {
            boxes.push_back(layout_.boxes()[j]);
            const std::uint32_t i = placed[j];
            const markers::Marker& m = all[i];
            if (m.style == markers::Style::pin && m.name >= 0.0F && clusters[j] == 0) {
                boxes.push_back(metrics.name_box(m, xs_[i], ys_[i], width));
            }
        }
    }
    // The draws, the lowest priority first: a marker as itself its look (a named pin's with its name on the side
    // MarkerPainter writes it); a cluster its bubble and its count. Each on the pixel its place lands on.
    for (std::size_t j = 0; j < placed.size(); ++j) {
        const std::uint32_t i = placed[j];
        const markers::Marker& m = all[i];
        const std::int32_t x = round_px(xs_[i]);
        const std::int32_t y = round_px(ys_[i]);
        if (clusters[j] > 0) {
            if (draws_.size() + 2 > max_draws) break;
            draws_.push_back({.target = {static_cast<float>(x), static_cast<float>(y),
                                         metrics.cluster_diameter(clusters[j]) / 2.0F, metrics.density},
                              .source = {m.colour, 0U, 0U, bubble_kind}});
            if (const auto c = set->count_of(m.ink, clusters[j])) {
                draws_.push_back(sprite_draw(set->counts()[*c].image, set->count_offsets()[*c], x, y));
            }
            continue;
        }
        const bool left = m.style == markers::Style::pin && m.name >= 0.0F && metrics.name_left(m, xs_[i], width);
        const std::uint32_t look = left ? m.look_left : m.look;
        if (look == markers::no_look || draws_.size() + 1 > max_draws) continue;
        draws_.push_back(sprite_draw(set->looks()[look], set->look_offsets()[look], x, y));
    }
    if (draws_.empty() || !draw_buffer_ || device_ == nullptr) return;
    // The slot's copy of the set's texels, made when it first draws this set (its previous frame has finished with
    // the copy it had).
    if (s.of != set || s.revision != set->revision() || !s.texels) {
        const auto texels = set->texels();
        auto made = fit(*device_, s.texels, std::max<vk::DeviceSize>(texels.size_bytes(), 4),
                        vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload);
        if (!made) {
            log::error("vk markers: their sprites' buffer ({} texels): {}", texels.size(), made.error().text());
            s.of = nullptr;
            return;
        }
        if (!texels.empty()) std::memcpy(s.texels.bytes().data(), texels.data(), texels.size_bytes());
        s.texels.flush();
        if (*made) {
            gpu::SetWriter(*s.set)
                .buffer(mk::marker_texels.binding, vk::DescriptorType::eStorageBuffer, s.texels.handle())
                .apply(device_->handle());
        }
        s.of = set;
        s.revision = set->revision();
    }
    std::memcpy(draw_buffer_.bytes().data() + (slot * slot_bytes), draws_.data(), draws_.size() * draw_bytes);
    draw_buffer_.flush();
    s.count = static_cast<std::uint32_t>(draws_.size());
}

gpu::Result<void> Markers::prepare(const frame::PassContext& context) {
    release();
    device_ = &context.device;
    const vk::raii::Device& d = context.device.handle();
    const gpu::PipelineTarget& target = context.pass.composite_target();
    // Where the composite writes the output beside the scene's colour (the dynamic route at 1x), so do the markers.
    const bool loc1 = target.colour_count == 2;
    const char* name = loc1 ? "overlay_marker_loc1" : "overlay_marker";
    ATLAS_GPU_LET(module, loc1 ? frame::make_module(context, name, spirv::overlay_marker_loc1)
                               : frame::make_module(context, name, spirv::overlay_marker));
    module_ = std::move(module);
    const std::array bindings{
        gpu::Binding{.slot = mk::marker_draws.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment},
        gpu::Binding{.slot = mk::marker_texels.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = vk::ShaderStageFlagBits::eFragment}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 2));
    pool_ = std::move(pool);
    for (Slot& s : slots_) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, pool_, set_layout_));
        s.set = std::move(made);
    }
    ATLAS_GPU_LET(draws, context.device.memory().buffer(2 * slot_bytes, vk::BufferUsageFlagBits::eStorageBuffer,
                                                        gpu::MemoryUse::upload));
    draw_buffer_ = std::move(draws);
    for (std::size_t s = 0; s < slots_.size(); ++s) {
        gpu::SetWriter(*slots_.at(s).set)
            .buffer(mk::marker_draws.binding, vk::DescriptorType::eStorageBuffer, draw_buffer_.handle(), s * slot_bytes,
                    slot_bytes)
            .apply(d);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_handle_ = std::move(layout);
    gpu::GraphicsDesc desc{.label = {.name = "markers", .module = name, .configuration = context.configuration},
                           .module = *module_,
                           .vertex = "marker_vs",
                           .fragment = "marker_fs",
                           .layout = *layout_handle_,
                           .target = target};
    if (loc1) {
        desc.blends[0] = gpu::Blend::untouched(); // the scene's colour, which the composite subpass only reads
        desc.blends[1] = gpu::Blend::over();
    } else {
        desc.blends[0] = gpu::Blend::over();
    }
    ATLAS_GPU_LET(pipeline, frame::make_pipeline(context, desc, frame::Overdraw::none));
    pipeline_ = std::move(pipeline);
    return {};
}

void Markers::release() noexcept {
    pipeline_.clear();
    layout_handle_.clear();
    for (Slot& s : slots_) {
        s.set.clear();
        s.texels = gpu::Buffer{};
        s.of = nullptr;
        s.revision = 0;
        s.count = 0;
    }
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    draw_buffer_ = gpu::Buffer{};
    device_ = nullptr;
}

void Markers::record(const frame::Recording& recording, std::uint32_t phase) {
    (void)phase;
    const Slot& s = slots_.at(recording.state.slot & 1U);
    if (!*pipeline_ || s.count == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_handle_, 2, *s.set, {});
    cmd.draw(6, s.count, 0, 0);
    recording.counters.draw("markers", 2ULL * s.count, s.count);
}

} // namespace atlas::passes
